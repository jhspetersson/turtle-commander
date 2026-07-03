package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class VfsStackEntry(
    val vfs: VirtualFileSystem,
    var parentPath: Path,
    val tempFile: File? = null,
) {
    fun cleanupTempFile() {
        tempFile?.let {
            try { it.delete(); it.parentFile?.delete() } catch (_: Exception) {}
        }
    }
}

class VfsEditEntry(
    var vfsFilePath: Path,
    val tempFilePath: Path,
    val vfsStack: MutableList<VfsStackEntry>,
    /**
     * Path of the edited file relative to the innermost VFS root, captured when the edit is
     * tracked (while [vfsFilePath] is still valid). The write-back resolves the destination fresh
     * from this against the *current* innermost root on every save, so it survives the temp dir
     * being recreated by a flush — whether this file's own earlier save, or another file edited
     * from the same archive (see [writeBackLocked]). Empty when there is no VFS stack.
     */
    val vfsFileRelPath: String = "",
    /**
     * Mutex owned by the originating [io.github.jhspetersson.turtlecommander.ui.FileTab] that
     * serializes all write-back work against the shared [vfsStack]. Must be held while flushing
     * or copying into any VFS in the stack; otherwise a concurrent refresh can close a parent
     * ZipFileSystem mid-`Files.copy` and raise `ClosedFileSystemException`.
     */
    val vfsWriteMutex: Mutex? = null,
    val onBeforeFlush: (() -> String)? = null,
    val onAfterFlush: ((String) -> Unit)? = null,
)

@Service(Service.Level.PROJECT)
class VfsEditService(
    private val cs: CoroutineScope,
) : Disposable {

    private val activeEdits = ConcurrentHashMap<String, VfsEditEntry>()

    private fun normalizeKey(path: String): String = path.replace('\\', '/')
    private fun normalizeKey(path: Path): String = normalizeKey(path.toString())

    fun trackEdit(entry: VfsEditEntry) {
        activeEdits[normalizeKey(entry.tempFilePath)] = entry
    }

    /** Test-only: checks whether a tracking entry still exists for the given normalized key. */
    internal fun isTrackedForTest(normalizedKey: String): Boolean = activeEdits.containsKey(normalizedKey)

    fun onFileSaved(filePath: String) {
        val entry = activeEdits[normalizeKey(filePath)] ?: return
        cs.launch {
            writeBack(entry)
        }
    }

    private suspend fun writeBack(entry: VfsEditEntry) = withContext(Dispatchers.IO) {
        try {
            val mutex = entry.vfsWriteMutex
            if (mutex != null) {
                mutex.withLock { writeBackLocked(entry) }
            } else {
                writeBackLocked(entry)
            }
            // Keep the entry tracked on success: the same file can be saved again, and each save
            // must be written back. (Previously it was removed here, so only the *first* save ever
            // reached the archive — every later save silently no-op'd.) It is cleaned up in
            // [dispose]; a failed write-back is dropped below.
        } catch (e: Exception) {
            thisLogger().warn("VFS edit write-back failed: ${e.message}")
            // Drop a failed edit so a permanently broken entry (e.g. the archive was closed
            // underneath it) doesn't keep retrying — and failing — on every subsequent save.
            activeEdits.remove(normalizeKey(entry.tempFilePath))
        }
    }

    /**
     * Core write-back body. Must be called with [VfsEditEntry.vfsWriteMutex] held (if one is
     * set) so that no concurrent refresh closes a parent ZipFileSystem while this loop is
     * mid-`Files.copy` into one of its ZipPaths.
     */
    private fun writeBackLocked(entry: VfsEditEntry) {
        val stack = entry.vfsStack
        val innermostVfs = stack.lastOrNull()?.vfs

        // Resolve the destination against the CURRENT innermost root every time. Any absolute path
        // captured when the edit was opened may now dangle: the temp dir gets deleted and recreated
        // by a flush — this file's own previous save, or another file edited from the same archive
        // that was saved first. Re-resolving from the archive-relative path keeps every save landing
        // on the right entry instead of a NoSuchFile/ClosedFileSystem failure that loses the edit.
        val target = when {
            innermostVfs == null -> entry.vfsFilePath
            entry.vfsFileRelPath.isEmpty() -> innermostVfs.root
            else -> innermostVfs.root.resolve(entry.vfsFileRelPath)
        }
        Files.copy(entry.tempFilePath, target, StandardCopyOption.REPLACE_EXISTING)
        entry.vfsFilePath = target

        // Capture the relative path of currentPath BEFORE any flush invalidates it
        val currentPathRel = entry.onBeforeFlush?.invoke() ?: ""

        // Flush the innermost VFS and reconstruct the file path for navigation/reuse.
        if (innermostVfs != null) {
            innermostVfs.flush()
            entry.vfsFilePath = if (entry.vfsFileRelPath.isEmpty()) innermostVfs.root else innermostVfs.root.resolve(entry.vfsFileRelPath)
        }

        // Write-back through the VFS stack (innermost to outermost)
        for (i in stack.indices.reversed()) {
            val stackEntry = stack[i]
            if (stackEntry.tempFile == null || i == 0) continue
            val parentStackEntry = stack[i - 1]
            Files.copy(stackEntry.tempFile.toPath(), stackEntry.parentPath, StandardCopyOption.REPLACE_EXISTING)
            val relPath = vfsRelativePath(parentStackEntry.vfs, stackEntry.parentPath)
            parentStackEntry.vfs.flush()
            stackEntry.parentPath = if (relPath.isEmpty()) parentStackEntry.vfs.root else parentStackEntry.vfs.root.resolve(relPath)
        }

        entry.onAfterFlush?.invoke(currentPathRel)
    }

    private fun vfsRelativePath(vfs: VirtualFileSystem, path: Path): String {
        return vfsRelativePath(vfs.root, path)
    }

    override fun dispose() {
        cs.cancel()
        val snapshot = activeEdits.values.toList()
        activeEdits.clear()
        for (entry in snapshot) {
            try {
                entry.tempFilePath.toFile().delete()
                entry.tempFilePath.parent?.toFile()?.delete()
            } catch (e: Exception) {
                thisLogger().debug("Failed to clean up temp file: ${entry.tempFilePath}", e)
            }
        }
    }
}

class VfsEditFileListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val service = project.service<VfsEditService>()
        for (event in events) {
            if (event is VFileContentChangeEvent) {
                service.onFileSaved(event.file.path)
            }
        }
    }
}
