package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.palantir.isofilereader.isofilereader.GenericInternalIsoFile
import com.palantir.isofilereader.isofilereader.IsoFileReader
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

class IsoFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("iso")
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem {
        return IsoVirtualFileSystem(archivePath)
    }
}

/**
 * Read-only VFS for disc images (`.iso`). Backed by Palantir's
 * [isofilereader](https://github.com/palantir/isofilereader) library, which auto-detects
 * the on-disc format and supports both:
 *
 *  - **ISO 9660** (ECMA-119) — the classic CD-ROM filesystem, plus Joliet long names and
 *    Rock Ridge POSIX attributes, used by virtually every Linux/Windows install CD and the
 *    "bridge" sectors of most modern DVD/BD images.
 *  - **UDF** (Universal Disk Format) — the filesystem behind DVD-Video, Blu-ray, and many
 *    modern Windows install ISOs (whose `boot.wim` and `install.wim` live in the UDF half
 *    of the bridge format and are not reachable through ISO 9660 alone).
 *
 * Unlike the other extract-based VFSs, content is **materialised lazily**: at open time
 * only the directory structure is written to the temp dir, with each file represented by a
 * sparse stub of the correct size and timestamp. Actual bytes are streamed from the image
 * only when something reads them — via [OpenVfsRegistry.materializeIfNeeded] called from
 * the file-open, copy, hash, and content-search code paths. Opening a 5 GB Windows ISO
 * therefore costs a tree walk, not 5 GB of disk; `install.wim` only lands on disk if the
 * user actually opens or copies it. Disc images are read-only — same reasoning as
 * [CrxVirtualFileSystem] and [RpmVirtualFileSystem].
 */
