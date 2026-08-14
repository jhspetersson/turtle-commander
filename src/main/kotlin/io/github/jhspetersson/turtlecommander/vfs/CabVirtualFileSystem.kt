package io.github.jhspetersson.turtlecommander.vfs

import de.morihofi.cab4j.extract.CabExtractor
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.ZoneId

class CabFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("cab")
    }

    override fun supportsExtension(ext: String): Boolean = ext in ARCHIVE_EXTENSIONS

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem =
        CabVirtualFileSystem(archivePath, openProgress)
}

class CabVirtualFileSystem(
    override val archivePath: Path,
    openProgress: VfsOpenProgress? = null,
) : AbstractTempDirVirtualFileSystem("turtle-cab-", openProgress) {

    override val isReadOnly: Boolean get() = true

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        if (!sniffIsCab()) throw SilentVfsOpenException()

        try {
            cab4jExtract(into, progress)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            reset(into)
            sevenZipFallback(into, progress, e)
        }
    }

    private fun cab4jExtract(into: Path, progress: VfsOpenProgress) {
        val entries = CabExtractor.extractWithAttributes(ByteBuffer.wrap(Files.readAllBytes(archivePath)))
        val total = entries.size
        var index = 0
        for ((rawName, fileData) in entries) {
            if (progress.isCancelled) break
            progress.onEntry(++index, total, rawName)
            val target = resolveEntryPath(into, rawName.replace('\\', '/')) ?: continue
            target.parent?.let { Files.createDirectories(it) }
            val data = fileData.data.duplicate()
            data.rewind()
            FileChannel.open(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { out ->
                while (data.hasRemaining()) out.write(data)
            }
            runCatching {
                val instant = fileData.lastModified.atZone(ZoneId.systemDefault()).toInstant()
                Files.setLastModifiedTime(target, FileTime.from(instant))
            }
        }
    }

    private fun sevenZipFallback(into: Path, progress: VfsOpenProgress, cause: Exception) {
        if (System7z.findBinary() == null) {
            throw System7zUnavailableException(
                "This CAB archive couldn't be read in-process. ${System7z.INSTALL_HINT}",
            ).initCause(cause)
        }
        System7z.extract(archivePath, into, progress)
    }

    private fun sniffIsCab(): Boolean {
        val header = ByteArray(CAB_SIGNATURE.size)
        val read = try {
            Files.newInputStream(archivePath).use { it.readNBytes(header, 0, header.size) }
        } catch (_: Exception) {
            return false
        }
        return read == CAB_SIGNATURE.size && header.contentEquals(CAB_SIGNATURE)
    }

    private fun reset(dir: Path) {
        dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
    }

    companion object {
        private val CAB_SIGNATURE = byteArrayOf(0x4D, 0x53, 0x43, 0x46)
    }
}
