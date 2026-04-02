package io.github.jhspetersson.turtlecommander.vfs
import io.github.jhspetersson.turtlecommander.model.FileEntry

import java.io.Closeable
import java.nio.file.InvalidPathException
import java.nio.file.Path

interface VirtualFileSystem : Closeable {
    val archivePath: Path
    val root: Path
    val isReadOnly: Boolean get() = false
    suspend fun listFiles(directory: Path): List<FileEntry>
    fun isRoot(path: Path): Boolean
    fun getPath(relativePath: String): Path
    fun flush()
    suspend fun renameFile(source: Path, newName: String): Path
}

interface VirtualFileSystemProvider {
    fun supports(path: Path): Boolean
    fun supportsExtension(ext: String): Boolean
    fun create(archivePath: Path): VirtualFileSystem
}

private val ILLEGAL_FILENAME_CHARS = charArrayOf('<', '>', ':', '"', '|', '?', '*')

/**
 * Resolves an archive entry name against a base directory, sanitizing characters
 * that are illegal in file names on the host OS (e.g. ?, *, < on Windows).
 * Returns null if the resolved path escapes the base directory (zip-slip).
 */
internal fun resolveEntryPath(baseDir: Path, entryName: String): Path? {
    val cleanName = entryName.removeSuffix("/")
    return try {
        val resolved = baseDir.resolve(cleanName)
        if (!resolved.normalize().startsWith(baseDir.normalize())) null else resolved
    } catch (_: InvalidPathException) {
        val sanitized = buildString(cleanName.length) {
            for (ch in cleanName) {
                append(
                    when {
                        ch == '/' || ch == '\\' -> ch
                        ch < ' ' || ch in ILLEGAL_FILENAME_CHARS -> '_'
                        else -> ch
                    }
                )
            }
        }
        val resolved = baseDir.resolve(sanitized)
        if (!resolved.normalize().startsWith(baseDir.normalize())) null else resolved
    }
}

object VirtualFileSystemRegistry {
    private val providers = mutableListOf<VirtualFileSystemProvider>()

    init {
        register(ZipFileSystemProvider())
        register(GzFileSystemProvider())
        register(Bz2FileSystemProvider())
        register(TarFileSystemProvider())
        register(SevenZipFileSystemProvider())
        register(ArFileSystemProvider())
        register(XzFileSystemProvider())
    }

    fun register(provider: VirtualFileSystemProvider) {
        providers.add(provider)
    }

    fun supports(path: Path): Boolean = providers.any { it.supports(path) }

    fun supportsByExtension(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return providers.any { it.supportsExtension(ext) }
    }

    fun create(archivePath: Path): VirtualFileSystem {
        val provider = providers.firstOrNull { it.supports(archivePath) }
            ?: throw IllegalArgumentException("No VFS provider for: $archivePath")
        return provider.create(archivePath)
    }
}
