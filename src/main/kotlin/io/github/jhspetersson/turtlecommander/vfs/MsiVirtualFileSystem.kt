package io.github.jhspetersson.turtlecommander.vfs

import java.nio.file.Files
import java.nio.file.Path

class MsiFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("msi")
    }

    override fun supportsExtension(ext: String): Boolean = ext in ARCHIVE_EXTENSIONS

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem =
        MsiVirtualFileSystem(archivePath, openProgress)
}

class MsiVirtualFileSystem(
    override val archivePath: Path,
    openProgress: VfsOpenProgress? = null,
) : AbstractTempDirVirtualFileSystem("turtle-msi-", openProgress) {

    override val isReadOnly: Boolean get() = true

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        if (!sniffIsCompoundFile()) throw SilentVfsOpenException()
        if (System7z.findBinary() == null) {
            throw System7zUnavailableException(
                "Browsing MSI packages requires a system 7-Zip. ${System7z.INSTALL_HINT}",
            )
        }
        System7z.extract(archivePath, into, progress)
    }

    private fun sniffIsCompoundFile(): Boolean {
        val header = ByteArray(CFB_SIGNATURE.size)
        val read = try {
            Files.newInputStream(archivePath).use { it.readNBytes(header, 0, header.size) }
        } catch (_: Exception) {
            return false
        }
        return read == CFB_SIGNATURE.size && header.contentEquals(CFB_SIGNATURE)
    }

    companion object {
        private val CFB_SIGNATURE = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )
    }
}
