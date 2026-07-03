package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class RpmFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("rpm", "srpm")
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem {
        return RpmVirtualFileSystem(archivePath, openProgress)
    }
}

/**
 * Read-only VFS for RPM packages. Layout of an RPM file:
 *
 *   [lead 96 B] [signature header] [pad to 8 B] [main header] [compressed cpio payload]
 *
 * Each header begins with a 16-byte intro (3-byte magic 0x8e 0xad 0xe8, 1-byte
 * version 0x01, 4 reserved bytes, big-endian uint32 index count, big-endian
 * uint32 data-store size), followed by `nindex` 16-byte index entries and
 * `hsize` bytes of string/numeric data. The signature header is padded to an
 * 8-byte boundary; the main header is not. Past both headers, the remainder of
 * the file is a single compressed cpio stream — the format named by tag 1125
 * (RPMTAG_PAYLOADCOMPRESSOR): gzip / bzip2 / xz / lzma / zstd. The cpio entries
 * carry the file names (typically prefixed with "./").
 *
 * RPMs are signed, so write-back would invalidate the package. We extract once
 * and stay read-only — same reasoning as [CrxVirtualFileSystem].
 */
class RpmVirtualFileSystem(
    override val archivePath: Path,
    openProgress: VfsOpenProgress? = null,
) : AbstractTempDirVirtualFileSystem("turtle-rpm-", openProgress) {

    override val isReadOnly: Boolean get() = true

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        BufferedInputStream(Files.newInputStream(archivePath)).use { raw ->
            val data = DataInputStream(raw)
            skipLead(data)
            // Signature header is padded to an 8-byte boundary; main header isn't.
            val sigBytes = skipHeader(data, peekCompressor = false)
            val sigPadding = (8 - (sigBytes % 8)) % 8
            skipFully(data, sigPadding.toLong())
            val compressor = readMainHeaderCompressor(data)
            val payload = wrapCompressor(raw, compressor)
            CpioArchiveInputStream(payload).use { cpio ->
                var entry = cpio.nextEntry
                while (entry != null) {
                    if (progress.isCancelled) break
                    val name = entry.name.removePrefix("./").removePrefix("/")
                    progress.onEntry(0, 0, name)
                    if (name.isEmpty() || name == "TRAILER!!!") {
                        entry = cpio.nextEntry
                        continue
                    }
                    val entryPath = resolveEntryPath(into, name)
                    if (entryPath == null) {
                        entry = cpio.nextEntry
                        continue
                    }
                    try {
                        when {
                            entry.isDirectory -> Files.createDirectories(entryPath)
                            entry.isSymbolicLink -> {
                                // cpio stores the link target as the entry body.
                                val target = cpio.readBytes().toString(Charsets.UTF_8)
                                if (symlinkTargetEscapes(into, entryPath, target)) {
                                    // Refuse a link pointing outside the extraction root — see
                                    // TarVirtualFileSystem for the symlink-then-child escape.
                                    thisLogger().warn("Skipping rpm symlink escaping the archive root: $name -> $target")
                                } else {
                                    entryPath.parent?.let { Files.createDirectories(it) }
                                    try {
                                        Files.createSymbolicLink(entryPath, Path.of(target))
                                    } catch (_: Exception) {
                                        // Windows without SeCreateSymbolicLinkPrivilege, or weird targets.
                                        // Materialize as a plain text file so the contents aren't lost.
                                        Files.writeString(entryPath, target)
                                    }
                                }
                            }
                            else -> {
                                Files.createDirectories(entryPath.parent)
                                Files.copy(cpio, entryPath)
                            }
                        }
                        val mtime = entry.lastModifiedDate?.time
                        if (mtime != null && mtime > 0) {
                            Files.setLastModifiedTime(entryPath, FileTime.fromMillis(mtime))
                        }
                    } catch (e: Exception) {
                        thisLogger().debug("Failed to extract rpm entry: $name", e)
                    }
                    entry = cpio.nextEntry
                }
            }
        }
    }

    private fun skipLead(data: DataInputStream) {
        val magic = ByteArray(4)
        data.readFully(magic)
        if (magic[0] != 0xed.toByte() ||
            magic[1] != 0xab.toByte() ||
            magic[2] != 0xee.toByte() ||
            magic[3] != 0xdb.toByte()
        ) {
            throw IOException("Not an RPM file: missing 0xedabeedb lead magic")
        }
        // Lead is fixed 96 bytes total; we've consumed 4.
        skipFully(data, 92)
    }

    /**
     * Reads a header intro + index + data store, optionally inspecting tags to find
     * the payload compressor. Returns the number of bytes consumed from the
     * intro onward (so callers can compute the 8-byte padding for the signature).
     */
    private fun skipHeader(data: DataInputStream, peekCompressor: Boolean): Int {
        val intro = ByteArray(16)
        data.readFully(intro)
        if (intro[0] != 0x8e.toByte() ||
            intro[1] != 0xad.toByte() ||
            intro[2] != 0xe8.toByte() ||
            intro[3] != 0x01.toByte()
        ) {
            throw IOException("Bad RPM header magic")
        }
        val nindex = beUInt32(intro, 8)
        val hsize = beUInt32(intro, 12)
        if (nindex < 0 || hsize < 0 || nindex > MAX_INDEX_ENTRIES || hsize > MAX_DATA_STORE) {
            throw IOException("RPM header bounds out of range (nindex=$nindex, hsize=$hsize)")
        }
        val indexBytes = nindex * 16
        val total = 16 + indexBytes + hsize
        if (!peekCompressor) {
            skipFully(data, (indexBytes + hsize).toLong())
        }
        return total
    }

    /**
     * Walks the main header looking for RPMTAG_PAYLOADCOMPRESSOR (1125). Falls
     * back to gzip if absent — that's what `rpm` itself assumes for legacy v3
     * packages that predate the tag.
     */
    private fun readMainHeaderCompressor(data: DataInputStream): String {
        val intro = ByteArray(16)
        data.readFully(intro)
        if (intro[0] != 0x8e.toByte() ||
            intro[1] != 0xad.toByte() ||
            intro[2] != 0xe8.toByte() ||
            intro[3] != 0x01.toByte()
        ) {
            throw IOException("Bad RPM main header magic")
        }
        val nindex = beUInt32(intro, 8)
        val hsize = beUInt32(intro, 12)
        if (nindex < 0 || hsize < 0 || nindex > MAX_INDEX_ENTRIES || hsize > MAX_DATA_STORE) {
            throw IOException("RPM main header bounds out of range (nindex=$nindex, hsize=$hsize)")
        }
        val index = ByteArray(nindex * 16)
        data.readFully(index)
        val store = ByteArray(hsize)
        data.readFully(store)
        for (i in 0 until nindex) {
            val tag = beUInt32(index, i * 16)
            val type = beUInt32(index, i * 16 + 4)
            val offset = beUInt32(index, i * 16 + 8)
            if (tag == RPMTAG_PAYLOADCOMPRESSOR && type == TYPE_STRING) {
                return readCString(store, offset).ifEmpty { "gzip" }
            }
        }
        return "gzip"
    }

    private fun wrapCompressor(raw: InputStream, name: String): InputStream {
        return when (name.lowercase()) {
            "gzip" -> GzipCompressorInputStream(raw)
            "bzip2" -> BZip2CompressorInputStream(raw)
            "xz" -> XZCompressorInputStream(raw)
            "lzma" -> LZMACompressorInputStream(raw)
            "zstd" -> ZstdCompressorInputStream(raw)
            else -> throw IOException("Unsupported RPM payload compressor: $name")
        }
    }

    private fun skipFully(data: DataInputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = data.skip(remaining)
            if (skipped <= 0) {
                if (data.read() == -1) throw IOException("Unexpected EOF in RPM header")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun beUInt32(buf: ByteArray, off: Int): Int {
        // RPM headers cap individual fields well below 2^31, so a signed Int is fine
        // and avoids the Long arithmetic that callers don't need.
        val b0 = buf[off].toInt() and 0xFF
        val b1 = buf[off + 1].toInt() and 0xFF
        val b2 = buf[off + 2].toInt() and 0xFF
        val b3 = buf[off + 3].toInt() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun readCString(store: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= store.size) return ""
        var end = offset
        while (end < store.size && store[end] != 0.toByte()) end++
        return String(store, offset, end - offset, Charsets.UTF_8)
    }

    companion object {
        private const val RPMTAG_PAYLOADCOMPRESSOR = 1125
        private const val TYPE_STRING = 6
        // Generous sanity caps so a malformed header can't trick us into a huge alloc.
        // Real RPMs have ~100s of index entries and data stores under a megabyte.
        private const val MAX_INDEX_ENTRIES = 1_000_000
        private const val MAX_DATA_STORE = 64 * 1024 * 1024
    }
}
