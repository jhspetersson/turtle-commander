package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.util.formatPosixMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

class ZipFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf(
            // Plain ZIP and Java/Android variants
            "zip", "jar", "war", "ear", "apk", "aar", "aab", "apkg",
            // iOS app packages (plain ZIPs with a Payload/ directory)
            "ipa",
            // Microsoft Office Open XML (incl. macro-enabled / template / Visio variants)
            "docx", "docm", "dotx", "dotm",
            "xlsx", "xlsm", "xltx", "xltm", "xlsb", "xlam",
            "pptx", "pptm", "potx", "potm", "ppsx", "ppsm", "ppam",
            "vsdx", "vsdm", "vstx", "vstm", "thmx",
            // OpenDocument (documents + templates)
            "odt", "ods", "odp", "odg", "odf", "odb", "odc", "odm",
            "ott", "ots", "otp", "otg",
            // eBooks / comics
            "epub", "cbz",
            // NuGet
            "nupkg",
            // Firefox / Mozilla browser extensions (plain ZIPs)
            "xpi",
            // VS Code / Visual Studio extensions (plain ZIPs)
            "vsix",
            // Quake 3 / id Tech 3 game data (plain ZIPs)
            "pk3",
            // Python wheels (PEP 427) and legacy setuptools eggs
            "whl", "egg",
        )
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem {
        return try {
            ZipVirtualFileSystem(archivePath)
        } catch (_: Exception) {
            // Java ZipFileSystem rejects some valid ZIPs (invalid CEN header, etc.)
            // Fall back to Commons Compress extraction
            ZipExtractVirtualFileSystem(archivePath, openProgress)
        }
    }
}

class ZipVirtualFileSystem(override val archivePath: Path) : VirtualFileSystem {
    private var fileSystem: FileSystem = openZipFs()

    /**
     * Unix permission strings parsed from the ZIP central directory, keyed by entry name (no
     * trailing slash). Java's [FileSystem] doesn't expose the stored Unix mode by default, so
     * we read it once with Commons Compress and cache it; lazily, because most browsing never
     * touches permissions and a fat JAR's central directory is large. Invalidated whenever the
     * archive is rewritten ([flush]) or an entry is renamed. ZIP stores no owner/group names,
     * so only [ArchiveEntryMetadata.permissions] is populated.
     */
    private var metadataCache: Map<String, ArchiveEntryMetadata>? = null

    override val root: Path get() = fileSystem.getPath("/")

    private fun openZipFs(): FileSystem {
        return FileSystems.newFileSystem(archivePath, mapOf<String, Any>("create" to "false"))
    }

    override fun isRoot(path: Path): Boolean {
        val pathStr = path.toString()
        return pathStr == "/" || pathStr.isEmpty()
    }

    override fun getPath(relativePath: String): Path {
        return fileSystem.getPath(relativePath)
    }

    override suspend fun listFiles(directory: Path): List<FileEntry> = withContext(Dispatchers.IO) {
        val result = mutableListOf<FileEntry>()

        if (!isRoot(directory)) {
            result.add(parentEntry(directory.parent ?: root))
        } else {
            result.add(parentEntry(archivePath.parent ?: archivePath))
        }

        result.addAll(readDirectoryEntries(directory) { entryMetadata(it) })

        result
    }

    override fun entryMetadata(path: Path): ArchiveEntryMetadata? {
        val key = path.toString().trim('/')
        if (key.isEmpty()) return null
        return metadata()[key]
    }

    private fun metadata(): Map<String, ArchiveEntryMetadata> =
        metadataCache ?: buildMetadata().also { metadataCache = it }

    private fun buildMetadata(): Map<String, ArchiveEntryMetadata> {
        val map = HashMap<String, ArchiveEntryMetadata>()
        try {
            ZipFile.builder().setPath(archivePath).get().use { zip ->
                val entries = zip.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val mode = entry.unixMode
                    if (mode == 0) continue // No external Unix attributes (e.g. ZIP written on Windows).
                    val name = entry.name.trimEnd('/')
                    if (name.isNotEmpty()) map[name] = ArchiveEntryMetadata(permissions = formatPosixMode(mode))
                }
            }
        } catch (e: Exception) {
            thisLogger().debug("Failed to read zip permissions for $archivePath: ${e.message}")
        }
        return map
    }

    override suspend fun renameFile(source: Path, newName: String): Path = withContext(Dispatchers.IO) {
        val oldParent = source.parent ?: fileSystem.getPath("/")
        val target = oldParent.resolve(newName)
        // Pre-check: Files.move on the zip filesystem throws FileAlreadyExistsException
        // if the target exists, which surfaces as a bare class name in the UI. Detect
        // the collision here so we can produce a descriptive error instead.
        if (target != source && Files.exists(target)) {
            throw FileAlreadyExistsException("Cannot rename to \"$newName\": entry already exists in archive")
        }
        val relativePath = if (oldParent == fileSystem.getPath("/")) "" else fileSystem.getPath("/").relativize(oldParent).toString()
        Files.move(source, target)
        metadataCache = null
        fileSystem.close()
        fileSystem = openZipFs()
        val newParent = if (relativePath.isEmpty()) fileSystem.getPath("/") else fileSystem.getPath(relativePath)
        newParent.resolve(newName)
    }

    override fun flush() {
        metadataCache = null
        fileSystem.close()
        fileSystem = openZipFs()
    }

    override fun close() {
        try {
            fileSystem.close()
        } catch (e: Exception) {
            thisLogger().debug("Failed to close zip filesystem for $archivePath: ${e.message}")
        }
    }
}

