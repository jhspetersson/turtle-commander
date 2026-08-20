package io.github.jhspetersson.turtlecommander.vfs
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import java.util.concurrent.CopyOnWriteArraySet

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import io.github.jhspetersson.turtlecommander.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

interface VirtualFileSystem : Closeable {
    val archivePath: Path
    val root: Path
    val isReadOnly: Boolean get() = false
    suspend fun listFiles(directory: Path): List<FileEntry>
    fun isRoot(path: Path): Boolean
    fun getPath(relativePath: String): Path
    fun flush()
    suspend fun renameFile(source: Path, newName: String): Path

    /**
     * Whether [path] was produced by this VFS — used by [OpenVfsRegistry] to dispatch a
     * materialization call to the right instance. Default: prefix-matches against [root],
     * which is correct for every existing temp-dir-backed implementation.
     */
    fun owns(path: Path): Boolean = path.normalize().startsWith(root.normalize())

    /**
     * Lazy-VFS hook: ensure the bytes for [path] are actually present on disk. The default
     * is a no-op because most VFS implementations extract everything up-front in `extract()`
     * and the temp-dir copies are already real files. [IsoVirtualFileSystem] overrides this
     * to stream content from the source disc image only when something actually needs it,
     * so opening a 5 GB ISO doesn't pre-extract 5 GB of files into temp.
     */
    fun materialize(path: Path) {}

    /**
     * Owner / group / permission metadata stored in the archive for the entry at [path], or
     * null when this VFS carries none (the temp-dir copy's own stat is meaningless — extraction
     * gives it the host umask, not the archive's recorded mode). Archive implementations that
     * capture the original metadata (tar, zip) override this so directory listings — and the
     * colorization rules that match on owner/group/permissions — see the real values.
     */
    fun entryMetadata(path: Path): ArchiveEntryMetadata? = null
}

/**
 * Owner / group / permission strings recorded inside an archive for one entry. Fields are
 * empty when the format doesn't carry them (e.g. zip stores a Unix mode but no owner/group
 * names), so consumers treat empty as "unknown / inherit" rather than a real value.
 */
data class ArchiveEntryMetadata(
    val owner: String = "",
    val group: String = "",
    val permissions: String = "",
)

/**
 * Global registry of currently-open VFS instances, used by consumer sites that hold a
 * `java.nio.file.Path` but don't know which VFS produced it (content search, hash
 * computation, generic copy). Each [AbstractTempDirVirtualFileSystem] auto-registers itself
 * after its temp dir is ready and unregisters on [close]; callers go through
 * [materializeIfNeeded] which fans out to whichever VFS owns the path.
 *
 * Lookup is `O(n)` over open VFSs — `n` is the number of archives the user has nested into,
 * typically 1–3, so this is trivial. The structure is a `CopyOnWriteArraySet` so reads
 * don't take a lock; register/unregister are rare relative to materialize calls.
 */
object OpenVfsRegistry {
    private val instances: MutableSet<VirtualFileSystem> = CopyOnWriteArraySet()

    fun register(vfs: VirtualFileSystem) { instances.add(vfs) }
    fun unregister(vfs: VirtualFileSystem) { instances.remove(vfs) }

    /**
     * Forward [path] to whichever registered VFS claims it via [VirtualFileSystem.owns].
     * Safe to call with any path, including ones outside every VFS — the lookup returns
     * silently in that case, so callers don't need to guard with `if (isInsideVfs)`.
     */
    fun materializeIfNeeded(path: Path) {
        for (vfs in instances) {
            if (vfs.owns(path)) {
                vfs.materialize(path)
                return
            }
        }
    }

    fun hasLiveContentUnder(dir: Path): Boolean {
        val normalized = dir.normalize()
        return instances.any {
            it.root.normalize().startsWith(normalized) || it.archivePath.normalize().startsWith(normalized)
        }
    }

    /**
     * Like [materializeIfNeeded] but recursive: ensures every file under [path] holds real
     * bytes. Needed before a same-filesystem `Files.move` relocates a lazy-VFS subtree out
     * of its temp dir wholesale — after the move the stubs are no longer under any VFS root,
     * so per-file interception can never run for them again.
     */
    fun materializeTreeIfNeeded(path: Path) {
        val owner = instances.firstOrNull { it.owns(path) } ?: return
        if (Files.isDirectory(path)) {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    owner.materialize(file)
                    return FileVisitResult.CONTINUE
                }
            })
        } else {
            owner.materialize(path)
        }
    }
}

