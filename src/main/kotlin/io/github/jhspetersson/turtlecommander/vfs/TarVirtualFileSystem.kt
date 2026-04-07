package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import io.github.jhspetersson.turtlecommander.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.*

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
        val indicator = ProgressManager.getGlobalProgressIndicator()
        val dir = Files.createTempDirectory("turtle-tar-")
        try {
            inputStreamFactory(archivePath).use { raw ->
                TarArchiveInputStream(raw).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (indicator?.isCanceled == true) break
                        indicator?.text2 = entry.name
                        val entryPath = resolveEntryPath(dir, entry.name)
                        if (entryPath == null) {
                            entry = tar.nextEntry
                            continue
                        }
                        try {
                            if (entry.isSymbolicLink) {
                                val linkTarget = entry.linkName
                                Files.createDirectories(entryPath.parent)
                                try {
                                    Files.createSymbolicLink(entryPath, java.nio.file.Paths.get(linkTarget))
                                } catch (_: Exception) {
                                    // Symlink creation may fail (e.g. Windows without privileges), skip
                                }
                            } else if (entry.isDirectory) {
                                Files.createDirectories(entryPath)
                            } else if (entry.isLink) {
                                // Hard link
                                val linkTarget = dir.resolve(entry.linkName)
                                Files.createDirectories(entryPath.parent)
                                try {
                                    Files.createLink(entryPath, linkTarget)
                                } catch (_: Exception) {
                                    // Hard link may fail, try copying the target instead
                                    if (Files.exists(linkTarget)) {
                                        Files.copy(linkTarget, entryPath)
                                    }
                                }
                            } else {
                                Files.createDirectories(entryPath.parent)
                                Files.copy(tar, entryPath)
                            }
                            if (entry.lastModifiedDate != null) {
                                Files.setLastModifiedTime(entryPath, FileTime.fromMillis(entry.lastModifiedDate.time))
                            }
                        } catch (e: Exception) {
                            thisLogger().debug("Failed to extract tar entry: ${entry.name}", e)
                        }
                        entry = tar.nextEntry
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
        repackArchive()
        target
    }

    private fun repackArchive() {
        val outFactory = outputStreamFactory ?: { path -> Files.newOutputStream(path) }
        outFactory(archivePath).use { raw ->
            TarArchiveOutputStream(raw).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                forEachArchiveEntry(tempDir) { path, relativeName ->
                    if (Files.isSymbolicLink(path)) {
                        val target = Files.readSymbolicLink(path)
                        val entry = TarArchiveEntry(relativeName, TarArchiveEntry.LF_SYMLINK)
                        entry.linkName = target.toString().replace('\\', '/')
                        tar.putArchiveEntry(entry)
                        tar.closeArchiveEntry()
                    } else {
                        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                        val entry = TarArchiveEntry(path, relativeName)
                        entry.size = if (attrs.isDirectory) 0 else attrs.size()
                        entry.modTime = Date(attrs.lastModifiedTime().toMillis())
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
