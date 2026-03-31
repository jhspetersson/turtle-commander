package io.github.jhspetersson.turtlecommander.vfs
import io.github.jhspetersson.turtlecommander.model.FileEntry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.Date

class SevenZipFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("7z")
    }

    override fun supports(path: Path): Boolean {
        val ext = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() ?: ""
        return ext in ARCHIVE_EXTENSIONS && Files.isRegularFile(path)
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem {
        return SevenZipVirtualFileSystem(archivePath)
    }
}

class SevenZipVirtualFileSystem(
    override val archivePath: Path,
) : VirtualFileSystem {

    private var tempDir: Path = extractArchive()

    override val root: Path get() = tempDir

    private fun extractArchive(): Path {
        val dir = Files.createTempDirectory("turtle-7z-")
        SevenZFile.builder().setPath(archivePath).get().use { sevenZ ->
            var entry = sevenZ.nextEntry
            while (entry != null) {
                val entryPath = dir.resolve(entry.name.removeSuffix("/"))
                if (!entryPath.normalize().startsWith(dir.normalize())) {
                    throw IOException("7z entry outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(entryPath)
                } else {
                    Files.createDirectories(entryPath.parent)
                    Files.newOutputStream(entryPath).use { out ->
                        val buf = ByteArray(8192)
                        var len: Int
                        while (sevenZ.read(buf).also { len = it } != -1) {
                            out.write(buf, 0, len)
                        }
                    }
                }
                try {
                    if (entry.hasLastModifiedDate) {
                        Files.setLastModifiedTime(entryPath, FileTime.fromMillis(entry.lastModifiedDate.time))
                    }
                } catch (_: Exception) {}
                entry = sevenZ.nextEntry
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
        val parent = source.parent ?: throw IllegalArgumentException("Cannot rename a root path")
        val target = parent.resolve(newName)
        Files.move(source, target)
        repackArchive()
        target
    }

    private fun repackArchive() {
        SevenZOutputFile(archivePath.toFile()).use { out ->
            Files.walk(tempDir).use { stream ->
                stream.forEach { path ->
                    if (path == tempDir) return@forEach
                    val relativeName = tempDir.relativize(path).toString().replace('\\', '/')
                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                    val entry = SevenZArchiveEntry().apply {
                        name = relativeName + if (attrs.isDirectory) "/" else ""
                        isDirectory = attrs.isDirectory
                        if (!attrs.isDirectory) size = attrs.size()
                        if (attrs.lastModifiedTime() != null) {
                            lastModifiedDate = Date(attrs.lastModifiedTime().toMillis())
                        }
                    }
                    out.putArchiveEntry(entry)
                    if (!attrs.isDirectory) {
                        Files.newInputStream(path).use { input ->
                            val buf = ByteArray(8192)
                            var len: Int
                            while (input.read(buf).also { len = it } != -1) {
                                out.write(buf, 0, len)
                            }
                        }
                    }
                    out.closeArchiveEntry()
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
