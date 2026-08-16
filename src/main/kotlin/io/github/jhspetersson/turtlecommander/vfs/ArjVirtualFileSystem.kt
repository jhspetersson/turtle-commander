package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.archivers.arj.ArjArchiveInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class ArjFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("arj", "cba")
    }

    override fun supportsExtension(ext: String): Boolean = ext in ARCHIVE_EXTENSIONS

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem =
        ArjVirtualFileSystem(archivePath, openProgress)
}

class ArjVirtualFileSystem(
    override val archivePath: Path,
    openProgress: VfsOpenProgress? = null,
) : AbstractTempDirVirtualFileSystem("turtle-arj-", openProgress) {

    override val isReadOnly: Boolean get() = true

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        if (!sniffIsArj()) throw SilentVfsOpenException()
        try {
            commonsCompressExtract(into, progress)
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
            reset(into)
            if (System7z.findBinary() == null) {
                throw System7zUnavailableException(
                    "This ARJ archive uses compression that can't be read in-process. " +
                        System7z.INSTALL_HINT,
                )
            }
            System7z.extract(archivePath, into, progress)
        }
    }

    private fun commonsCompressExtract(into: Path, progress: VfsOpenProgress) {
        Files.newInputStream(archivePath).buffered().use { raw ->
            ArjArchiveInputStream(raw).use { arj ->
                var entry = arj.nextEntry
                while (entry != null) {
                    if (progress.isCancelled) break
                    val rawName = entry.name.replace('\\', '/')
                    progress.onEntry(0, 0, rawName)
                    if (!arj.canReadEntryData(entry)) {
                        throw IOException("Unsupported ARJ compression method in entry $rawName")
                    }
                    val target = resolveEntryPath(into, rawName)
                    if (target != null) {
                        if (entry.isDirectory) {
                            Files.createDirectories(target)
                        } else {
                            target.parent?.let { Files.createDirectories(it) }
                            Files.copy(arj, target)
                            runCatching {
                                entry.lastModifiedDate?.let {
                                    Files.setLastModifiedTime(target, FileTime.fromMillis(it.time))
                                }
                            }
                        }
                    }
                    entry = arj.nextEntry
                }
            }
        }
    }

    private fun sniffIsArj(): Boolean {
        val header = ByteArray(2)
        val read = try {
            Files.newInputStream(archivePath).use { it.readNBytes(header, 0, header.size) }
        } catch (_: Exception) {
            return false
        }
        return read == header.size && header[0] == 0x60.toByte() && header[1] == 0xEA.toByte()
    }

    private fun reset(dir: Path) {
        dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
    }
}
