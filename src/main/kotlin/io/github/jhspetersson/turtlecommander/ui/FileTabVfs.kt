package io.github.jhspetersson.turtlecommander.ui

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import io.github.jhspetersson.turtlecommander.util.fileErrorMessage
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal fun FileTab.enterVfs(archivePath: Path) {
    val fileName = archivePath.fileName?.toString() ?: "archive"
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Opening $fileName\u2026", true) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            try {
                if (vfsStack.isEmpty()) {
                    val vfs = VirtualFileSystemRegistry.create(archivePath)
                    if (indicator.isCanceled) { vfs.close(); return }
                    vfsStack.add(VfsStackEntry(vfs, archivePath))
                    runBlocking { navigateTo(vfs.root) }
                } else {
                    var tempFile: java.io.File? = null
                    try {
                        io.github.jhspetersson.turtlecommander.service.VfsTempCleanup.cleanupOnce()
                        val tempDir = Files.createTempDirectory("turtle-vfs-")
                        val tempPath = tempDir.resolve(fileName)
                        Files.copy(archivePath, tempPath)
                        tempFile = tempPath.toFile()
                        val vfs = VirtualFileSystemRegistry.create(tempFile.toPath())
                        if (indicator.isCanceled) {
                            vfs.close()
                            tempFile.delete(); tempFile.parentFile?.delete()
                            return
                        }
                        vfsStack.add(VfsStackEntry(vfs, archivePath, tempFile))
                        runBlocking { navigateTo(vfs.root) }
                    } catch (e: Exception) {
                        tempFile?.let {
                            try { it.delete(); it.parentFile?.delete() } catch (_: Exception) {}
                        }
                        fileErrorNotification("Cannot open nested archive: ${fileErrorMessage(e)}")
                    }
                }
            } catch (e: Exception) {
                fileErrorNotification("Cannot open archive: ${fileErrorMessage(e)}")
            }
        }
    })
}

internal fun FileTab.exitVfs() {
    if (vfsStack.isEmpty()) return
    val entry = vfsStack.removeLast()
    entry.vfs.close()
    entry.cleanupTempFile()
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
        dispose()
        val path = try { Path.of(segmentPath) } catch (_: Exception) { null }
        if (path != null && path.toFile().isDirectory) {
            fileOps.launch { navigateTo(path) }
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

    while (vfsStack.size > targetLevel + 1) {
        val entry = vfsStack.removeLast()
        entry.vfs.close()
        entry.cleanupTempFile()
    }

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

internal suspend fun FileTab.writeBackNestedArchives() = withContext(Dispatchers.IO) {
    vfsWriteMutex.withLock {
        writeBackNestedArchivesLocked()
    }
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
        val newPath = if (relativePath.isEmpty()) vfs.root else vfs.root.resolve(relativePath)
        navigateTo(newPath, selectName = selectName)
    } else {
        navigateTo(currentPath, selectName = selectName)
    }
}

internal fun vfsRelativePath(vfs: VirtualFileSystem, path: Path): String {
    return io.github.jhspetersson.turtlecommander.vfs.vfsRelativePath(vfs.root, path)
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
