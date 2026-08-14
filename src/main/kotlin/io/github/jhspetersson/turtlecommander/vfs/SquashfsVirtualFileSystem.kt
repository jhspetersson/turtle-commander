package io.github.jhspetersson.turtlecommander.vfs

import java.nio.file.Files
import java.nio.file.Path

class SquashfsFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("squashfs", "sqsh", "sfs", "snap")
    }

    override fun supportsExtension(ext: String): Boolean = ext in ARCHIVE_EXTENSIONS

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem =
        SquashfsVirtualFileSystem(archivePath, openProgress)
}

class SquashfsVirtualFileSystem(
    override val archivePath: Path,
    openProgress: VfsOpenProgress? = null,
) : AbstractTempDirVirtualFileSystem("turtle-squashfs-", openProgress) {

    override val isReadOnly: Boolean get() = true

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        if (!sniffIsSquashfs()) throw SilentVfsOpenException()
        if (System7z.findBinary() == null) {
            throw System7zUnavailableException(
                "Browsing SquashFS images requires a system 7-Zip. ${System7z.INSTALL_HINT}",
            )
        }
        System7z.extract(archivePath, into, progress)
    }

    private fun sniffIsSquashfs(): Boolean {
        val header = ByteArray(4)
        val read = try {
            Files.newInputStream(archivePath).use { it.readNBytes(header, 0, header.size) }
        } catch (_: Exception) {
            return false
        }
        return read == 4 && (header.contentEquals(MAGIC_LE) || header.contentEquals(MAGIC_BE))
    }

    companion object {
        private val MAGIC_LE = byteArrayOf(0x68, 0x73, 0x71, 0x73)
        private val MAGIC_BE = byteArrayOf(0x73, 0x71, 0x73, 0x68)
    }
}
