package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import io.github.jhspetersson.turtlecommander.model.FileEntry
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
import java.nio.file.attribute.BasicFileAttributes

class ZipFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf(
            // Plain ZIP and Java/Android variants
            "zip", "jar", "war", "ear", "apk", "aar", "apkg",
            // Microsoft Office Open XML
            "docx", "xlsx", "pptx",
            // OpenDocument
            "odt", "ods", "odp", "odg",
            // eBooks / comics
            "epub", "cbz",
            // NuGet
            "nupkg",
            // Firefox / Mozilla browser extensions (plain ZIPs)
            "xpi",
        )
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem {
        return try {
            ZipVirtualFileSystem(archivePath)
        } catch (_: Exception) {
            // Java ZipFileSystem rejects some valid ZIPs (invalid CEN header, etc.)
            // Fall back to Commons Compress extraction
            ZipExtractVirtualFileSystem(archivePath)
        }
    }
}

class ZipVirtualFileSystem(override val archivePath: Path) : VirtualFileSystem {
    private var fileSystem: FileSystem = openZipFs()

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

        result.addAll(readDirectoryEntries(directory))

        result
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
        fileSystem.close()
        fileSystem = openZipFs()
        val newParent = if (relativePath.isEmpty()) fileSystem.getPath("/") else fileSystem.getPath(relativePath)
        newParent.resolve(newName)
    }

    override fun flush() {
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
 * Extracts to a temp directory like Tar/AR VFS.
 */
class ZipExtractVirtualFileSystem(
    override val archivePath: Path,
) : AbstractTempDirVirtualFileSystem("turtle-zip-") {

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val indicator = ProgressManager.getGlobalProgressIndicator()
        ZipFile.builder().setPath(archivePath).get().use { zip ->
            // Previously we called zip.entries.toList() just to know the total for
            // the progress fraction, which allocated an O(n) reference list for
            // archives that can contain millions of entries (fat JARs, etc.). The
            // central directory is already parsed in memory, so iterating twice
            // is cheap and lets us keep determinate progress without the extra copy.
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
                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, entryPath)
                        }
                    }
                    if (entry.lastModifiedDate != null) {
                        Files.setLastModifiedTime(
                            entryPath,
                            java.nio.file.attribute.FileTime.fromMillis(entry.lastModifiedDate.time),
                        )
                    }
                } catch (e: Exception) {
                    thisLogger().debug("Failed to extract zip entry: ${entry.name}", e)
                }
            }
        }
    }

    override fun repack(from: Path) {
        ZipArchiveOutputStream(Files.newOutputStream(archivePath)).use { zip ->
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
