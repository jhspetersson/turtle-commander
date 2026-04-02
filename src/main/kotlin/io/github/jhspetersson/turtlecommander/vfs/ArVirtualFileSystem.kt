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

    override fun supports(path: Path): Boolean {
        val ext = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() ?: ""
        return ext in ARCHIVE_EXTENSIONS && Files.isRegularFile(path)
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

    private var tempDir: Path = extractArchive()

    override val root: Path get() = tempDir

    private fun extractArchive(): Path {
        val indicator = ProgressManager.getGlobalProgressIndicator()
        val dir = Files.createTempDirectory("turtle-ar-")
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
        Files.newOutputStream(archivePath).use { raw ->
            ArArchiveOutputStream(raw).use { ar ->
                Files.walk(tempDir).use { stream ->
                    stream.forEach { path ->
                        if (path == tempDir) return@forEach
                        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                        if (attrs.isDirectory) return@forEach // AR format is flat, skip directories
                        val relativeName = tempDir.relativize(path).toString().replace('\\', '/')
                        val entry = ArArchiveEntry(
                            relativeName,
                            attrs.size(),
                            0,
                            0,
                            0x81A4.toInt(), // rw-r--r--
                            attrs.lastModifiedTime().toMillis() / 1000,
                        )
                        ar.putArchiveEntry(entry)
                        Files.copy(path, ar)
                        ar.closeArchiveEntry()
                    }
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
