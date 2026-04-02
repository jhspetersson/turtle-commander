package io.github.jhspetersson.turtlecommander.vfs

import io.github.jhspetersson.turtlecommander.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class ZipFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("zip", "jar", "war", "ear")
    }

    override fun supports(path: Path): Boolean {
        val ext = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() ?: ""
        return ext in ARCHIVE_EXTENSIONS && Files.isRegularFile(path)
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
            val parent = directory.parent ?: root
            result.add(
                FileEntry(
                    name = "..",
                    path = parent,
                    isDirectory = true,
                    size = 0,
                    lastModified = null,
                    permissions = "",
                    isParentLink = true,
                )
            )
        } else {
            result.add(
                FileEntry(
                    name = "..",
                    path = archivePath.parent ?: archivePath,
                    isDirectory = true,
                    size = 0,
                    lastModified = null,
                    permissions = "",
                    isParentLink = true,
                )
            )
        }

        Files.newDirectoryStream(directory).use { stream ->
            val dirs = mutableListOf<FileEntry>()
            val files = mutableListOf<FileEntry>()

            for (entry in stream) {
                try {
                    val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java)
                    val fileEntry = FileEntry(
                        name = entry.fileName.toString(),
                        path = entry,
                        isDirectory = attrs.isDirectory,
                        size = if (attrs.isDirectory) 0 else attrs.size(),
                        creationTime = attrs.creationTime(),
                        lastModified = attrs.lastModifiedTime(),
                        permissions = "",
                    )
                    if (attrs.isDirectory) dirs.add(fileEntry) else files.add(fileEntry)
                } catch (_: Exception) {
                }
            }

            dirs.sortBy { it.name.lowercase() }
            files.sortBy { it.name.lowercase() }
            result.addAll(dirs)
            result.addAll(files)
        }

        result
    }

    override suspend fun renameFile(source: Path, newName: String): Path = withContext(Dispatchers.IO) {
        val oldParent = source.parent ?: fileSystem.getPath("/")
        val relativePath = if (oldParent == fileSystem.getPath("/")) "" else fileSystem.getPath("/").relativize(oldParent).toString()
        Files.move(source, oldParent.resolve(newName))
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
        } catch (_: Exception) {
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
) : VirtualFileSystem {

    private var tempDir: Path = extractArchive()

    override val root: Path get() = tempDir

    private fun extractArchive(): Path {
        val dir = Files.createTempDirectory("turtle-zip-")
        ZipFile.builder().setPath(archivePath).get().use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryPath = dir.resolve(entry.name.removeSuffix("/"))
                if (!entryPath.normalize().startsWith(dir.normalize())) continue
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
                } catch (_: Exception) {}
            }
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
            val parent = directory.parent ?: tempDir
            result.add(
                FileEntry(
                    name = "..",
                    path = parent,
                    isDirectory = true,
                    size = 0,
                    lastModified = null,
                    permissions = "",
                    isParentLink = true,
                )
            )
        } else {
            result.add(
                FileEntry(
                    name = "..",
                    path = archivePath.parent ?: archivePath,
                    isDirectory = true,
                    size = 0,
                    lastModified = null,
                    permissions = "",
                    isParentLink = true,
                )
            )
        }

        Files.newDirectoryStream(directory).use { stream ->
            val dirs = mutableListOf<FileEntry>()
            val files = mutableListOf<FileEntry>()

            for (entry in stream) {
                try {
                    val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java)
                    val fileEntry = FileEntry(
                        name = entry.fileName.toString(),
                        path = entry,
                        isDirectory = attrs.isDirectory,
                        size = if (attrs.isDirectory) 0 else attrs.size(),
                        creationTime = attrs.creationTime(),
                        lastModified = attrs.lastModifiedTime(),
                        permissions = "",
                    )
                    if (attrs.isDirectory) dirs.add(fileEntry) else files.add(fileEntry)
                } catch (_: Exception) {
                }
            }

            dirs.sortBy { it.name.lowercase() }
            files.sortBy { it.name.lowercase() }
            result.addAll(dirs)
            result.addAll(files)
        }

        result
    }

    override suspend fun renameFile(source: Path, newName: String): Path = withContext(Dispatchers.IO) {
        val parent = source.parent ?: throw IllegalArgumentException("Cannot rename a root path")
        val target = parent.resolve(newName)
        Files.move(source, target)
        repackArchive()
        target
    }

    private fun repackArchive() {
        ZipArchiveOutputStream(Files.newOutputStream(archivePath)).use { zip ->
            Files.walk(tempDir).use { stream ->
                stream.forEach { path ->
                    if (path == tempDir) return@forEach
                    try {
                        val relativeName = tempDir.relativize(path).toString().replace('\\', '/')
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
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun flush() {
        repackArchive()
        tempDir.toFile().deleteRecursively()
        tempDir = extractArchive()
    }

    override fun close() {
        try {
            tempDir.toFile().deleteRecursively()
        } catch (_: Exception) {
        }
    }
}
