package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import io.github.jhspetersson.turtlecommander.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

class ArFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("ar", "a", "deb")
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem {
        return ArVirtualFileSystem(archivePath)
    }
}

class ArVirtualFileSystem(
    override val archivePath: Path,
) : VirtualFileSystem {

    private data class ArEntryMetadata(
        val mode: Int,
        val userId: Int,
        val groupId: Int,
        val lastModifiedSeconds: Long,
    )

    /**
     * Metadata captured at extract time keyed by the original AR entry name. Repack uses this
     * to preserve mode/uid/gid/mtime rather than resetting every entry to 0644/root/root.
     * Entries that were created after extraction (not present here) fall back to sensible defaults.
     */
    private val entryMetadata = mutableMapOf<String, ArEntryMetadata>()

    private var tempDir: Path = extractArchive()

    override val root: Path get() = tempDir

    private fun extractArchive(): Path {
        val indicator = ProgressManager.getGlobalProgressIndicator()
        val dir = Files.createTempDirectory("turtle-ar-")
        entryMetadata.clear()
        try {
            Files.newInputStream(archivePath).use { raw ->
                ArArchiveInputStream(raw).use { ar ->
                    var entry = ar.nextEntry
                    while (entry != null) {
                        if (indicator?.isCanceled == true) break
                        indicator?.text2 = entry.name
                        val entryPath = resolveEntryPath(dir, entry.name)
                        if (entryPath == null) {
                            entry = ar.nextEntry
                            continue
                        }
                        Files.createDirectories(entryPath.parent)
                        Files.copy(ar, entryPath)
                        entryMetadata[entry.name] = ArEntryMetadata(
                            mode = entry.mode,
                            userId = entry.userId,
                            groupId = entry.groupId,
                            lastModifiedSeconds = entry.lastModified,
                        )
                        try {
                            if (entry.lastModifiedDate != null) {
                                Files.setLastModifiedTime(entryPath, FileTime.fromMillis(entry.lastModifiedDate.time))
                            }
                        } catch (e: Exception) {
                            thisLogger().debug("Failed to set modified time for ar entry: ${entry.name}", e)
                        }
                        entry = ar.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            dir.toFile().deleteRecursively()
            throw e
        }
        return dir
    }

    override fun isRoot(path: Path): Boolean {
        return path.normalize() == tempDir.normalize()
    }

    override fun getPath(relativePath: String): Path {
        val clean = relativePath.removePrefix("/").removePrefix("\\")
        return if (clean.isEmpty()) tempDir else tempDir.resolve(clean)
    }

    override suspend fun listFiles(directory: Path): List<FileEntry> = withContext(Dispatchers.IO) {
        val result = mutableListOf<FileEntry>()

        if (!isRoot(directory)) {
            result.add(parentEntry(directory.parent ?: tempDir))
        } else {
            result.add(parentEntry(archivePath.parent ?: archivePath))
        }

        result.addAll(readDirectoryEntries(directory))

        result
    }

    override suspend fun renameFile(source: Path, newName: String): Path = withContext(Dispatchers.IO) {
        val parent = source.parent ?: throw IllegalArgumentException("Cannot rename a root path")
        val target = parent.resolve(newName)
        Files.move(source, target)
        // Carry metadata over to the new name so repack keeps the original mode/uid/gid/mtime.
        val oldName = tempDir.relativize(source).toString().replace('\\', '/')
        val newRelativeName = tempDir.relativize(target).toString().replace('\\', '/')
        entryMetadata.remove(oldName)?.let { entryMetadata[newRelativeName] = it }
        repackArchive()
        target
    }

    private fun repackArchive() {
        Files.newOutputStream(archivePath).use { raw ->
            ArArchiveOutputStream(raw).use { ar ->
                forEachArchiveEntry(tempDir) { path, relativeName ->
                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                    if (attrs.isDirectory) return@forEachArchiveEntry // AR format is flat, skip directories
                    val metadata = entryMetadata[relativeName]
                    val entry = ArArchiveEntry(
                        relativeName,
                        attrs.size(),
                        metadata?.userId ?: 0,
                        metadata?.groupId ?: 0,
                        metadata?.mode ?: DEFAULT_FILE_MODE,
                        metadata?.lastModifiedSeconds ?: (attrs.lastModifiedTime().toMillis() / 1000),
                    )
                    ar.putArchiveEntry(entry)
                    Files.copy(path, ar)
                    ar.closeArchiveEntry()
                }
            }
        }
    }

    companion object {
        // Default mode for newly added entries that have no recorded metadata: 0100644 (rw-r--r--).
        private const val DEFAULT_FILE_MODE = 0x81A4
    }

    override fun flush() {
        repackArchive()
        tempDir.toFile().deleteRecursively()
        tempDir = extractArchive()
    }

    override fun close() {
        try {
            tempDir.toFile().deleteRecursively()
        } catch (e: Exception) {
            thisLogger().debug("Failed to clean up temp dir $tempDir: ${e.message}")
        }
    }
}
