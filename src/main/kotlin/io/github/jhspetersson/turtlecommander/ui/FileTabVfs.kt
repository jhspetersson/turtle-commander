package io.github.jhspetersson.turtlecommander.ui

import io.github.jhspetersson.turtlecommander.util.fileErrorMessage
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal fun FileTab.enterVfs(archivePath: Path) {
    try {
        if (vfsStack.isEmpty()) {
            val vfs = VirtualFileSystemRegistry.create(archivePath)
            vfsStack.add(VfsStackEntry(vfs, archivePath))
            fileOps.launch { navigateTo(vfs.root) }
        } else {
            fileOps.launch {
                var tempFile: java.io.File? = null
                try {
                    tempFile = withContext(Dispatchers.IO) {
                        val tempDir = Files.createTempDirectory("turtle-vfs-")
                        val fileName = archivePath.fileName.toString()
                        val tempPath = tempDir.resolve(fileName)
                        Files.copy(archivePath, tempPath)
                        tempPath.toFile()
                    }
                    val vfs = VirtualFileSystemRegistry.create(tempFile.toPath())
                    vfsStack.add(VfsStackEntry(vfs, archivePath, tempFile))
                    navigateTo(vfs.root)
                } catch (e: Exception) {
                    tempFile?.let {
                        try { it.delete(); it.parentFile?.delete() } catch (_: Exception) {}
                    }
                    fileErrorNotification("Cannot open nested archive: ${fileErrorMessage(e)}")
                }
            }
        }
    } catch (e: Exception) {
        fileErrorNotification("Cannot open archive: ${fileErrorMessage(e)}")
    }
}

internal fun FileTab.exitVfs() {
    if (vfsStack.isEmpty()) return
    val entry = vfsStack.removeLast()
    entry.vfs.close()
    if (entry.tempFile != null) {
        try {
            entry.tempFile.delete()
            entry.tempFile.parentFile?.delete()
        } catch (_: Exception) {}
    }
    val parentPath = entry.parentPath
    if (vfsStack.isNotEmpty()) {
        val parentVfsPath = parentPath.parent ?: vfsStack.last().vfs.root
        fileOps.launch { navigateTo(parentVfsPath, selectName = parentPath.fileName.toString()) }
    } else {
        fileOps.launch { navigateTo(parentPath.parent ?: parentPath, selectName = parentPath.fileName.toString()) }
    }
}

internal fun FileTab.handleVfsBreadcrumbClick(segmentPath: String) {
    val separator = if (vfsStack.first().parentPath.toString().contains("\\")) "\\" else "/"
    val outerArchiveStr = vfsStack.first().parentPath.toString()

    if (segmentPath.length < outerArchiveStr.length) {
        dispose()
        val path = try { Path.of(segmentPath) } catch (_: Exception) { null }
        if (path != null && path.toFile().isDirectory) {
            fileOps.launch { navigateTo(path) }
        }
        return
    }

    val prefixes = mutableListOf<String>()
    val sb = StringBuilder()
    for (stackEntry in vfsStack) {
        if (sb.isEmpty()) {
            sb.append(stackEntry.parentPath.toString())
        } else {
            val nestedPath = stackEntry.parentPath.toString().removePrefix("/").replace("/", separator)
            sb.append(separator).append(nestedPath)
        }
        prefixes.add(sb.toString())
    }

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
        if (entry.tempFile != null) {
            try {
                entry.tempFile.delete()
                entry.tempFile.parentFile?.delete()
            } catch (_: Exception) {}
        }
    }

    val vfs = vfsStack.last().vfs
    val archivePrefix = prefixes[targetLevel]
    val relativePath = segmentPath.removePrefix(archivePrefix)
        .removePrefix("\\").removePrefix("/")
        .replace("\\", "/")
    val vfsPath = if (relativePath.isEmpty()) vfs.root else vfs.getPath("/$relativePath")
    fileOps.launch { navigateTo(vfsPath) }
}

internal suspend fun FileTab.writeBackNestedArchives() = withContext(Dispatchers.IO) {
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

internal suspend fun FileTab.refreshAfterVfsChange(selectName: String? = null) {
    val vfs = currentVfs
    if (vfs != null) {
        val relativePath = vfsRelativePath(vfs, currentPath)
        vfs.flush()
        writeBackNestedArchives()
        val newPath = if (relativePath.isEmpty()) vfs.root else vfs.root.resolve(relativePath)
        navigateTo(newPath, selectName = selectName)
    } else {
        navigateTo(currentPath, selectName = selectName)
    }
}

internal fun vfsRelativePath(vfs: VirtualFileSystem, path: Path): String {
    val rootStr = vfs.root.toString().trimEnd('/').trimEnd('\\')
    val pathStr = path.toString()
    return if (pathStr.startsWith(rootStr)) {
        pathStr.removePrefix(rootStr).removePrefix("/").removePrefix("\\")
    } else {
        pathStr
    }
}

fun FileTab.getDisplayPath(): String {
    val vfs = currentVfs ?: return currentPath.toString()
    val separator = if (vfsStack.first().parentPath.toString().contains("\\")) "\\" else "/"
    val sb = StringBuilder()
    for (stackEntry in vfsStack) {
        if (sb.isEmpty()) {
            sb.append(stackEntry.parentPath.toString())
        } else {
            val nestedPath = stackEntry.parentPath.toString().removePrefix("/").replace("/", separator)
            sb.append(separator).append(nestedPath)
        }
    }
    if (!vfs.isRoot(currentPath)) {
        val relativePath = currentPath.toString()
        sb.append(separator).append(relativePath.removePrefix("/").replace("/", separator))
    }
    return sb.toString()
}
