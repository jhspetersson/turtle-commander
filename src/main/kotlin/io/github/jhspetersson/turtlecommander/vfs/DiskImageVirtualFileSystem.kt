package io.github.jhspetersson.turtlecommander.vfs

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
        val binary = System7z.findBinary()
            ?: throw System7zUnavailableException(
                "Browsing virtual disk images requires a system 7-Zip. ${System7z.INSTALL_HINT}",
            )
        if (extension() == "vhdx") {
            val version = System7z.binaryVersion(binary)
            if (version != null && version < VHDX_MIN_7Z_VERSION) {
                throw System7zUnavailableException(
                    "Browsing VHDX images requires 7-Zip ${System7z.formatVersion(VHDX_MIN_7Z_VERSION)} " +
                        "or newer; the installed 7-Zip is ${System7z.formatVersion(version)}. " +
                        "Download: ${System7z.DOWNLOAD_URL}",
                )
            }
        }
        System7z.extract(archivePath, into, progress)
    }

    private fun extension(): String? =
        archivePath.fileName?.toString()?.substringAfterLast('.', "")?.lowercase()

    private fun sniffIsDiskImage(): Boolean {
        return when (extension()) {
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
        private const val VHDX_MIN_7Z_VERSION = 2102
    }
}
