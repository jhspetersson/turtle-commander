package io.github.jhspetersson.turtlecommander

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

class TarFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("tar")
    }

    override fun supports(path: Path): Boolean {
        val ext = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() ?: ""
        return ext in ARCHIVE_EXTENSIONS && Files.isRegularFile(path)
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem {
        return TarVirtualFileSystem(
            archivePath,
            inputStreamFactory = { Files.newInputStream(it) },
            outputStreamFactory = { Files.newOutputStream(it) },
        )
    }
}

class TarVirtualFileSystem(
    override val archivePath: Path,
    private val inputStreamFactory: (Path) -> InputStream,
    private val outputStreamFactory: ((Path) -> OutputStream)? = null,
) : VirtualFileSystem {

    private var tempDir: Path = extractArchive()

    override val root: Path get() = tempDir

    private fun extractArchive(): Path {
        val dir = Files.createTempDirectory("turtle-tar-")
        inputStreamFactory(archivePath).use { raw ->
            TarArchiveInputStream(raw).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val entryPath = dir.resolve(entry.name.removeSuffix("/"))
                    if (!entryPath.normalize().startsWith(dir.normalize())) {
                        throw IOException("Tar entry outside target dir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        Files.createDirectories(entryPath)
                    } else {
                        Files.createDirectories(entryPath.parent)
                        Files.copy(tar, entryPath)
                    }
                    try {
                        if (entry.lastModifiedDate != null) {
                            Files.setLastModifiedTime(entryPath, FileTime.fromMillis(entry.lastModifiedDate.time))
                        }
                    } catch (_: Exception) {}
                    entry = tar.nextEntry
                }
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
        val target = source.parent.resolve(newName)
        Files.move(source, target)
        repackArchive()
        target
    }

    private fun repackArchive() {
        val outFactory = outputStreamFactory ?: { path -> Files.newOutputStream(path) }
        outFactory(archivePath).use { raw ->
            TarArchiveOutputStream(raw).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                Files.walk(tempDir).use { stream ->
                    stream.forEach { path ->
                        if (path == tempDir) return@forEach
                        val relativeName = tempDir.relativize(path).toString().replace('\\', '/')
                        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                        val entry = TarArchiveEntry(path, relativeName)
                        entry.size = if (attrs.isDirectory) 0 else attrs.size()
                        entry.modTime = java.util.Date(attrs.lastModifiedTime().toMillis())
                        tar.putArchiveEntry(entry)
                        if (!attrs.isDirectory) {
                            Files.copy(path, tar)
                        }
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
    }

    override fun flush() {
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
