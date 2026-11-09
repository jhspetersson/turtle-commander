package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import io.github.jhspetersson.turtlecommander.action.FileCopyBuffer
import io.github.jhspetersson.turtlecommander.vfs.SharedVfsRegistry
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Service(Service.Level.APP)
internal class VfsCloseService(private val cs: CoroutineScope) {

    fun closeOffEdt(entries: List<VfsStackEntry>, writeMutex: Mutex) {
        if (entries.isEmpty()) return
        if (!cs.isActive) {
            closeNow(entries)
            return
        }
        cs.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                writeMutex.withLock { closeNow(entries) }
            }
        }
    }

    fun closeNow(entries: List<VfsStackEntry>) {
        for (entry in entries) {
            try {
                if (!SharedVfsRegistry.release(entry.vfs)) entry.vfs.close()
            } catch (e: Exception) {
                thisLogger().warn("Failed to close VFS ${entry.vfs.archivePath}: ${e.message}")
            }
            entry.cleanupTempFile()
        }
        val closed = entries.map { it.vfs }
        ApplicationManager.getApplication()?.invokeLater({ FileCopyBuffer.dropEntriesOwnedBy(closed) }, ModalityState.any())
    }
}
