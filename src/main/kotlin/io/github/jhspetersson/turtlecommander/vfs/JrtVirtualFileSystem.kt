package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import io.github.jhspetersson.turtlecommander.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

class JrtFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        const val MODULES_FILE_NAME = "modules"
        private const val LIB_DIR_NAME = "lib"
        private const val JRT_FS_JAR_NAME = "jrt-fs.jar"
        private val JIMAGE_MAGIC = byteArrayOf(0xDA.toByte(), 0xDA.toByte(), 0xFE.toByte(), 0xCA.toByte())

        fun modulesImageFor(javaHome: Path): Path = javaHome.resolve(LIB_DIR_NAME).resolve(MODULES_FILE_NAME)

        fun jrtFsJarFor(javaHome: Path): Path = javaHome.resolve(LIB_DIR_NAME).resolve(JRT_FS_JAR_NAME)

        fun isModulesImage(path: Path): Boolean {
            if (path.fileName?.toString() != MODULES_FILE_NAME) return false
            if (path.parent?.fileName?.toString() != LIB_DIR_NAME) return false
            if (!Files.isRegularFile(path)) return false
            return try {
                Files.newInputStream(path).use { it.readNBytes(JIMAGE_MAGIC.size) }.contentEquals(JIMAGE_MAGIC)
            } catch (_: IOException) {
                false
            }
        }
    }

    override fun supports(path: Path): Boolean = isModulesImage(path)

    override fun supportsExtension(ext: String): Boolean = false

    override fun create(archivePath: Path): VirtualFileSystem = JrtVirtualFileSystem(archivePath)
}

class JrtVirtualFileSystem(override val archivePath: Path) : VirtualFileSystem {
    private val javaHome: Path = archivePath.parent?.parent
        ?: throw IOException("$archivePath is not inside a JDK installation")
    private val fileSystem: FileSystem = openJrtFileSystem()

    override val isReadOnly: Boolean get() = true

    override val root: Path get() = fileSystem.getPath("/modules")

    override fun isRoot(path: Path): Boolean = path.toString().trimEnd('/') == "/modules"

    override fun getPath(relativePath: String): Path = root.resolve(relativePath)

    override fun owns(path: Path): Boolean = path.fileSystem === fileSystem

    override suspend fun listFiles(directory: Path): List<FileEntry> = withContext(Dispatchers.IO) {
        val parent = if (isRoot(directory)) archivePath.parent ?: archivePath else directory.parent ?: root
        listOf(parentEntry(parent)) + readDirectoryEntries(directory)
    }

    override fun flush() {}

    override suspend fun renameFile(source: Path, newName: String): Path =
        throw UnsupportedOperationException("The JDK runtime image is read-only")

    override fun close() {
        try {
            fileSystem.close()
        } catch (e: Exception) {
            thisLogger().debug("Failed to close jrt filesystem for $archivePath: ${e.message}")
        }
    }

    private fun openJrtFileSystem(): FileSystem {
        val uri = URI.create("jrt:/")
        if (Files.isRegularFile(JrtFileSystemProvider.jrtFsJarFor(javaHome))) {
            return FileSystems.newFileSystem(uri, mapOf("java.home" to javaHome.toString()))
        }
        val runningHome = Path.of(System.getProperty("java.home"))
        if (Files.isSameFile(runningHome, javaHome)) {
            return FileSystems.newFileSystem(uri, emptyMap<String, Any>())
        }
        throw IOException("$javaHome has no lib/jrt-fs.jar; cannot read its runtime image")
    }
}
