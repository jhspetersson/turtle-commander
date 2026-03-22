package io.github.jhspetersson.turtlecommander

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class GzFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("gz", "tgz")
    }

    override fun supports(path: Path): Boolean {
        val ext = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() ?: ""
        return ext in ARCHIVE_EXTENSIONS && Files.isRegularFile(path)
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem {
        val name = archivePath.fileName?.toString()?.lowercase() ?: ""
        return if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            TarVirtualFileSystem(archivePath) { GzipCompressorInputStream(Files.newInputStream(it)) }
        } else {
            GzSingleFileVirtualFileSystem(archivePath)
        }
    }
}

class GzSingleFileVirtualFileSystem(
    override val archivePath: Path,
) : VirtualFileSystem {

    private var tempDir: Path = extractFile()

    override val root: Path get() = tempDir

    private fun extractFile(): Path {
        val dir = Files.createTempDirectory("turtle-gz-")
        val originalName = archivePath.fileName?.toString() ?: "file"
        val innerName = originalName.removeSuffix(".gz").removeSuffix(".GZ")
        val destPath = dir.resolve(innerName)
        GzipCompressorInputStream(Files.newInputStream(archivePath)).use { gz ->
            Files.copy(gz, destPath)
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

        try {
            Files.newDirectoryStream(directory).use { stream ->
                for (entry in stream) {
                    try {
                        val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java)
                        result.add(
                            FileEntry(
                                name = entry.fileName.toString(),
                                path = entry,
                                isDirectory = attrs.isDirectory,
                                size = if (attrs.isDirectory) 0 else attrs.size(),
                                lastModified = attrs.lastModifiedTime(),
                                permissions = "",
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }

        result
    }

    override fun flush() {
        tempDir.toFile().deleteRecursively()
        tempDir = extractFile()
    }

    override fun close() {
        try {
            tempDir.toFile().deleteRecursively()
        } catch (_: Exception) {
        }
    }
}
