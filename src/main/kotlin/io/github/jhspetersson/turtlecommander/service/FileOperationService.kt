package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import io.github.jhspetersson.turtlecommander.model.FileEntry
import com.intellij.openapi.util.SystemInfo
import io.github.jhspetersson.turtlecommander.settings.ColorRuleManager
import io.github.jhspetersson.turtlecommander.settings.RuleMatcher
import io.github.jhspetersson.turtlecommander.settings.TextProperty
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.util.readFileGroup
import io.github.jhspetersson.turtlecommander.util.readFileOwner
import io.github.jhspetersson.turtlecommander.util.readFilePermissions
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry
import io.github.jhspetersson.turtlecommander.vfs.parentEntry
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.Files.walkFileTree
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

@Service(Service.Level.PROJECT)
class FileOperationService(
    private val cs: CoroutineScope,
) {
    private val isWindows = SystemInfo.isWindows
    private val isMac = SystemInfo.isMac

    /** Test-only clock override. Defaults to wall clock. */
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * Test-only hook for the "Delete to Recycle Bin" flow. Defaults to the real AWT Desktop
     * `moveToTrash`. Tests replace this with an in-memory fake so they don't depend on the
     * presence of a real trash on the CI runner and don't actually litter the system trash.
     */
    internal var moveToTrash: (Path) -> Boolean = { path ->
        try {
            java.awt.Desktop.getDesktop().moveToTrash(path.toFile())
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    /**
     * Test-only hook for the platform capability check. Mirrors [moveToTrash] so tests can
     * exercise the recycle-bin code path regardless of whether the runner has a graphical
     * desktop available.
     */
    internal var isMoveToTrashSupported: () -> Boolean = {
        try {
            java.awt.Desktop.isDesktopSupported() &&
                java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)
        } catch (_: Throwable) {
            false
        }
    }

    private data class CachedListing(
        val dirs: List<FileEntry>,
        val files: List<FileEntry>,
        val timestamp: Long,
        // Directory mtime captured when the listing was built. Used to auto-invalidate the
        // cache on external modifications (other process, shell, IDE). Nullable because
        // some filesystems or paths (e.g. unreadable system dirs) may refuse the stat.
        val dirMtime: FileTime?,
    )

    private val listingCache = object : LinkedHashMap<Path, CachedListing>(CACHE_MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, CachedListing>): Boolean =
            size > CACHE_MAX_SIZE
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = cs.launch(block = block)

    suspend fun listFiles(directory: Path, forceRefresh: Boolean = false): List<FileEntry> = withContext(Dispatchers.IO) {
        val result = mutableListOf<FileEntry>()

        val parent = directory.parent
        if (parent != null) {
            result.add(parentEntry(parent))
        }

        val cached = if (!forceRefresh) getCachedListing(directory) else null
        if (cached != null) {
            appendSortedEntries(result, cached.dirs, cached.files)
            return@withContext result
        }

        val effectiveDirectory = try {
            resolveReparsePoint(directory)
        } catch (_: Exception) {
            directory
        }

        val dirs = mutableListOf<FileEntry>()
        val files = mutableListOf<FileEntry>()

        // Resolve which expensive per-entry lookups are actually displayed. On Windows the
        // User column is hidden by default, so `Files.getOwner(...)` (a slow Win32 security
        // descriptor + SID-to-name lookup) shouldn't run for every file in a large directory.
        val settings = TurtleCommanderSettings.getInstance()
        val visibleColumnIds = settings
            .getEffectiveColumns()
            .filter { it.visible }
            .map { it.id }
            .toSet()
        // Owner / group / permissions are also read when an active color rule matches on them,
        // otherwise such a rule could never fire while its column is hidden. (Created / modified
        // timestamps come from the same attribute read as size, so they need no opt-in.)
        val state = settings.state
        val activeRules = if (state.enableFileNameHighlighting) {
            ColorRuleManager.getActiveRulesForEvaluation(state)
        } else {
            emptyList()
        }
        fun rulesMatchOn(prop: TextProperty): Boolean =
            activeRules.any { rule -> rule.matchers.any { it is RuleMatcher.Text && it.field == prop } }
        val needOwner = "User" in visibleColumnIds || rulesMatchOn(TextProperty.OWNER)
        val needGroup = !isWindows && ("Group" in visibleColumnIds || rulesMatchOn(TextProperty.GROUP))
        val needPermissions = "Permissions" in visibleColumnIds || rulesMatchOn(TextProperty.PERMISSIONS)

        // An IOException from Files.newDirectoryStream (e.g. access denied on
        // C:\$Recycle.Bin\S-1-5-18) is allowed to propagate so the UI can show it to the user.
        // Per-entry attribute read failures are still swallowed as debug logs since those are
        // usually transient (a file disappearing mid-iteration) and shouldn't abort the whole
        // listing.
        Files.newDirectoryStream(effectiveDirectory).use { stream ->
            for (entry in stream) {
                try {
                    // On Windows, read DosFileAttributes directly (extends BasicFileAttributes)
                    // so the permission flags come from the same syscall as size/time.
                    val attrs = if (isWindows && needPermissions) {
                        Files.readAttributes(entry, DosFileAttributes::class.java)
                    } else {
                        Files.readAttributes(entry, BasicFileAttributes::class.java)
                    }
                    val owner = if (needOwner) readOwner(entry) else ""
                    val group = if (needGroup) readGroup(entry) else ""
                    val permissions = when {
                        !needPermissions -> ""
                        isWindows && attrs is DosFileAttributes -> dosAttrsToString(attrs)
                        else -> readPermissions(entry)
                    }
                    val fileEntry = FileEntry(
                        name = entry.name,
                        path = entry,
                        isDirectory = attrs.isDirectory,
                        size = attrs.size(),
                        creationTime = attrs.creationTime(),
                        lastModified = attrs.lastModifiedTime(),
                        owner = owner,
                        group = group,
                        permissions = permissions,
                    )
                    if (attrs.isDirectory) dirs.add(fileEntry) else files.add(fileEntry)
                } catch (e: IOException) {
                    thisLogger().debug("Cannot read attributes for $entry: ${e.message}")
                }
            }
        }

        val mtime = try { Files.getLastModifiedTime(effectiveDirectory) } catch (_: Exception) { null }
        putCachedListing(directory, dirs.toList(), files.toList(), mtime)
        appendSortedEntries(result, dirs, files)

        result
    }

    private fun appendSortedEntries(
        result: MutableList<FileEntry>,
        dirs: List<FileEntry>,
        files: List<FileEntry>,
    ) {
        val sortWithDirs = TurtleCommanderSettings.getInstance().state.sortWithDirectories
        if (sortWithDirs) {
            val all = (dirs + files).sortedBy { it.name.lowercase() }
            result.addAll(all)
        } else {
            val sortedDirs = dirs.sortedBy { it.name.lowercase() }
            val sortedFiles = files.sortedBy { it.name.lowercase() }
            result.addAll(sortedDirs)
            result.addAll(sortedFiles)
        }
    }

    private fun getCachedListing(directory: Path): CachedListing? {
        val entry = synchronized(listingCache) {
            val e = listingCache[directory] ?: return null
            if (clock() - e.timestamp > CACHE_TTL_MS) {
                listingCache.remove(directory)
                return null
            }
            e
        }
        // mtime check is done outside the lock to avoid blocking other cache users on a
        // filesystem stat. A racing `put` can't corrupt the result — worst case we evict a
        // freshly-repopulated entry, which is a self-healing no-op on the next listFiles.
        if (entry.dirMtime != null) {
            val current = try { Files.getLastModifiedTime(directory) } catch (_: Exception) { null }
            if (current == null || current != entry.dirMtime) {
                synchronized(listingCache) { listingCache.remove(directory) }
                return null
            }
        }
        return entry
    }

    private fun putCachedListing(directory: Path, dirs: List<FileEntry>, files: List<FileEntry>, dirMtime: FileTime?) {
        synchronized(listingCache) {
            listingCache[directory] = CachedListing(dirs, files, clock(), dirMtime)
        }
    }

    fun invalidateListingCache(directory: Path) {
        synchronized(listingCache) {
            listingCache.remove(directory)
        }
    }

    fun clearListingCache() {
        synchronized(listingCache) {
            listingCache.clear()
        }
    }

    internal fun listingCacheSize(): Int = synchronized(listingCache) { listingCache.size }

    internal fun isListingCached(directory: Path): Boolean = synchronized(listingCache) {
        val entry = listingCache[directory] ?: return@synchronized false
        clock() - entry.timestamp <= CACHE_TTL_MS
    }

    /**
     * Like [isListingCached] but also verifies the directory's mtime still matches. Returns
     * false if the cache would be re-read on the next [listFiles] call — used by the UI to
     * skip a costly re-render on tab activation when nothing has changed.
     */
    fun isListingCacheFresh(directory: Path): Boolean {
        val entry = synchronized(listingCache) {
            val e = listingCache[directory] ?: return false
            if (clock() - e.timestamp > CACHE_TTL_MS) return false
            e
        }
        if (entry.dirMtime == null) return true
        val current = try { Files.getLastModifiedTime(directory) } catch (_: Exception) { return false }
        return current == entry.dirMtime
    }

    companion object {
        internal const val CACHE_MAX_SIZE = 20
        internal const val CACHE_TTL_MS = 2L * 60 * 1000 // 2 minutes; mtime invalidation handles most staleness
    }

    /**
     * On Windows some system junctions like `C:\Documents and Settings` have an ACL that denies
     * listing, but they are reparse points pointing at an accessible target (`C:\Users`). Follow
     * the reparse point to the real target in that case so the user can still enter them.
     */
    private fun resolveReparsePoint(directory: Path): Path {
        if (!isWindows) return directory
        return try {
            val real = directory.toRealPath()
            if (real != directory) real else directory
        } catch (_: Exception) {
            directory
        }
    }

    /**
     * What [resolveOverwriteAction] tells the caller to do for a single existing target.
     * `CANCEL` aborts the whole loop, `SKIP` continues to the next source, `OVERWRITE`
     * means "go ahead and replace it now."
     */
    internal enum class TargetAction { OVERWRITE, SKIP, CANCEL }

    /** Mutable holder so a single policy var can be threaded through nested helpers. */
    internal class PolicyHolder(var policy: OverwritePolicy)

    /**
     * Resolve what to do when [target] already exists, given the current [holder] policy.
     * For [OverwritePolicy.ASK] we delegate to [onOverwriteConfirm] and translate the
     * 7-button dialog response into a [TargetAction], possibly upgrading the policy so
     * subsequent files in the run can answer without prompting.
     */
    internal suspend fun resolveOverwriteAction(
        source: Path,
        target: Path,
        holder: PolicyHolder,
        onOverwriteConfirm: suspend (Path) -> OverwriteResponse,
    ): TargetAction = when (holder.policy) {
        OverwritePolicy.OVERWRITE_ALL -> TargetAction.OVERWRITE
        OverwritePolicy.SKIP_ALL -> TargetAction.SKIP
        OverwritePolicy.IF_OLDER -> ifOlderAction(source, target)
        OverwritePolicy.ASK -> when (onOverwriteConfirm(target)) {
            OverwriteResponse.OVERWRITE -> TargetAction.OVERWRITE
            OverwriteResponse.SKIP -> TargetAction.SKIP
            OverwriteResponse.OVERWRITE_ALL -> {
                holder.policy = OverwritePolicy.OVERWRITE_ALL
                TargetAction.OVERWRITE
            }
            OverwriteResponse.SKIP_ALL -> {
                holder.policy = OverwritePolicy.SKIP_ALL
                TargetAction.SKIP
            }
            OverwriteResponse.OVERWRITE_IF_OLDER -> ifOlderAction(source, target)
            OverwriteResponse.OVERWRITE_ALL_IF_OLDER -> {
                holder.policy = OverwritePolicy.IF_OLDER
                ifOlderAction(source, target)
            }
            OverwriteResponse.CANCEL -> TargetAction.CANCEL
        }
    }

    private fun ifOlderAction(source: Path, target: Path): TargetAction {
        val sourceNewer = try {
            Files.getLastModifiedTime(source) > Files.getLastModifiedTime(target)
        } catch (_: Exception) {
            // mtime unreadable on either side — fall through to overwrite so the user's
            // intent ("replace stale targets") wins over a transient stat failure.
            true
        }
        return if (sourceNewer) TargetAction.OVERWRITE else TargetAction.SKIP
    }

    suspend fun copyFilesWithProgress(
        sources: List<Path>,
        destination: Path,
        initialPolicy: OverwritePolicy,
        onProgress: suspend (copiedCount: Int, currentFile: String) -> Unit,
        onOverwriteConfirm: suspend (path: Path) -> OverwriteResponse,
        onError: suspend (path: Path, error: Exception) -> Unit,
        isCancelled: () -> Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        var copiedCount = 0
        val holder = PolicyHolder(initialPolicy)

        loop@ for (source in sources) {
            if (isCancelled()) break
            val target = destination.resolve(source.name)
            try {
                if (source.isDirectory()) {
                    val (newCount, cancelled) = copyDirectoryWithProgress(
                        source, target, copiedCount, holder,
                        onProgress, onOverwriteConfirm, onError, isCancelled,
                    )
                    copiedCount = newCount
                    if (cancelled) break@loop
                } else {
                    when (val action = copyFileWithOverwrite(source, target, holder, onOverwriteConfirm)) {
                        TargetAction.OVERWRITE, TargetAction.SKIP -> {
                            if (action == TargetAction.OVERWRITE) {
                                copiedCount++
                                onProgress(copiedCount, source.name)
                            }
                        }
                        TargetAction.CANCEL -> break@loop
                    }
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to copy $source to $destination: ${e.message}")
                onError(source, e)
            }
        }
        invalidateDestinationTree(destination, sources)
    }

    /**
     * Invalidate the listing cache for [destination] and every nested directory the
     * copy actually wrote into. Without this, a tab viewing a sub-directory of the
     * destination keeps serving the pre-copy listing.
     */
    private fun invalidateDestinationTree(destination: Path, sources: List<Path>) {
        invalidateListingCache(destination)
        for (source in sources) {
            if (!source.isDirectory()) continue
            val target = destination.resolve(source.name)
            if (!target.isDirectory()) continue
            try {
                walkFileTree(target, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        invalidateListingCache(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (e: Exception) {
                thisLogger().debug("Failed to walk $target for cache invalidation: ${e.message}")
            }
        }
    }

    /**
     * Copy a single file honouring [holder]'s policy. Returns [TargetAction.OVERWRITE]
     * when the file was actually written (so the caller can tick progress), [SKIP] when
     * the policy chose to skip it, or [CANCEL] when the user aborted via the dialog.
     */
    private suspend fun copyFileWithOverwrite(
        source: Path,
        target: Path,
        holder: PolicyHolder,
        onOverwriteConfirm: suspend (Path) -> OverwriteResponse,
    ): TargetAction {
        // Stream bytes onto the stub if [source] lives in a lazy VFS (currently only .iso).
        // No-op for ordinary files and for VFSs that extract eagerly.
        OpenVfsRegistry.materializeIfNeeded(source)
        if (!target.exists()) {
            Files.copy(source, target)
            return TargetAction.OVERWRITE
        }
        return when (val action = resolveOverwriteAction(source, target, holder, onOverwriteConfirm)) {
            TargetAction.OVERWRITE -> {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                action
            }
            TargetAction.SKIP, TargetAction.CANCEL -> action
        }
    }

    private suspend fun copyDirectoryWithProgress(
        source: Path,
        target: Path,
        startCount: Int,
        holder: PolicyHolder,
        onProgress: suspend (Int, String) -> Unit,
        onOverwriteConfirm: suspend (Path) -> OverwriteResponse,
        onError: suspend (Path, Exception) -> Unit,
        isCancelled: () -> Boolean,
    ): Pair<Int, Boolean> {
        var copiedCount = startCount

        try {
            Files.createDirectories(target)
            copiedCount++
            onProgress(copiedCount, source.name)
        } catch (e: Exception) {
            thisLogger().warn("Failed to create directory $target: ${e.message}")
            onError(source, e)
            return copiedCount to false
        }

        try {
            Files.newDirectoryStream(source).use { stream ->
                for (entry in stream) {
                    if (isCancelled()) break
                    val entryTarget = target.resolve(entry.name)
                    if (entry.isDirectory()) {
                        val (sub, cancelled) = copyDirectoryWithProgress(
                            entry, entryTarget, copiedCount, holder,
                            onProgress, onOverwriteConfirm, onError, isCancelled,
                        )
                        copiedCount = sub
                        if (cancelled) return copiedCount to true
                    } else {
                        try {
                            when (copyFileWithOverwrite(entry, entryTarget, holder, onOverwriteConfirm)) {
                                TargetAction.OVERWRITE -> {
                                    copiedCount++
                                    onProgress(copiedCount, entry.name)
                                }
                                TargetAction.SKIP -> { /* nothing */ }
                                TargetAction.CANCEL -> return copiedCount to true
                            }
                        } catch (e: Exception) {
                            thisLogger().warn("Failed to copy $entry: ${e.message}")
                            onError(entry, e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to list directory $source: ${e.message}")
            onError(source, e)
        }
        return copiedCount to false
    }

    suspend fun moveFilesWithProgress(
        sources: List<Path>,
        destination: Path,
        initialPolicy: OverwritePolicy,
        onProgress: suspend (movedCount: Int, currentFile: String) -> Unit,
        onOverwriteConfirm: suspend (path: Path) -> OverwriteResponse,
        onError: suspend (path: Path, error: Exception) -> Unit,
        isCancelled: () -> Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        var movedCount = 0
        val holder = PolicyHolder(initialPolicy)

        loop@ for (source in sources) {
            if (isCancelled()) break
            val target = destination.resolve(source.name)
            try {
                if (source.isDirectory()) {
                    // Enumerate every entry under [source] up-front and move them one
                    // by one so each file ticks the progress bar — the historical
                    // single-call Files.move(REPLACE_EXISTING) on a directory only
                    // reported one step regardless of size.
                    val (newCount, cancelled) = moveDirectoryWithProgress(
                        source, target, movedCount, holder,
                        onProgress, onOverwriteConfirm, onError, isCancelled,
                    )
                    movedCount = newCount
                    if (cancelled) break@loop
                } else {
                    when (val action = moveFileWithOverwrite(source, target, holder, onOverwriteConfirm)) {
                        TargetAction.OVERWRITE -> {
                            movedCount++
                            onProgress(movedCount, source.name)
                        }
                        TargetAction.SKIP -> { /* nothing */ }
                        TargetAction.CANCEL -> break@loop
                    }
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to move $source to $destination: ${e.message}")
                onError(source, e)
            }
        }
        invalidateDestinationTree(destination, sources)
        sources.mapNotNull { it.parent }.toSet().forEach { invalidateListingCache(it) }
    }

    /**
     * Move a single file honouring [holder]'s policy. Returns [TargetAction.OVERWRITE]
     * when the file was actually moved, [SKIP] when the policy chose to skip it, or
     * [CANCEL] when the user aborted.
     */
    private suspend fun moveFileWithOverwrite(
        source: Path,
        target: Path,
        holder: PolicyHolder,
        onOverwriteConfirm: suspend (Path) -> OverwriteResponse,
    ): TargetAction {
        if (!target.exists()) {
            crossFileSystemMove(source, target)
            return TargetAction.OVERWRITE
        }
        return when (val action = resolveOverwriteAction(source, target, holder, onOverwriteConfirm)) {
            TargetAction.OVERWRITE -> {
                crossFileSystemMove(source, target, StandardCopyOption.REPLACE_EXISTING)
                action
            }
            TargetAction.SKIP, TargetAction.CANCEL -> action
        }
    }

    private suspend fun moveDirectoryWithProgress(
        source: Path,
        target: Path,
        startCount: Int,
        holder: PolicyHolder,
        onProgress: suspend (Int, String) -> Unit,
        onOverwriteConfirm: suspend (Path) -> OverwriteResponse,
        onError: suspend (Path, Exception) -> Unit,
        isCancelled: () -> Boolean,
    ): Pair<Int, Boolean> {
        var movedCount = startCount

        try {
            Files.createDirectories(target)
            movedCount++
            onProgress(movedCount, source.name)
        } catch (e: Exception) {
            thisLogger().warn("Failed to create directory $target: ${e.message}")
            onError(source, e)
            return movedCount to false
        }

        try {
            Files.newDirectoryStream(source).use { stream ->
                for (entry in stream) {
                    if (isCancelled()) return movedCount to true
                    val entryTarget = target.resolve(entry.name)
                    if (entry.isDirectory()) {
                        val (sub, cancelled) = moveDirectoryWithProgress(
                            entry, entryTarget, movedCount, holder,
                            onProgress, onOverwriteConfirm, onError, isCancelled,
                        )
                        movedCount = sub
                        if (cancelled) return movedCount to true
                    } else {
                        try {
                            when (moveFileWithOverwrite(entry, entryTarget, holder, onOverwriteConfirm)) {
                                TargetAction.OVERWRITE -> {
                                    movedCount++
                                    onProgress(movedCount, entry.name)
                                }
                                TargetAction.SKIP -> { /* nothing */ }
                                TargetAction.CANCEL -> return movedCount to true
                            }
                        } catch (e: Exception) {
                            thisLogger().warn("Failed to move $entry: ${e.message}")
                            onError(entry, e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to list directory $source: ${e.message}")
            onError(source, e)
            return movedCount to false
        }

        // Source directory is now empty; remove it so the overall move semantics match
        // the previous single-call Files.move behaviour.
        try {
            Files.deleteIfExists(source)
        } catch (e: Exception) {
            thisLogger().warn("Failed to remove emptied source directory $source: ${e.message}")
            onError(source, e)
        }
        return movedCount to false
    }

    suspend fun deleteFilesWithProgress(
        paths: List<Path>,
        onProgress: suspend (deletedCount: Int, currentFile: String) -> Unit,
        onError: suspend (path: Path, error: Exception) -> Unit,
        isCancelled: () -> Boolean,
        useRecycleBin: Boolean = false,
    ): Unit = withContext(Dispatchers.IO) {
        var deletedCount = 0

        // Recycle-bin mode: move each top-level path to the OS trash in one call. Directories
        // move atomically (the OS handles recursion), so we don't walk the tree ourselves.
        // Progress is reported once per top-level path since moveToTrash is a single op.
        if (useRecycleBin && isMoveToTrashSupported()) {
            for (path in paths) {
                if (isCancelled()) break
                try {
                    val moved = moveToTrash(path)
                    if (moved) {
                        deletedCount++
                        onProgress(deletedCount, path.name)
                    } else {
                        onError(path, IOException("Failed to move to Recycle Bin: $path"))
                    }
                } catch (e: Exception) {
                    thisLogger().warn("Failed to move to trash $path: ${e.message}")
                    onError(path, e)
                }
            }
            paths.mapNotNull { it.parent }.toSet().forEach { invalidateListingCache(it) }
            return@withContext
        }

        for (path in paths) {
            if (isCancelled()) break
            try {
                if (path.isDirectory()) {
                    // Collect the file tree first to avoid runBlocking inside walkFileTree callbacks
                    val entries = mutableListOf<Path>()
                    val walkErrors = mutableListOf<Pair<Path, IOException>>()
                    walkFileTree(path, object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            entries.add(file)
                            return FileVisitResult.CONTINUE
                        }
                        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                            walkErrors.add(file to exc)
                            return FileVisitResult.CONTINUE
                        }
                        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                            if (exc != null) {
                                walkErrors.add(dir to exc)
                            }
                            entries.add(dir)
                            return FileVisitResult.CONTINUE
                        }
                    })
                    for ((errorPath, errorExc) in walkErrors) {
                        onError(errorPath, errorExc)
                    }
                    for (entry in entries) {
                        if (isCancelled()) break
                        try {
                            Files.delete(entry)
                            deletedCount++
                            onProgress(deletedCount, entry.name)
                        } catch (e: Exception) {
                            thisLogger().warn("Failed to delete $entry: ${e.message}")
                            onError(entry, e)
                        }
                    }
                } else {
                    try {
                        Files.deleteIfExists(path)
                        deletedCount++
                        onProgress(deletedCount, path.name)
                    } catch (e: Exception) {
                        thisLogger().warn("Failed to delete $path: ${e.message}")
                        onError(path, e)
                    }
                }
            } catch (e: Exception) {
                thisLogger().warn("Failed to process $path: ${e.message}")
                onError(path, e)
            }
        }
        paths.mapNotNull { it.parent }.toSet().forEach { invalidateListingCache(it) }
    }

    suspend fun renameFile(source: Path, newName: String): Path = withContext(Dispatchers.IO) {
        val parent = source.parent ?: throw IllegalArgumentException("Cannot rename a root path")
        val target = parent.resolve(newName)
        val moved = Files.move(source, target)
        invalidateListingCache(parent)
        moved
    }

    suspend fun createDirectory(parent: Path, name: String): Path = withContext(Dispatchers.IO) {
        val created = Files.createDirectory(parent.resolve(name))
        invalidateListingCache(parent)
        created
    }

    fun getRoots(): List<String> {
        return if (isWindows) {
            val roots = File.listRoots().map { it.absolutePath }.toMutableList()
            val home = System.getProperty("user.home")
            if (home != null) {
                val homePath = Path.of(home)
                if (homePath.exists() && homePath.isDirectory()) {
                    roots.add(homePath.toString())
                }
            }
            roots
        } else {
            val roots = mutableListOf("/")

            val home = System.getProperty("user.home")
            if (home != null) {
                val homePath = Path.of(home)
                if (homePath.exists() && homePath.isDirectory()) {
                    roots.add(homePath.toString())
                }
            }

            if (isMac) {
                addSubdirectories(roots, Path.of("/Volumes"))
            } else {
                addSubdirectories(roots, Path.of("/media"))
                addSubdirectories(roots, Path.of("/mnt"))
                val user = System.getProperty("user.name")
                if (user != null) {
                    addSubdirectories(roots, Path.of("/run/media/$user"))
                }
            }

            roots
        }
    }

    private fun addSubdirectories(roots: MutableList<String>, dir: Path) {
        if (dir.exists() && dir.isDirectory()) {
            try {
                Files.newDirectoryStream(dir).use { stream ->
                    stream.filter { it.isDirectory() }.forEach { roots.add(it.toString()) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun readOwner(path: Path): String = readFileOwner(path)
    private fun readGroup(path: Path): String = readFileGroup(path)
    private fun readPermissions(path: Path): String = readFilePermissions(path, isWindows)

    private fun dosAttrsToString(attrs: DosFileAttributes): String = buildString {
        if (attrs.isReadOnly) append('R')
        if (attrs.isHidden) append('H')
        if (attrs.isSystem) append('S')
        if (attrs.isArchive) append('A')
    }

    private fun copyDirectoryRecursive(source: Path, target: Path) {
        walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relativePath = source.relativize(dir).toString()
                val targetDir = if (relativePath.isEmpty()) target else target.resolve(relativePath)
                Files.createDirectories(targetDir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                OpenVfsRegistry.materializeIfNeeded(file)
                val relativePath = source.relativize(file).toString()
                Files.copy(file, target.resolve(relativePath), StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                thisLogger().warn("Failed to copy $file: ${exc.message}")
                return FileVisitResult.CONTINUE
            }
        })
    }

    internal fun crossFileSystemMove(source: Path, target: Path, vararg options: CopyOption) {
        if (source.fileSystem == target.fileSystem) {
            Files.move(source, target, *options)
            return
        }
        val replaceExisting = StandardCopyOption.REPLACE_EXISTING in options
        if (source.isDirectory()) {
            // Same-FS Files.move(..., REPLACE_EXISTING) replaces atomically (or fails on a
            // non-empty target). copyDirectoryRecursive alone would *merge* into an existing
            // target, leaving stale entries the user expected to be replaced. Wipe the target
            // first so cross-FS semantics line up with the same-FS path.
            if (replaceExisting && Files.exists(target)) {
                deleteDirectoryRecursive(target)
            }
            copyDirectoryRecursive(source, target)
            deleteDirectoryRecursive(source)
        } else {
            OpenVfsRegistry.materializeIfNeeded(source)
            if (replaceExisting) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.copy(source, target)
            }
            Files.delete(source)
        }
    }

    private fun deleteDirectoryRecursive(path: Path) {
        walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                thisLogger().warn("Failed to delete $file: ${exc.message}")
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) {
                    thisLogger().warn("Failed to fully iterate $dir: ${exc.message}")
                }
                try {
                    Files.delete(dir)
                } catch (e: Exception) {
                    thisLogger().warn("Failed to delete directory $dir: ${e.message}")
                }
                return FileVisitResult.CONTINUE
            }
        })
    }
}

