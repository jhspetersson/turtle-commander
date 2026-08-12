package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

class GzFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("gz", "tgz")
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem {
        val name = archivePath.fileName?.toString()?.lowercase() ?: ""
        return if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            TarVirtualFileSystem(
                archivePath,
                inputStreamFactory = { GzipCompressorInputStream(Files.newInputStream(it)) },
                outputStreamFactory = { GzipCompressorOutputStream(Files.newOutputStream(it)) },
                openProgress = openProgress,
            )
        } else {
            CompressedSingleFileVirtualFileSystem(archivePath, ".gz") { GzipCompressorInputStream(it) }
        }
    }
}

class Bz2FileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("bz2", "tbz2", "tbz")
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem {
        val name = archivePath.fileName?.toString()?.lowercase() ?: ""
        return if (name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz")) {
            TarVirtualFileSystem(
                archivePath,
                inputStreamFactory = { BZip2CompressorInputStream(Files.newInputStream(it)) },
                outputStreamFactory = { BZip2CompressorOutputStream(Files.newOutputStream(it)) },
                openProgress = openProgress,
            )
        } else {
            CompressedSingleFileVirtualFileSystem(archivePath, ".bz2") { BZip2CompressorInputStream(it) }
        }
    }
}

class XzFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("xz", "txz")
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem {
        val name = archivePath.fileName?.toString()?.lowercase() ?: ""
        return if (name.endsWith(".tar.xz") || name.endsWith(".txz")) {
            TarVirtualFileSystem(
                archivePath,
                inputStreamFactory = { XZCompressorInputStream(Files.newInputStream(it)) },
                outputStreamFactory = { XZCompressorOutputStream(Files.newOutputStream(it)) },
                openProgress = openProgress,
            )
        } else {
            CompressedSingleFileVirtualFileSystem(archivePath, ".xz") { XZCompressorInputStream(it) }
        }
    }
}

class ZstFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("zst", "tzst")
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem {
        val name = archivePath.fileName?.toString()?.lowercase() ?: ""
        return if (name.endsWith(".tar.zst") || name.endsWith(".tzst")) {
            TarVirtualFileSystem(
                archivePath,
                inputStreamFactory = { ZstdCompressorInputStream(Files.newInputStream(it)) },
                outputStreamFactory = { ZstdCompressorOutputStream(Files.newOutputStream(it)) },
                openProgress = openProgress,
            )
        } else {
            CompressedSingleFileVirtualFileSystem(archivePath, ".zst") { ZstdCompressorInputStream(it) }
        }
    }
}

class CompressedSingleFileVirtualFileSystem(
    override val archivePath: Path,
    private val compressionSuffix: String,
    private val decompressorFactory: (InputStream) -> InputStream,
) : AbstractTempDirVirtualFileSystem("turtle-decompress-") {

    override val isReadOnly: Boolean get() = true

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val originalName = archivePath.fileName?.toString() ?: "file"
        val innerName = if (originalName.endsWith(compressionSuffix, ignoreCase = true))
            originalName.dropLast(compressionSuffix.length)
        else
            originalName
        val destPath = into.resolve(innerName)
        decompressorFactory(Files.newInputStream(archivePath)).use { stream ->
            Files.copy(stream, destPath)
        }
    }
}