/**
 * Progress/cancellation hook for the extract-at-open phase of temp-dir VFS implementations.
 * Passed explicitly by coroutine-based progress call sites; replaces the historical reliance
 * on [com.intellij.openapi.progress.ProgressManager.getGlobalProgressIndicator], which is
 * thread-bound and absent under the coroutine progress API
 * ([com.intellij.platform.ide.progress.withBackgroundProgress]).
 */
interface VfsOpenProgress {
    /** Reports entry [index] of [total] ([total] <= 0 when unknown) currently being extracted. */
    fun onEntry(index: Int, total: Int, name: String)

    /** Polled between entries; extraction stops early (leaving a partial temp dir) when true. */
    val isCancelled: Boolean

    companion object {
        /** Adapter reporting entries through [indicator]'s fraction / text2 / isCanceled. */
        fun fromIndicator(indicator: ProgressIndicator): VfsOpenProgress {
            return object : VfsOpenProgress {
                override fun onEntry(index: Int, total: Int, name: String) {
                    if (total > 0) {
                        if (indicator.isIndeterminate) indicator.isIndeterminate = false
                        indicator.fraction = index.toDouble() / total
                    }
                    indicator.text2 = name
                }

                override val isCancelled: Boolean get() = indicator.isCanceled
            }
        }

        /**
         * Adapter for the legacy thread-bound indicator, used as the fallback when no explicit
         * hook is supplied (VFS creation from code still running under a `Task`, or tests).
         * Returns a no-op when no indicator is bound to the current thread.
         */
        fun fromCurrentThread(): VfsOpenProgress {
            val indicator = ProgressManager.getGlobalProgressIndicator() ?: return Noop
            return fromIndicator(indicator)
        }

        private object Noop : VfsOpenProgress {
            override fun onEntry(index: Int, total: Int, name: String) {}
            override val isCancelled: Boolean get() = false
        }
    }
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

    /**
     * Create with an extract-progress hook. The default ignores it — only implementations
     * that do significant work at open time (the ZIP extract fallback and ISO) override this.
     */
    fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem = create(archivePath)
}

/**
 * Thrown by [VirtualFileSystemProvider.create] when a file's extension is recognised but
 * its contents match no supported archive format. Callers (e.g. the tool window's
 * `enterVfs`) treat this as "leave the file as it is": no VFS is opened and no error is
 * shown. Contrast with the generic exceptions thrown for genuinely corrupt archives, which
 * *do* surface an error to the user.
 */
class SilentVfsOpenException : Exception()

internal fun parentEntry(path: Path) = FileEntry(
    name = "..",
    path = path,
    isDirectory = true,
    size = 0,
    lastModified = null,
    permissions = "",
    isParentLink = true,
)

