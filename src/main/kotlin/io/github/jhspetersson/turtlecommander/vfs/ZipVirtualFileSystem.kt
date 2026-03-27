package io.github.jhspetersson.turtlecommander.vfs
import io.github.jhspetersson.turtlecommander.model.FileEntry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        return ZipVirtualFileSystem(archivePath)
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

        try {
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
        } catch (_: Exception) {
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