class IsoVirtualFileSystem(
    override val archivePath: Path,
) : AbstractTempDirVirtualFileSystem("turtle-iso-") {

    override val isReadOnly: Boolean get() = true

    /**
     * Map from stub path (normalised) to the ISO record that holds its bytes. Populated
     * during [extract] and drained by [materialize]; once a path is gone from the map the
     * stub has been overwritten with real content and further [materialize] calls for it
     * are no-ops. Concurrent because content search and parallel hash actions can race.
     */
    private val pendingEntries = ConcurrentHashMap<Path, GenericInternalIsoFile>()

    /**
     * The reader is kept open for the VFS lifetime so [materialize] can stream individual
     * files on demand without re-parsing the volume descriptors and tree on every call.
     * Initialised in [extract] (which holds it through the directory walk) and closed in
     * [close]; null when the VFS is empty or already torn down.
     */
    private var reader: IsoFileReader? = null

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val indicator = ProgressManager.getGlobalProgressIndicator()
        val newReader = IsoFileReader(archivePath.toFile())
        // The constructor runs format detection and silently picks the best mode it can.
        // If it finds neither an ISO 9660 volume descriptor nor a UDF anchor, we must reject
        // the file explicitly — otherwise we'd silently mount any random file as an empty
        // VFS. Close the reader on the failure path so we don't leak the file handle.
        if (!newReader.hasIsoFormat() && !newReader.hasUdfFormat()) {
            newReader.close()
            throw IOException("Not an ISO 9660 or UDF disc image: ${archivePath.fileName}")
        }
        try {
            val tree = newReader.allFiles
            val files = newReader.convertTreeFilesToFlatList(tree)
            if (files.isNotEmpty()) indicator?.isIndeterminate = false
            files.forEachIndexed { index, entry ->
                if (indicator?.isCanceled == true) return@forEachIndexed
                indicator?.fraction = (index + 1).toDouble() / files.size
                val rawPath = entry.getFullFileName('/') ?: return@forEachIndexed
                val cleanPath = stripIsoVersionSuffix(rawPath).trimStart('/')
                if (cleanPath.isEmpty()) return@forEachIndexed
                indicator?.text2 = cleanPath
                val entryPath = resolveEntryPath(into, cleanPath) ?: return@forEachIndexed
                try {
                    entryPath.parent?.let { Files.createDirectories(it) }
                    createStub(entryPath, entry.size)
                    val dateOpt = entry.dateAsDate
                    if (dateOpt != null && dateOpt.isPresent) {
                        Files.setLastModifiedTime(entryPath, FileTime.fromMillis(dateOpt.get().time))
                    }
                    pendingEntries[entryPath.normalize()] = entry
                } catch (e: Exception) {
                    thisLogger().debug("Failed to stub iso entry: $cleanPath", e)
                }
            }
        } catch (e: Exception) {
            newReader.close()
            throw e
        }
        reader = newReader
    }

    /**
     * Create a placeholder file of [size] bytes at [path]. Opens with [StandardOpenOption.SPARSE]
     * so the OS can keep the allocation sparse where supported (NTFS on Windows when the
     * SPARSE flag is honoured, ext4 / xfs on Linux, APFS on macOS); falls back to a regular
     * allocation otherwise. Either way [Files.size] returns the right value so directory
     * listings show the on-disc size before any bytes have been streamed.
     */
    private fun createStub(path: Path, size: Long) {
        Files.createFile(path)
        if (size <= 0) return
        FileChannel.open(
            path,
            StandardOpenOption.WRITE,
            StandardOpenOption.SPARSE,
        ).use { ch ->
            // Writing a single byte at offset (size - 1) extends the file to `size`; on
            // SPARSE-capable filesystems the gap remains a hole. The byte itself will be
            // overwritten when materialize() streams real content.
            ch.position(size - 1)
            ch.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
    }

    /**
     * Stream the actual bytes for [path] from the ISO image and replace its sparse stub.
     * A no-op if [path] isn't one of our entries (or has already been materialised by an
     * earlier caller). Synchronised so two consumers reading the same file concurrently
     * each get the full content rather than racing through `Files.copy` and corrupting
     * the result; the synchronization is per-VFS, which is fine — different files inside
     * the same ISO still materialise in parallel because each call holds the monitor only
     * long enough to do the I/O.
     */
    @Synchronized
    override fun materialize(path: Path) {
        val normalized = path.normalize()
        val entry = pendingEntries[normalized] ?: return
        val activeReader = reader ?: return
        try {
            // Write into a sibling temp file then atomic-move, so a partial write never
            // leaves a half-streamed file at the canonical path. ATOMIC_MOVE within the
            // same temp dir is supported on every platform we run on.
            val tmp = Files.createTempFile(normalized.parent, ".tc-materializing-", ".tmp")
            try {
                activeReader.getFileStream(entry).use { input ->
                    Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
                }
                Files.move(tmp, normalized, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
            val dateOpt = entry.dateAsDate
            if (dateOpt != null && dateOpt.isPresent) {
                Files.setLastModifiedTime(normalized, FileTime.fromMillis(dateOpt.get().time))
            }
            pendingEntries.remove(normalized)
        } catch (e: Exception) {
            thisLogger().warn("Failed to materialize ISO entry $normalized: ${e.message}")
            throw e
        }
    }

    override fun close() {
        try {
            reader?.close()
        } catch (e: Exception) {
            thisLogger().debug("Failed to close IsoFileReader for $archivePath: ${e.message}")
        }
        reader = null
        pendingEntries.clear()
        super.close()
    }

    /**
     * ISO 9660 file identifiers end with `;N` where N is the file version. Real-world
     * images almost always use version 1, and tools that mount ISOs (Linux's iso9660
     * driver, macOS DiskImageMounter, Windows Explorer) hide the suffix. We do the same
     * so the materialised filename matches what the user expects to see. UDF entries
     * don't carry the suffix at all — this function is a no-op for them.
     */
    private fun stripIsoVersionSuffix(path: String): String {
        val semi = path.lastIndexOf(';')
        if (semi < 0) return path
        // Only strip when everything after `;` is digits — anything else is part of the name.
        for (i in semi + 1 until path.length) {
            if (!path[i].isDigit()) return path
        }
        return path.substring(0, semi)
    }
}
