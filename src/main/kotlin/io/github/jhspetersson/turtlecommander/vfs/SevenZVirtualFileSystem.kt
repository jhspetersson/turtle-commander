package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import io.github.jhspetersson.turtlecommander.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.*

class SevenZipFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("7z")
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
        val indicator = ProgressManager.getGlobalProgressIndicator()
        val dir = Files.createTempDirectory("turtle-7z-")
        try {
            SevenZFile.builder().setPath(archivePath).get().use { sevenZ ->
                var entry = sevenZ.nextEntry
                while (entry != null) {
                    if (indicator?.isCanceled == true) break
                    indicator?.text2 = entry.name
                    val entryPath = resolveEntryPath(dir, entry.name)
                    if (entryPath == null) {
                        entry = sevenZ.nextEntry
                        continue
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
                    } catch (e: Exception) {
                        thisLogger().debug("Failed to set modified time for 7z entry: ${entry.name}", e)
                    }
                    entry = sevenZ.nextEntry
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
        SevenZOutputFile(archivePath.toFile()).use { out ->
            // SevenZOutputFile duck-types as an OutputStream (write(int)/write(byte[])/
            // write(byte[],int,int)) but doesn't actually extend it, so we can't hand it
            // to Files.copy directly. A thin adapter lets us replace the hand-rolled 8KB
            // read/write loop with the JDK's tuned Files.copy → OutputStream path.
            val sink = object : OutputStream() {
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            }
            forEachArchiveEntry(tempDir) { path, relativeName ->
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
                    Files.copy(path, sink)
                }
                out.closeArchiveEntry()
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
