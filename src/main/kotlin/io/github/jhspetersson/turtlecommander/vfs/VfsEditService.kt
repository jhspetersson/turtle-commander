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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class VfsStackEntry(
    val vfs: VirtualFileSystem,
    var parentPath: Path,
    val tempFile: File? = null,
)

class VfsEditEntry(
    var vfsFilePath: Path,
    val tempFilePath: Path,
    val vfsStack: MutableList<VfsStackEntry>,
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

    fun onFileSaved(filePath: String) {
        val entry = activeEdits[normalizeKey(filePath)] ?: return
        cs.launch {
            writeBack(entry)
        }
    }

    private suspend fun writeBack(entry: VfsEditEntry) = withContext(Dispatchers.IO) {
        try {
            // Copy temp file content back to VFS path
            Files.copy(entry.tempFilePath, entry.vfsFilePath, StandardCopyOption.REPLACE_EXISTING)

            // Capture the relative path of currentPath BEFORE any flush invalidates it
            val currentPathRel = entry.onBeforeFlush?.invoke() ?: ""

            // Flush the innermost VFS and reconstruct the file path
            val stack = entry.vfsStack
            if (stack.isNotEmpty()) {
                val innermostVfs = stack.last().vfs
                val relPath = vfsRelativePath(innermostVfs, entry.vfsFilePath)
                innermostVfs.flush()
                entry.vfsFilePath = if (relPath.isEmpty()) innermostVfs.root else innermostVfs.root.resolve(relPath)
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

            activeEdits.remove(normalizeKey(entry.tempFilePath))
        } catch (e: Exception) {
            thisLogger().warn("VFS edit write-back failed: ${e.message}")
        }
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
