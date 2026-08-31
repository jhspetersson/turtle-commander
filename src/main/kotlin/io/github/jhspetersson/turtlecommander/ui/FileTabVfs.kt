package io.github.jhspetersson.turtlecommander.ui
import io.github.jhspetersson.turtlecommander.service.VfsTempCleanup
import io.github.jhspetersson.turtlecommander.vfs.vfsRelativePath as vfsRootRelativePath
import java.io.File

import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.util.fileErrorMessage
import io.github.jhspetersson.turtlecommander.util.withIndicatorProgress
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry
import io.github.jhspetersson.turtlecommander.vfs.SilentVfsOpenException
import io.github.jhspetersson.turtlecommander.vfs.SharedVfsRegistry
import io.github.jhspetersson.turtlecommander.vfs.VfsOpenProgress
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal fun FileTab.enterVfs(entry: FileEntry) {
    val archivePath = entry.path
    val fileName = archivePath.fileName?.toString() ?: "archive"
    fileOps.launch {
        withIndicatorProgress(project, "Opening $fileName…") { indicator ->
            // Same graceful-cancel contract as the operations in FileTabOperations:
            // extraction observes the cancel request through the polled hook and returns
            // a partial VFS that the checks below close - NonCancellable keeps an abrupt
            // CancellationException from skipping that cleanup or leaving a VFS on the
            // stack without the follow-up navigation.
            withContext(NonCancellable) {
                val openProgress = VfsOpenProgress.fromIndicator(indicator)
                try {
                    if (vfsStack.isEmpty()) {
                        val vfs = withContext(Dispatchers.IO) {
                            SharedVfsRegistry.acquire(archivePath, openProgress)
                        }
                        if (indicator.isCanceled) {
                            runCatching { SharedVfsRegistry.release(vfs) }
                            return@withContext
                        }
                        attachSharedVfs(vfs, archivePath)
                        navigateTo(vfs.root)
                    } else {
                        var tempFile: File? = null
                        try {
                            VfsTempCleanup.cleanupOnce()
                            val vfs = withContext(Dispatchers.IO) {
                                // If the parent VFS is lazy (an .iso), the entry we're about to nest
                                // into is still a sparse stub on disk - stream the actual bytes from
                                // the disc image first so the Files.copy below sees real content.
                                // No-op for any VFS that already extracted everything in extract().
                                OpenVfsRegistry.materializeIfNeeded(archivePath)
                                val tempDir = Files.createTempDirectory("turtle-vfs-")
                                val tempPath = tempDir.resolve(fileName)
                                tempFile = tempPath.toFile()
                                Files.copy(archivePath, tempPath)
                                VirtualFileSystemRegistry.create(tempPath, openProgress)
                            }
                            if (indicator.isCanceled) {
                                vfs.close()
                                tempFile?.delete(); tempFile?.parentFile?.delete()
                                return@withContext
                            }
                            vfsStack.add(VfsStackEntry(vfs, archivePath, tempFile))
                            navigateTo(vfs.root)
                        } catch (_: SilentVfsOpenException) {
                            // Recognised extension but unrecognised contents (e.g. a .pak that is
                            // neither PAK nor ZIP): fall through to opening it as a normal file.
                            tempFile?.let {
                                try { it.delete(); it.parentFile?.delete() } catch (_: Exception) {}
                            }
                            openFile(entry)
                        } catch (e: Exception) {
                            tempFile?.let {
                                try { it.delete(); it.parentFile?.delete() } catch (_: Exception) {}
                            }
                            fileErrorNotification("Cannot open nested archive: ${fileErrorMessage(e)}", e)
                        }
                    }
                } catch (_: SilentVfsOpenException) {
                    // Recognised extension but unrecognised contents: open it as a normal file.
                    openFile(entry)
                } catch (e: Exception) {
                    fileErrorNotification("Cannot open archive: ${fileErrorMessage(e)}", e)
                }
            }
        }
    }
}

internal fun FileTab.exitVfs() {
    if (vfsStack.isEmpty()) return
    val entry = vfsStack.removeLast()
    // Close off the EDT under the write mutex — see FileTab.scheduleVfsClose. Closing here
    // (Backspace at an archive root) used to run the recursive temp-dir delete inline and could
    // race an in-flight write-back on the same stack.
    scheduleVfsClose(listOf(entry))
    val parentPath = entry.parentPath
    val selectName = parentPath.fileName?.toString() ?: ""
    if (vfsStack.isNotEmpty()) {
        val parentVfsPath = parentPath.parent ?: vfsStack.last().vfs.root
        fileOps.launch { navigateTo(parentVfsPath, selectName = selectName) }
    } else {
        fileOps.launch { navigateTo(parentPath.parent ?: parentPath, selectName = selectName) }
    }
}

internal fun FileTab.handleVfsBreadcrumbClick(segmentPath: String) {
    val outerArchiveStr = vfsStack.first().parentPath.toString()

    if (segmentPath.length < outerArchiveStr.length) {
        closeVfsStackAsync()
        val path = try { Path.of(segmentPath) } catch (_: Exception) { null }
        if (path != null) {
            // Stat off the EDT: a dead network path can block for many seconds.
            fileOps.launch {
                if (withContext(Dispatchers.IO) { Files.isDirectory(path) }) {
                    navigateTo(path)
                }
            }
        }
        return
    }

    val (_, prefixes) = buildVfsStackPrefixes()

    var targetLevel = -1
    for (i in prefixes.indices.reversed()) {
        if (segmentPath.length >= prefixes[i].length) {
            targetLevel = i
            break
        }
    }

    if (targetLevel < 0) return

    val removed = mutableListOf<VfsStackEntry>()
    while (vfsStack.size > targetLevel + 1) {
        removed.add(vfsStack.removeLast())
    }
    scheduleVfsClose(removed)

    val vfs = vfsStack.last().vfs
    val archivePrefix = prefixes[targetLevel]
    val relativePath = segmentPath.removePrefix(archivePrefix)
        .removePrefix("\\").removePrefix("/")
        .replace("\\", "/")
    val vfsPath = if (relativePath.isEmpty()) vfs.root else vfs.getPath("/$relativePath")
    fileOps.launch { navigateTo(vfsPath) }
}

