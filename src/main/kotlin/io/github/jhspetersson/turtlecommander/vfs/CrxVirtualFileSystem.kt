package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class CrxFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("crx")
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem {
        return CrxVirtualFileSystem(archivePath)
    }
}

/**
 * VFS for Chrome / Chromium extension packages (`.crx`). A CRX file is a ZIP
 * prefixed with a signature header:
 *
 *  - CRX2: `Cr24` magic, version=2, public-key length, signature length, then the
 *    key bytes, signature bytes, and finally the ZIP payload.
 *  - CRX3: `Cr24` magic, version=3, header length, the header proto, and the ZIP.
 *
 * All length fields are little-endian uint32. We parse the header, stream the ZIP
 * payload into a temp file, then extract that like [ZipExtractVirtualFileSystem].
 * The CRX is read-only: any edit would break the signature, making the result
 * unloadable by the browser, so write-back has no useful semantics.
 */
class CrxVirtualFileSystem(
    override val archivePath: Path,
) : AbstractTempDirVirtualFileSystem("turtle-crx-") {

    override val isReadOnly: Boolean get() = true

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val indicator = ProgressManager.getGlobalProgressIndicator()
        val innerZip = Files.createTempFile("turtle-crx-inner-", ".zip")
        try {
            Files.newInputStream(archivePath).use { input ->
                skipCrxHeader(input)
                Files.newOutputStream(innerZip).use { output ->
                    input.copyTo(output)
                }
            }

            ZipFile.builder().setPath(innerZip).get().use { zip ->
                var total = 0
                val counter = zip.entries
                while (counter.hasMoreElements()) {
                    counter.nextElement()
                    total++
                }
                if (total > 0) indicator?.isIndeterminate = false
                val iter = zip.entries
                var index = 0
                while (iter.hasMoreElements()) {
                    val entry = iter.nextElement()
                    if (indicator?.isCanceled == true) break
                    index++
                    if (total > 0) indicator?.fraction = index.toDouble() / total
                    indicator?.text2 = entry.name
                    val entryPath = resolveEntryPath(into, entry.name) ?: continue
                    try {
                        if (entry.isDirectory) {
                            Files.createDirectories(entryPath)
                        } else {
                            Files.createDirectories(entryPath.parent)
                            zip.getInputStream(entry).use { stream ->
                                Files.copy(stream, entryPath)
                            }
                        }
                        if (entry.lastModifiedDate != null) {
                            Files.setLastModifiedTime(
                                entryPath,
                                FileTime.fromMillis(entry.lastModifiedDate.time),
                            )
                        }
                    } catch (e: Exception) {
                        thisLogger().debug("Failed to extract crx entry: ${entry.name}", e)
                    }
                }
            }
        } finally {
            try {
                Files.deleteIfExists(innerZip)
            } catch (e: Exception) {
                thisLogger().debug("Failed to delete inner crx zip $innerZip: ${e.message}")
            }
        }
    }

    private fun skipCrxHeader(input: InputStream) {
        val data = DataInputStream(input)
        val magic = ByteArray(4)
        data.readFully(magic)
        if (magic[0] != 'C'.code.toByte() ||
            magic[1] != 'r'.code.toByte() ||
            magic[2] != '2'.code.toByte() ||
            magic[3] != '4'.code.toByte()
        ) {
            throw IOException("Not a CRX file: missing 'Cr24' magic header")
        }
        val version = readLeUInt32(data)
        when (version) {
            2L -> {
                val pubKeyLen = readLeUInt32(data)
                val sigLen = readLeUInt32(data)
                skipFully(data, pubKeyLen + sigLen)
            }
            3L -> {
                val headerLen = readLeUInt32(data)
                skipFully(data, headerLen)
            }
            else -> throw IOException("Unsupported CRX version: $version")
        }
    }

    private fun readLeUInt32(data: DataInputStream): Long {
        val b = ByteArray(4)
        data.readFully(b)
        return (b[0].toInt() and 0xFF).toLong() or
            ((b[1].toInt() and 0xFF).toLong() shl 8) or
            ((b[2].toInt() and 0xFF).toLong() shl 16) or
            ((b[3].toInt() and 0xFF).toLong() shl 24)
    }

    private fun skipFully(data: DataInputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = data.skip(remaining)
            if (skipped <= 0) {
                // skip() can return 0 prematurely on some streams; force a single-byte
                // read so an unexpected EOF surfaces as an explicit error.
                if (data.read() == -1) throw IOException("Unexpected EOF in CRX header")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}