/**
 * Fallback ZIP VFS using Apache Commons Compress for ZIP files that Java's
 * built-in ZipFileSystem rejects (e.g. "Invalid CEN header").
 *
 * Like [IsoVirtualFileSystem], content is materialised lazily: at open time only the
 * directory structure is written to the temp dir, with each file represented by a sparse
 * stub of the correct size and timestamp. Actual bytes are streamed from the archive only
 * when something reads them — via [OpenVfsRegistry.materializeIfNeeded] from the file-open,
 * copy, hash, and content-search code paths. Unlike the ISO this VFS is writable, which
 * adds one rule: [repack] must materialise every remaining stub first, because the source
 * archive (the stubs' only backing store) is overwritten right after.
 */
class ZipExtractVirtualFileSystem(
    override val archivePath: Path,
    openProgress: VfsOpenProgress? = null,
) : AbstractTempDirVirtualFileSystem("turtle-zip-", openProgress) {

    /**
     * The archive entry backing a still-unmaterialised stub, plus the stub's size and
     * mtime as created. The metadata serves two purposes: [materialize] gives the
     * materialised file the stub's exact mtime (so materialisation never flips the
     * dirty-tracking fingerprint in the base class), and it detects a stub that was
     * overwritten or deleted by an external write (copy-into-archive replacing an entry)
     * — in that case the user's content wins and the pending record is just dropped.
     */
    private class PendingEntry(
        val entry: ZipArchiveEntry,
        val stubSize: Long,
        val stubMtime: FileTime,
    )

    /**
     * Map from stub path (normalised) to its pending record. Populated during [extract]
     * and drained by [materialize]; once a path is gone from the map the stub holds real
     * (or user-written) content and further [materialize] calls for it are no-ops.
     * Concurrent because content search and parallel hash actions can race.
     */
    private val pendingEntries = ConcurrentHashMap<Path, PendingEntry>()

    /**
     * Unix permission strings parsed from each ZIP entry's external attributes during
     * [extract], keyed by relative name in the temp dir. Surfaced in listings (and to
     * colorization rules) because the extracted stub only carries the host umask. ZIP records
     * no owner/group names, so only [ArchiveEntryMetadata.permissions] is populated.
     */
    private val entryMetadataByName = ConcurrentHashMap<String, ArchiveEntryMetadata>()

    /**
     * Kept open for the VFS lifetime so [materialize] can stream individual entries without
     * re-parsing the central directory on every call. Re-created by [extract] (which runs
     * again on every flush) and closed in [close] — and in [repack], before the archive is
     * rewritten underneath it.
     */
    private var zipReader: ZipFile? = null

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        // flush() re-extracts into a fresh temp dir: drop the reader and the stub
        // bookkeeping of the previous round first.
        closeReader()
        pendingEntries.clear()
        entryMetadataByName.clear()
        val zip = ZipFile.builder().setPath(archivePath).get()
        try {
            // Iterating the (already in-memory) central directory twice is cheaper than
            // zip.entries.toList() just to know the total for the progress fraction —
            // fat JARs can contain millions of entries.
            var total = 0
            val counter = zip.entries
            while (counter.hasMoreElements()) {
                counter.nextElement()
                total++
            }
            val iter = zip.entries
            var index = 0
            while (iter.hasMoreElements()) {
                val entry = iter.nextElement()
                if (progress.isCancelled) break
                index++
                progress.onEntry(index, total, entry.name)
                val entryPath = resolveEntryPath(into, entry.name) ?: continue
                val mode = entry.unixMode
                if (mode != 0) {
                    entryMetadataByName[into.relativize(entryPath).toString().replace('\\', '/')] =
                        ArchiveEntryMetadata(permissions = formatPosixMode(mode))
                }
                try {
                    if (entry.isDirectory) {
                        Files.createDirectories(entryPath)
                        if (entry.lastModifiedDate != null) {
                            Files.setLastModifiedTime(entryPath, FileTime.fromMillis(entry.lastModifiedDate.time))
                        }
                    } else {
                        Files.createDirectories(entryPath.parent)
                        createSparseStub(entryPath, entry.size)
                        if (entry.lastModifiedDate != null) {
                            Files.setLastModifiedTime(entryPath, FileTime.fromMillis(entry.lastModifiedDate.time))
                        }
                        pendingEntries[entryPath.normalize()] = PendingEntry(
                            entry,
                            stubSize = Files.size(entryPath),
                            stubMtime = Files.getLastModifiedTime(entryPath),
                        )
                    }
                } catch (e: Exception) {
                    thisLogger().debug("Failed to stub zip entry: ${entry.name}", e)
                }
            }
        } catch (e: Exception) {
            runCatching { zip.close() }
            throw e
        }
        zipReader = zip
    }

    /**
     * Stream the actual bytes for [path] from the archive and replace its sparse stub.
     * A no-op if [path] isn't one of our entries or has already been materialised — and a
     * deliberate no-op (record dropped) when the stub was overwritten or deleted by an
     * external write, so a copy-into-archive or delete is never clobbered/resurrected by
     * a later materialisation. Synchronised so two consumers reading the same file
     * concurrently each get the full content rather than racing through `Files.copy`.
     */
    @Synchronized
    override fun materialize(path: Path) {
        val normalized = path.normalize()
        val pending = pendingEntries[normalized] ?: return
        val zip = zipReader ?: return
        val current = runCatching {
            Files.size(normalized) to Files.getLastModifiedTime(normalized)
        }.getOrNull()
        if (current == null || current.first != pending.stubSize || current.second != pending.stubMtime) {
            // Stub gone or replaced in place: whatever is (or isn't) there now is the
            // user's doing, not ours to overwrite.
            pendingEntries.remove(normalized)
            return
        }
        try {
            // Write into a sibling temp file then atomic-move, so a partial write never
            // leaves a half-streamed file at the canonical path.
            val tmp = Files.createTempFile(normalized.parent, ".tc-materializing-", ".tmp")
            try {
                zip.getInputStream(pending.entry).use { input ->
                    Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
                }
                Files.move(tmp, normalized, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
            // Exactly the stub's mtime, so materialisation is invisible to the base
            // class's dirty-tracking fingerprint.
            Files.setLastModifiedTime(normalized, pending.stubMtime)
            pendingEntries.remove(normalized)
        } catch (e: Exception) {
            thisLogger().warn("Failed to materialize zip entry $normalized: ${e.message}")
            throw e
        }
    }

    /**
     * [renameFile] in the base class moves the stub on disk; keep the pending map keyed
     * by the stub's new location so [materialize] (and the repack below) stream the bytes
     * to where the stub actually lives now.
     */
    @Synchronized
    override fun onRenamed(source: Path, target: Path) {
        pendingEntries.remove(source.normalize())?.let { entry ->
            pendingEntries[target.normalize()] = entry
        }
        val oldName = tempDir.relativize(source).toString().replace('\\', '/')
        val newName = tempDir.relativize(target).toString().replace('\\', '/')
        entryMetadataByName.remove(oldName)?.let { entryMetadataByName[newName] = it }
    }

    override fun entryMetadata(path: Path): ArchiveEntryMetadata? =
        entryMetadataByName[tempDir.relativize(path).toString().replace('\\', '/')]

    /**
     * Materialise every still-pending stub before [repack] overwrites the archive they
     * stream from. An entry whose bytes can't be read is dropped from the temp dir (and
     * thus from the repacked archive) — the same outcome the eager extractor produced
     * when an entry failed to extract — rather than repacking a zero-filled stub.
     */
    @Synchronized
    private fun materializeAllForRepack() {
        for (path in pendingEntries.keys.toList()) {
            try {
                materialize(path)
            } catch (e: Exception) {
                thisLogger().warn("Dropping unreadable zip entry from repack: $path (${e.message})")
                runCatching { Files.deleteIfExists(path) }
                pendingEntries.remove(path)
            }
        }
    }

    override fun repack(from: Path) {
        materializeAllForRepack()
        // Release the read handle: the atomic move below replaces the same file.
        closeReader()
        repackAtomically(archivePath) { target ->
            ZipArchiveOutputStream(Files.newOutputStream(target)).use { zip ->
                forEachArchiveEntry(from) { path, relativeName ->
                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                    val entryName = if (attrs.isDirectory) "$relativeName/" else relativeName
                    val entry = ZipArchiveEntry(entryName)
                    entry.time = attrs.lastModifiedTime().toMillis()
                    if (!attrs.isDirectory) {
                        entry.size = attrs.size()
                    }
                    zip.putArchiveEntry(entry)
                    if (!attrs.isDirectory) {
                        Files.copy(path, zip)
                    }
                    zip.closeArchiveEntry()
                }
            }
        }
    }

    private fun closeReader() {
        try {
            zipReader?.close()
        } catch (e: Exception) {
            thisLogger().debug("Failed to close zip reader for $archivePath: ${e.message}")
        }
        zipReader = null
    }

    override fun close() {
        closeReader()
        pendingEntries.clear()
        super.close()
    }
}