/**
 * Body of the nested-archive write-back loop. The caller is responsible for holding
 * [FileTab.vfsWriteMutex] and for being on [Dispatchers.IO]. Split out so callers that
 * also need to flush the innermost VFS atomically (e.g. [refreshAfterVfsChange]) can
 * do so under the same lock without re-entering the mutex.
 */
internal fun FileTab.writeBackNestedArchivesLocked() {
    for (i in vfsStack.indices.reversed()) {
        val entry = vfsStack[i]
        if (entry.tempFile == null || i == 0) continue
        val parentEntry = vfsStack[i - 1]
        Files.copy(entry.tempFile.toPath(), entry.parentPath, StandardCopyOption.REPLACE_EXISTING)
        val relPath = vfsRelativePath(parentEntry.vfs, entry.parentPath)
        parentEntry.vfs.flush()
        val newParentPath = if (relPath.isEmpty()) parentEntry.vfs.root else parentEntry.vfs.root.resolve(relPath)
        entry.parentPath = newParentPath
    }
}

internal suspend fun FileTab.writeBackNestedArchives() {
    withContext(Dispatchers.IO) {
        vfsWriteMutex.withLock {
            writeBackNestedArchivesLocked()
        }
    }
    notifySharedVfsMutated()
}

internal suspend fun FileTab.refreshAfterVfsChange(selectName: String? = null) {
    invalidateFreeSpaceCache()
    val vfs = currentVfs
    if (vfs != null) {
        val relativePath = vfsRelativePath(vfs, currentPath)
        withContext(Dispatchers.IO) {
            vfsWriteMutex.withLock {
                vfs.flush()
                writeBackNestedArchivesLocked()
            }
        }
        notifySharedVfsMutated()
        val newPath = if (relativePath.isEmpty()) vfs.root else vfs.root.resolve(relativePath)
        navigateTo(newPath, selectName = selectName)
    } else {
        navigateTo(currentPath, selectName = selectName)
    }
}

internal fun vfsRelativePath(vfs: VirtualFileSystem, path: Path): String {
    return vfsRootRelativePath(vfs.root, path)
}

/**
 * Builds the prefix string for each level of the VFS stack, e.g.
 * `["C:\dir\archive.zip", "C:\dir\archive.zip\inner.tar"]`.
 */
internal fun FileTab.buildVfsStackPrefixes(): Pair<String, List<String>> {
    val separator = if (vfsStack.first().parentPath.toString().contains("\\")) "\\" else "/"
    val prefixes = mutableListOf<String>()
    val sb = StringBuilder()
    for ((i, stackEntry) in vfsStack.withIndex()) {
        if (i == 0) {
            sb.append(stackEntry.parentPath.toString())
        } else {
            val parentVfs = vfsStack[i - 1].vfs
            val nestedPath = vfsRelativePath(parentVfs, stackEntry.parentPath)
                .removePrefix("/").replace("/", separator)
            sb.append(separator).append(nestedPath)
        }
        prefixes.add(sb.toString())
    }
    return separator to prefixes
}

fun FileTab.getDisplayPath(): String {
    val vfs = currentVfs ?: return currentPath.toString()
    val (separator, prefixes) = buildVfsStackPrefixes()
    val sb = StringBuilder(prefixes.last())
    if (!vfs.isRoot(currentPath)) {
        val relativePath = vfsRelativePath(vfs, currentPath)
        sb.append(separator).append(relativePath.removePrefix("/").replace("/", separator))
    }
    return sb.toString()
}

internal fun FileTab.attachSharedVfs(vfs: VirtualFileSystem, archivePath: Path) {
    val entry = VfsStackEntry(vfs, archivePath)
    val listener: () -> Unit = { onSharedVfsMutated() }
    entry.mutationListener = listener
    SharedVfsRegistry.addMutationListener(vfs, listener)
    vfsStack.add(entry)
}

internal fun FileTab.detachSharedVfs(entry: VfsStackEntry) {
    entry.mutationListener?.let { SharedVfsRegistry.removeMutationListener(entry.vfs, it) }
    entry.mutationListener = null
}

internal fun FileTab.notifySharedVfsMutated() {
    vfsStack.firstOrNull()?.let { SharedVfsRegistry.notifyMutated(it.vfs, it.mutationListener) }
}

internal fun FileTab.onSharedVfsMutated() {
    fileOps.launch {
        val first = vfsStack.firstOrNull() ?: return@launch
        val vfs = first.vfs
        withContext(Dispatchers.IO) {
            vfsWriteMutex.withLock {
                val second = vfsStack.getOrNull(1)
                if (second != null) {
                    val rel = vfsRelativePath(vfs, second.parentPath)
                    second.parentPath = if (rel.isEmpty()) vfs.root else vfs.root.resolve(rel)
                }
            }
        }
        if (vfsStack.size == 1 && currentVfs === vfs) {
            val rel = vfsRelativePath(vfs, currentPath)
            val newPath = if (rel.isEmpty()) vfs.root else vfs.root.resolve(rel)
            val target = withContext(Dispatchers.IO) {
                if (Files.exists(newPath)) newPath else vfs.root
            }
            navigateTo(target, requestFocus = false)
        }
    }
}
