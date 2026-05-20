package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import io.github.jhspetersson.turtlecommander.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

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
    /**
     * Default `supports` implementation: extract the lowercased extension from the path's
     * file name and delegate to [supportsExtension], confirming the path points to a
     * regular file. Previously every provider duplicated this logic; keeping it here
     * means extensions only need to be declared in one place per provider.
     */
    fun supports(path: Path): Boolean {
        val ext = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext.isNotEmpty() && supportsExtension(ext) && Files.isRegularFile(path)
    }
    fun supportsExtension(ext: String): Boolean
    fun create(archivePath: Path): VirtualFileSystem
}

internal fun parentEntry(path: Path) = FileEntry(
    name = "..",
    path = path,
    isDirectory = true,
    size = 0,
    lastModified = null,
    permissions = "",
    isParentLink = true,
)

internal fun readDirectoryEntries(directory: Path): List<FileEntry> {
    val dirs = mutableListOf<FileEntry>()
    val files = mutableListOf<FileEntry>()

    Files.newDirectoryStream(directory).use { stream ->
        for (entry in stream) {
            try {
                val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java)
                val fileEntry = FileEntry(
                    name = entry.fileName.toString(),
                    path = entry,
                    isDirectory = attrs.isDirectory,
                    size = if (attrs.isDirectory) 0 else attrs.size(),
                    creationTime = attrs.creationTime(),
                    lastModified = attrs.lastModifiedTime(),
                    permissions = "",
                )
                if (attrs.isDirectory) dirs.add(fileEntry) else files.add(fileEntry)
            } catch (e: Exception) {
                LOG.debug("Failed to read directory entry: ${entry.fileName}", e)
            }
        }
    }

    dirs.sortBy { it.name.lowercase() }
    files.sortBy { it.name.lowercase() }
    return dirs + files
}

private val LOG = Logger.getInstance("VirtualFileSystem")

/**
 * Walks [tempDir] and invokes [visitor] for every descendant in a deterministic order:
 * parents are visited before their children (so archive entries with implicit directory
 * entries are well-formed), and siblings are sorted by name. Deterministic traversal also
 * makes repack output byte-stable across filesystems, which matters for checksums and CI.
 */
internal inline fun forEachArchiveEntry(
    tempDir: Path,
    crossinline visitor: (path: Path, relativeName: String) -> Unit,
) {
    val collected = mutableListOf<Path>()
    Files.walkFileTree(
        tempDir,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (dir != tempDir) collected.add(dir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                collected.add(file)
                return FileVisitResult.CONTINUE
            }
        },
    )
    // Sort by the repack-relative name so iteration order is locale- and filesystem-independent.
    val sorted = collected
        .map { it to tempDir.relativize(it).toString().replace('\\', '/') }
        .sortedBy { it.second }
    for ((path, relativeName) in sorted) {
        try {
            visitor(path, relativeName)
        } catch (e: Exception) {
            LOG.warn("Failed to repack entry: $relativeName", e)
        }
    }
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

internal fun vfsRelativePath(root: Path, path: Path): String {
    return try {
        root.relativize(path).toString()
    } catch (_: IllegalArgumentException) {
        // Fallback if paths have different roots (e.g. different drives on Windows)
        path.toString()
    }
}

/**
 * Common scaffolding for VFS implementations that materialise an archive into a temp
 * directory on disk and operate on it from there. Tar / Ar / SevenZ / ZipExtract /
 * single-file (Gz/Bz2/Xz) all follow the same pattern: extract once at construction,
 * answer [listFiles] / [getPath] / [isRoot] from the temp dir, repack into [archivePath]
 * on flush, delete the temp dir on close.
 *
 * Subclasses provide the format-specific bits via [extract] (mandatory) and [repack]
 * (no-op default for read-only formats). They are responsible for calling [openTempDir]
 * **from their own `init` block** — not from this base class — because the abstract
 * [extract] method needs the subclass's primary-constructor properties (factories,
 * metadata maps, etc.) to be initialised, which in Kotlin only happens after the
 * super-class constructor finishes.
 */
abstract class AbstractTempDirVirtualFileSystem(
    private val tempDirPrefix: String,
) : VirtualFileSystem {

    protected lateinit var tempDir: Path
        private set

    /**
     * Extract the archive at [archivePath] into [into], an empty temp directory provided
     * by [openTempDir]. Throwing from here aborts initialisation; [openTempDir] handles
     * cleanup of [into] before propagating.
     */
    protected abstract fun extract(into: Path)

    /**
     * Repack [from] (the temp dir) back into [archivePath]. Default no-op suits read-only
     * formats; the base [flush] only invokes this when [isReadOnly] is `false`.
     */
    protected open fun repack(from: Path) {}

    /**
     * Hook fired after a successful [renameFile] move but before [repack]. Subclasses
     * that maintain rename-aware bookkeeping (e.g. [ArVirtualFileSystem]'s per-entry
     * mode/uid/gid map) override this to migrate state from the old name to the new.
     */
    protected open fun onRenamed(source: Path, target: Path) {}

    /**
     * Creates a new temp directory and runs [extract] into it. On any failure the
     * partial directory is wiped before the exception propagates so we don't leak
     * temp files when an archive is malformed.
     */
    protected fun openTempDir() {
        val dir = Files.createTempDirectory(tempDirPrefix)
        try {
            extract(dir)
        } catch (e: Exception) {
            dir.toFile().deleteRecursively()
            throw e
        }
        tempDir = dir
    }

    override val root: Path get() = tempDir

    override fun isRoot(path: Path): Boolean = path.normalize() == tempDir.normalize()

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
        if (isReadOnly) {
            throw UnsupportedOperationException("Cannot rename inside a read-only archive")
        }
        val parent = source.parent ?: throw IllegalArgumentException("Cannot rename a root path")
        val target = parent.resolve(newName)
        Files.move(source, target)
        onRenamed(source, target)
        repack(tempDir)
        target
    }

    override fun flush() {
        if (!isReadOnly) repack(tempDir)
        tempDir.toFile().deleteRecursively()
        openTempDir()
    }

    override fun close() {
        try {
            tempDir.toFile().deleteRecursively()
        } catch (e: Exception) {
            thisLogger().debug("Failed to clean up temp dir $tempDir: ${e.message}")
        }
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
        register(CrxFileSystemProvider())
        register(RpmFileSystemProvider())
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
