package io.github.jhspetersson.turtlecommander.vfs

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class DiskImageFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("vhd", "vhdx", "vmdk")
    }

    override fun supportsExtension(ext: String): Boolean = ext in ARCHIVE_EXTENSIONS

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem =
        DiskImageVirtualFileSystem(archivePath, openProgress)
}

class DiskImageVirtualFileSystem(
    override val archivePath: Path,
    openProgress: VfsOpenProgress? = null,
) : AbstractTempDirVirtualFileSystem("turtle-diskimage-", openProgress) {

    override val isReadOnly: Boolean get() = true

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        if (!sniffIsDiskImage()) throw SilentVfsOpenException()
        if (System7z.findBinary() == null) {
            throw IOException(
                "Browsing virtual disk images requires a system 7-Zip. Install 7-Zip / p7zip and " +
                    "ensure 7z, 7zz, or 7za is on PATH (or in the standard 7-Zip install " +
                    "location on Windows) to open it.",
            )
        }
        System7z.extract(archivePath, into, progress)
    }

    private fun sniffIsDiskImage(): Boolean {
        val ext = archivePath.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() ?: return false
        return when (ext) {
            "vhd" -> hasBytesAt(0, VHD_COOKIE) || hasVhdFooterCookie()
            "vhdx" -> hasBytesAt(0, VHDX_SIGNATURE)
            "vmdk" -> hasBytesAt(0, VMDK_MAGIC)
            else -> false
        }
    }

    private fun hasVhdFooterCookie(): Boolean {
        val size = try {
            Files.size(archivePath)
        } catch (_: Exception) {
            return false
        }
        return size >= 512 && hasBytesAt(size - 512, VHD_COOKIE)
    }

    private fun hasBytesAt(position: Long, expected: ByteArray): Boolean {
        return try {
            FileChannel.open(archivePath, StandardOpenOption.READ).use { channel ->
                val buffer = ByteBuffer.allocate(expected.size)
                var pos = position
                while (buffer.hasRemaining()) {
                    val read = channel.read(buffer, pos)
                    if (read <= 0) return false
                    pos += read
                }
                buffer.array().contentEquals(expected)
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val VHD_COOKIE = "conectix".toByteArray(Charsets.US_ASCII)
        private val VHDX_SIGNATURE = "vhdxfile".toByteArray(Charsets.US_ASCII)
        private val VMDK_MAGIC = byteArrayOf(0x4B, 0x44, 0x4D, 0x56)
    }
}