internal fun readDirectoryEntries(
    directory: Path,
    metadataFor: (Path) -> ArchiveEntryMetadata? = { null },
): List<FileEntry> {
    val dirs = mutableListOf<FileEntry>()
    val files = mutableListOf<FileEntry>()

    Files.newDirectoryStream(directory).use { stream ->
        for (entry in stream) {
            try {
                val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java)
                val meta = metadataFor(entry)
                val fileEntry = FileEntry(
                    name = entry.fileName.toString(),
                    path = entry,
                    isDirectory = attrs.isDirectory,
                    size = if (attrs.isDirectory) 0 else attrs.size(),
                    creationTime = attrs.creationTime(),
                    lastModified = attrs.lastModifiedTime(),
                    owner = meta?.owner ?: "",
                    group = meta?.group ?: "",
                    permissions = meta?.permissions ?: "",
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

/**
 * True when a symbolic link located at [linkPath] and pointing at [target] would resolve outside
 * [baseDir] — i.e. following it, or writing through it, escapes the extraction root.
 *
 * [resolveEntryPath] only rejects textual `..` in an entry *name*; it can't see link *targets*.
 * Without this check a hostile archive can ship `evil -> /home/user` (or `../../..`) followed by a
 * child entry `evil/.bashrc`: the child's name passes the containment check, but the real write
 * follows the symlink out of the temp dir. Absolute targets, and relative targets that climb above
 * [baseDir], are treated as escaping. The target is resolved against the link's own directory, the
 * way the OS dereferences it, so a legitimate in-tree link such as `bin/sh -> ../usr/bin/sh` is
 * still allowed.
 */
internal fun symlinkTargetEscapes(baseDir: Path, linkPath: Path, target: String): Boolean {
    return try {
        val base = baseDir.normalize()
        val parent = (linkPath.parent ?: base).normalize()
        !parent.resolve(Path.of(target)).normalize().startsWith(base)
    } catch (_: Exception) {
        // Unparseable target (illegal chars, cross-scheme, …) — refuse it, the safe default.
        true
    }
}

/**
 * Rebuild an archive without risking the original: [write] emits the new archive into a sibling
 * temp file, which is atomically moved over [archivePath] only after it finishes successfully. A
 * crash, disk-full, or unreadable entry mid-repack then leaves the original archive intact instead
 * of truncating it in place into an unrecoverable half-written file (the temp dir it was extracted
 * into is deleted on close, so the on-disk archive is often the only surviving copy of the data).
 *
 * The temp file is a sibling — same directory, hence same filesystem — so the move can be atomic.
 */
internal fun repackAtomically(archivePath: Path, write: (Path) -> Unit) {
    val dir = archivePath.toAbsolutePath().normalize().parent
        ?: throw IOException("Cannot repack an archive with no parent directory: $archivePath")
    val tmp = Files.createTempFile(dir, "tc-repack-", ".tmp")
    try {
        write(tmp)
        try {
            Files.move(tmp, archivePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // Rare (e.g. some network filesystems): a non-atomic replace is still far safer than
            // truncating the original before the new archive bytes exist.
            Files.move(tmp, archivePath, StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: Throwable) {
        runCatching { Files.deleteIfExists(tmp) }
        throw e
    }
}

/**
 * Create a placeholder file of [size] bytes at [path] for lazily-materialising VFS
 * implementations ([IsoVirtualFileSystem], [ZipExtractVirtualFileSystem]). Opens with
 * [StandardOpenOption.SPARSE] so the OS can keep the allocation sparse where supported
 * (NTFS on Windows when the SPARSE flag is honoured, ext4 / xfs on Linux, APFS on macOS);
 * falls back to a regular allocation otherwise. Either way [Files.size] returns the right
 * value so directory listings show the real size before any bytes have been streamed.
 */
internal fun createSparseStub(path: Path, size: Long) {
    Files.createFile(path)
    if (size <= 0) return
    FileChannel.open(
        path,
        StandardOpenOption.WRITE,
        StandardOpenOption.SPARSE,
    ).use { ch ->
        // Writing a single byte at offset (size - 1) extends the file to `size`; on
        // SPARSE-capable filesystems the gap remains a hole. The byte itself will be
        // overwritten when materialize() streams real content.
        ch.position(size - 1)
        ch.write(ByteBuffer.wrap(byteArrayOf(0)))
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
    openProgress: VfsOpenProgress? = null,
) : VirtualFileSystem {

    protected lateinit var tempDir: Path
        private set

    // One-shot open-progress hook, cleared on first take so a flush()-driven re-extract
    // can't call into a progress reporter whose scope has already ended.
    private var openProgress: VfsOpenProgress? = openProgress

    /**
     * The progress hook for the current [extract] run: the constructor-supplied hook on the
     * first call, the legacy thread-bound indicator (or a no-op) afterwards / when none was
     * given. Call once at the top of [extract].
     */
    protected fun takeOpenProgress(): VfsOpenProgress {
        val progress = openProgress ?: VfsOpenProgress.fromCurrentThread()
        openProgress = null
        return progress
    }

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
        // Captured before extract so an archive rewritten mid-extraction can't be mistaken
        // for the state we extracted — the next flush would then re-extract, which is the
        // safe direction.
        val signature = archiveSignature()
        val dir = Files.createTempDirectory(tempDirPrefix)
        try {
            extract(dir)
        } catch (e: Exception) {
            dir.toFile().deleteRecursively()
            throw e
        }
        tempDir = dir
        cleanFingerprint = computeFingerprint()
        cleanArchiveSignature = signature
        // Register only after the temp dir is fully populated (or stub-populated) so
        // materializeIfNeeded never dispatches to a half-initialised VFS. The matching
        // unregister lives in close(), not in flush(), because flush() rebuilds the temp
        // dir while the VFS itself stays alive.
        OpenVfsRegistry.register(this)
    }

    /**
     * Fingerprint of the temp-dir contents (relative name + size + mtime of every entry,
     * accumulated over the deterministic sorted walk of [forEachArchiveEntry]), captured
     * right after [extract]. [flush] compares against it so a repack only happens when
     * something actually changed — copying a file *out* of the archive or a plain refresh
     * must not rewrite (and re-compress) an archive the user never modified.
     *
     * Granularity caveat: an in-place edit that preserves both size and mtime would go
     * unnoticed, but every real write path (editor write-back, copy-in, delete) produces a
     * fresh mtime or a different size. Lazy implementations keep materialisation invisible
     * here by giving the materialised file the stub's exact mtime.
     */
    private var cleanFingerprint: Long = 0

    private fun computeFingerprint(): Long {
        var h = 1125899906842597L
        forEachArchiveEntry(tempDir) { path, relativeName ->
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
            h = h * 31 + relativeName.hashCode()
            if (attrs.isDirectory) {
                // Directories contribute by name only: their mtime changes whenever a
                // child is created — including the sibling temp file a lazy materialize
                // writes — and carries no archive-relevant information that the file set
                // and the files' own size/mtime don't already capture.
                h = h * 31 - 1L
            } else {
                h = h * 31 + attrs.size()
                h = h * 31 + attrs.lastModifiedTime().toMillis()
            }
        }
        return h
    }

    /**
     * Size + mtime of [archivePath] as of the last [extract], the same heuristic the
     * directory-listing cache uses. [flush] re-extracts only when this changed (an
     * external program rewrote the archive) or the temp dir is dirty — otherwise the
     * existing temp dir, with any lazily-materialised content, is kept as is.
     */
    private var cleanArchiveSignature: Pair<Long, FileTime>? = null

    private fun archiveSignature(): Pair<Long, FileTime>? = runCatching {
        Files.size(archivePath) to Files.getLastModifiedTime(archivePath)
    }.getOrNull()

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
        result.addAll(readDirectoryEntries(directory) { entryMetadata(it) })
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
        // The archive now matches the temp dir again; without this a following flush()
        // would see the renamed entry as a change and repack (and re-extract) a second time.
        cleanFingerprint = computeFingerprint()
        cleanArchiveSignature = archiveSignature()
        target
    }

    override fun flush() {
        // Repack only when the temp dir actually changed: read-only operations (copying a
        // file out of the archive, a plain refresh) must not rewrite the archive.
        val dirty = !isReadOnly && computeFingerprint() != cleanFingerprint
        if (dirty) {
            repack(tempDir)
        } else {
            val signature = archiveSignature()
            if (signature != null && signature == cleanArchiveSignature) {
                // Nothing to pick up in either direction: the temp dir is untouched and
                // the archive on disk is still the one we extracted from. Keep the temp
                // dir (and any lazily-materialised content) instead of re-extracting.
                return
            }
        }
        tempDir.toFile().deleteRecursively()
        openTempDir()
    }

    override fun close() {
        OpenVfsRegistry.unregister(this)
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
        register(RarFileSystemProvider())
        register(CabFileSystemProvider())
        register(ArFileSystemProvider())
        register(ArjFileSystemProvider())
        register(CpioFileSystemProvider())
        register(XzFileSystemProvider())
        register(ZstFileSystemProvider())
        register(CrxFileSystemProvider())
        register(JmodFileSystemProvider())
        register(MsiFileSystemProvider())
        register(SquashfsFileSystemProvider())
        register(DiskImageFileSystemProvider())
        register(RpmFileSystemProvider())
        register(PakFileSystemProvider())
        register(IsoFileSystemProvider())
    }

    fun register(provider: VirtualFileSystemProvider) {
        providers.add(provider)
    }

    fun supports(path: Path): Boolean = providers.any { it.supports(path) }

    fun supportsByExtension(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return providers.any { it.supportsExtension(ext) }
    }

    fun create(archivePath: Path, openProgress: VfsOpenProgress? = null): VirtualFileSystem {
        val provider = providers.firstOrNull { it.supports(archivePath) }
            ?: throw IllegalArgumentException("No VFS provider for: $archivePath")
        return provider.create(archivePath, openProgress)
    }
}
