package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.ArchiveFileSystem
import io.github.jhspetersson.turtlecommander.vfs.JrtFileSystemProvider
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import java.nio.file.InvalidPathException
import java.nio.file.Path

sealed class OpenTarget {
    data class Local(val path: Path, val isDirectory: Boolean) : OpenTarget()

    data class ArchiveEntry(val archivePath: Path, val entryPath: String, val isDirectory: Boolean) : OpenTarget()

    companion object {
        fun of(file: VirtualFile): OpenTarget? {
            if (file.isInLocalFileSystem) {
                return Local(file.nioPathOrNull() ?: return null, file.isDirectory)
            }
            val fs = file.fileSystem as? ArchiveFileSystem ?: return null
            val local = fs.getLocalByEntry(file) ?: return null
            if (!local.isInLocalFileSystem) return null
            val archivePath = archiveBackingFile(local.nioPathOrNull() ?: return null, local.isDirectory) ?: return null
            val root = fs.getRootByEntry(file) ?: return null
            val entryPath = file.path.removePrefix(root.path).trim('/')
            return ArchiveEntry(archivePath, entryPath, file.isDirectory)
        }

        internal fun archiveBackingFile(localPath: Path, isDirectory: Boolean): Path? = if (isDirectory) {
            JrtFileSystemProvider.modulesImageFor(localPath).takeIf { JrtFileSystemProvider.isModulesImage(it) }
        } else {
            localPath.takeIf { VirtualFileSystemRegistry.supportsByExtension(it.fileName?.toString() ?: "") }
        }

        private fun VirtualFile.nioPathOrNull(): Path? = try {
            Path.of(path)
        } catch (_: InvalidPathException) {
            null
        }
    }
}
