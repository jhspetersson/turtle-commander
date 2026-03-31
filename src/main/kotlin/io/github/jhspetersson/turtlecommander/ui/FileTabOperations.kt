package io.github.jhspetersson.turtlecommander.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.jhspetersson.turtlecommander.dialog.*
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.operation.CombineFilesOperation
import io.github.jhspetersson.turtlecommander.operation.SplitFileOperation
import io.github.jhspetersson.turtlecommander.service.ArchiveService
import io.github.jhspetersson.turtlecommander.service.OverwriteResponse
import io.github.jhspetersson.turtlecommander.util.countFiles
import io.github.jhspetersson.turtlecommander.util.fileErrorMessage
import io.github.jhspetersson.turtlecommander.util.formatSize
import io.github.jhspetersson.turtlecommander.vfs.VfsEditEntry
import io.github.jhspetersson.turtlecommander.vfs.VfsEditService
import io.github.jhspetersson.turtlecommander.vfs.ZipVirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path

internal fun FileTab.performCopy() {
    val otherTab = getOtherPanelTab()
    if (otherTab?.currentVfs?.isReadOnly == true) {
        fileErrorNotification("Cannot copy into a read-only archive")
        return
    }
    performCopyEntries(getSelectedEntries(), otherPanelPathProvider() ?: return)
}

internal fun FileTab.performCopyEntries(selected: List<FileEntry>, destination: Path, destinationDisplayPath: String? = null) {
    if (selected.isEmpty()) return

    val displayPath = destinationDisplayPath ?: getOtherPanelDisplayPath() ?: destination.toString()
    val copyDialog = CopyDialog(project, selected, destination, displayPath)
    if (!copyDialog.showAndGet()) return
    val overwriteAll = copyDialog.overwriteExisting
    val sourcePaths = selected.map { it.path }

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying files", true) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            indicator.text = "Counting files..."

            runBlocking {
                val totalFiles = countFiles(sourcePaths)
                indicator.isIndeterminate = false

                fileOps.copyFilesWithProgress(
                    sources = sourcePaths,
                    destination = destination,
                    overwriteAll = overwriteAll,
                    onProgress = { count, name ->
                        indicator.fraction = if (totalFiles > 0) count.toDouble() / totalFiles else 1.0
                        indicator.text = "Copying $count / $totalFiles"
                        indicator.text2 = name
                    },
                    onOverwriteConfirm = { path ->
                        askOverwriteConfirm(path)
                    },
                    onError = { path, error ->
                        fileErrorNotification("Failed to copy ${path.fileName}: ${fileErrorMessage(error)}")
                    },
                    isCancelled = { indicator.isCanceled },
                )

                refreshAfterVfsChange()
                withContext(Dispatchers.EDT) {
                    onRefreshOtherPanel()
                }
            }
        }
    })
}

internal fun FileTab.performMove() {
    val selected = getSelectedEntries()
    if (selected.isEmpty()) return
    val otherTab = getOtherPanelTab()
    if (otherTab?.currentVfs?.isReadOnly == true) {
        fileErrorNotification("Cannot move into a read-only archive")
        return
    }
    val destination = otherPanelPathProvider() ?: return
    performMoveEntries(selected, destination)
}

internal fun FileTab.performMoveEntries(selected: List<FileEntry>, destination: Path, destinationDisplayPath: String? = null) {
    if (selected.isEmpty()) return

    val displayPath = destinationDisplayPath ?: getOtherPanelDisplayPath() ?: destination.toString()
    val moveDialog = MoveDialog(project, selected, destination, displayPath)
    if (!moveDialog.showAndGet()) return
    val overwriteAll = moveDialog.overwriteExisting
    val sourcePaths = selected.map { it.path }

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Moving files", true) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            indicator.text = "Counting files..."

            runBlocking {
                val totalFiles = countFiles(sourcePaths)
                indicator.isIndeterminate = false

                fileOps.moveFilesWithProgress(
                    sources = sourcePaths,
                    destination = destination,
                    overwriteAll = overwriteAll,
                    onProgress = { count, name ->
                        indicator.fraction = if (totalFiles > 0) count.toDouble() / totalFiles else 1.0
                        indicator.text = "Moving $count / $totalFiles"
                        indicator.text2 = name
                    },
                    onOverwriteConfirm = { path ->
                        askOverwriteConfirm(path)
                    },
                    onError = { path, error ->
                        fileErrorNotification("Failed to move ${path.fileName}: ${fileErrorMessage(error)}")
                    },
                    isCancelled = { indicator.isCanceled },
                )

                refreshAfterVfsChange()
                withContext(Dispatchers.EDT) {
                    onRefreshOtherPanel()
                }
            }
        }
    })
}

internal fun FileTab.performDelete() {
    val selected = getSelectedEntries()
    if (selected.isEmpty()) return

    val deleteDialog = DeleteDialog(project, selected)
    if (!deleteDialog.showAndGet()) return
    val sourcePaths = selected.map { it.path }

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Deleting files", true) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            indicator.text = "Counting files..."

            runBlocking {
                val totalFiles = countFiles(sourcePaths)
                indicator.isIndeterminate = false

                fileOps.deleteFilesWithProgress(
                    paths = sourcePaths,
                    onProgress = { count, name ->
                        indicator.fraction = if (totalFiles > 0) count.toDouble() / totalFiles else 1.0
                        indicator.text = "Deleting $count / $totalFiles"
                        indicator.text2 = name
                    },
                    onError = { path, error ->
                        fileErrorNotification("Failed to delete ${path.fileName}: ${fileErrorMessage(error)}")
                    },
                    isCancelled = { indicator.isCanceled },
                )

                refreshAfterVfsChange()
            }
        }
    })
}

internal fun FileTab.performCreateDirectory() {
    val name = Messages.showInputDialog(
        project,
        "Enter directory name:",
        "New Directory",
        Messages.getQuestionIcon(),
    )
    if (name.isNullOrBlank()) return

    fileOps.launch {
        try {
            fileOps.createDirectory(currentPath, name)
            if (currentVfs != null) {
                refreshAfterVfsChange(selectName = name)
            } else {
                navigateTo(currentPath, selectName = name)
            }
        } catch (e: Exception) {
            fileErrorNotification("Create directory failed: ${fileErrorMessage(e)}")
        }
    }
}

internal fun FileTab.performCreateFile() {
    val name = Messages.showInputDialog(
        project,
        "Enter file name:",
        "New File",
        Messages.getQuestionIcon(),
    )
    if (name.isNullOrBlank()) return

    fileOps.launch {
        try {
            val filePath = withContext(Dispatchers.IO) {
                Files.createFile(currentPath.resolve(name))
            }
            if (currentVfs != null) {
                refreshAfterVfsChange(selectName = name)
            } else {
                navigateTo(currentPath, selectName = name)
            }
            // Open the created file in editor
            val entryPath = if (currentVfs != null) {
                // After refresh, resolve the file in the (potentially new) VFS path
                currentPath.resolve(name)
            } else {
                filePath
            }
            val entry = FileEntry(name, entryPath, isDirectory = false, size = 0, lastModified = null, permissions = "")
            withContext(Dispatchers.EDT) {
                openFile(entry)
            }
        } catch (e: Exception) {
            fileErrorNotification("Create file failed: ${fileErrorMessage(e)}")
        }
    }
}

internal fun FileTab.performSplitFile() {
    if (isInsideArchive) return
    val entry = getSelectedEntry() ?: return
    if (entry.isDirectory || entry.isParentLink) return

    val otherPath = otherPanelPathProvider()?.toString() ?: currentPath.toString()
    val dialog = SplitFileDialog(project, entry.name, entry.size, otherPath)
    if (!dialog.showAndGet()) return

    val targetDir = Path.of(dialog.targetDirectory)
    try {
        Files.createDirectories(targetDir)
    } catch (e: Exception) {
        fileErrorNotification("Failed to create target directory: ${e.message}")
        return
    }
    val chunkSize = if (dialog.isSplitBySize) {
        dialog.chunkSize
    } else {
        (entry.size + dialog.numberOfParts - 1) / dialog.numberOfParts
    }

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Splitting ${entry.name}", true) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = false
            try {
                SplitFileOperation.split(
                    sourceFile = entry.path,
                    targetDirectory = targetDir,
                    chunkSize = chunkSize,
                    onProgress = { chunkIndex, totalChunks, bytesWritten, totalBytes ->
                        indicator.fraction = bytesWritten.toDouble() / totalBytes
                        indicator.text = "Writing chunk $chunkIndex of $totalChunks"
                        indicator.text2 = "${formatSize(bytesWritten)} / ${formatSize(totalBytes)}"
                    },
                    isCancelled = { indicator.isCanceled },
                )

                if (!indicator.isCanceled) {
                    val totalChunks = if (entry.size == 0L) 1 else ((entry.size + chunkSize - 1) / chunkSize).toInt()
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Turtle Commander")
                        .createNotification(
                            "File split complete",
                            "${entry.name} split into $totalChunks parts",
                            NotificationType.INFORMATION,
                        )
                        .notify(project)
                }
            } catch (e: Exception) {
                if (!indicator.isCanceled) {
                    fileErrorNotification("Failed to split ${entry.name}: ${e.message}")
                }
            }

            runBlocking {
                refreshAfterVfsChange()
                withContext(Dispatchers.EDT) {
                    onRefreshOtherPanel()
                }
            }
        }
    })
}

internal fun FileTab.performCombineFiles() {
    if (isInsideArchive) return
    val entry = getSelectedEntry() ?: return
    if (entry.isDirectory || entry.isParentLink) return

    val directory = entry.path.parent ?: return
    val baseFileName = CombineFilesOperation.resolveBaseFileName(entry.path) ?: return

    val crcFile = directory.resolve("$baseFileName.crc")
    val hasCrc = Files.exists(crcFile)

    val crcInfo = if (hasCrc) {
        try {
            CombineFilesOperation.parseCrcFile(crcFile)
        } catch (e: Exception) {
            fileErrorNotification("Failed to parse CRC file: ${e.message}")
            return
        }
    } else null

    val targetFileName = crcInfo?.filename ?: baseFileName
    val chunkFiles = CombineFilesOperation.findChunkFiles(directory, crcInfo?.filename ?: baseFileName)
    if (chunkFiles.isEmpty()) {
        fileErrorNotification("No chunk files found for $baseFileName")
        return
    }

    val totalSize = chunkFiles.sumOf { Files.size(it) }
    val otherPath = otherPanelPathProvider()?.toString() ?: currentPath.toString()

    val dialog = CombineFilesDialog(project, chunkFiles.size, totalSize, hasCrc, otherPath, targetFileName)
    if (!dialog.showAndGet()) return

    val targetDir = Path.of(dialog.targetDirectory)
    try {
        Files.createDirectories(targetDir)
    } catch (e: Exception) {
        fileErrorNotification("Failed to create target directory: ${e.message}")
        return
    }
    val targetFile = targetDir.resolve(dialog.targetFile)

    if (Files.exists(targetFile)) {
        val result = Messages.showYesNoDialog(
            project,
            "File \"${dialog.targetFile}\" already exists. Overwrite?",
            "Combine Files",
            Messages.getQuestionIcon(),
        )
        if (result != Messages.YES) return
    }

    val finalTargetFileName = dialog.targetFile

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Combining $finalTargetFileName", true) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = false
            try {
                CombineFilesOperation.combine(
                    chunkFiles = chunkFiles,
                    targetFile = targetFile,
                    expectedSize = crcInfo?.size,
                    expectedCrc32 = crcInfo?.crc32,
                    onProgress = { chunkIndex, totalChunks, bytesWritten, totalBytes ->
                        indicator.fraction = bytesWritten.toDouble() / totalBytes
                        indicator.text = "Reading chunk $chunkIndex of $totalChunks"
                        indicator.text2 = "${formatSize(bytesWritten)} / ${formatSize(totalBytes)}"
                    },
                    isCancelled = { indicator.isCanceled },
                )

                if (!indicator.isCanceled) {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Turtle Commander")
                        .createNotification(
                            "File combine complete",
                            "$finalTargetFileName assembled from ${chunkFiles.size} parts" +
                                if (hasCrc) " (CRC verified)" else "",
                            NotificationType.INFORMATION,
                        )
                        .notify(project)
                }
            } catch (e: Exception) {
                if (!indicator.isCanceled) {
                    fileErrorNotification("Failed to combine files: ${e.message}")
                }
            }

            runBlocking {
                refreshAfterVfsChange()
                withContext(Dispatchers.EDT) {
                    onRefreshOtherPanel()
                }
            }
        }
    })
}

internal fun FileTab.performPack() {
    val selected = getSelectedEntries()
    if (selected.isEmpty()) return
    val destination = otherPanelPathProvider() ?: currentPath

    val archiveName = if (selected.size == 1) {
        selected[0].name + ".zip"
    } else {
        "archive.zip"
    }
    val defaultArchivePath = destination.resolve(archiveName).toString()

    val packDialog = PackDialog(project, selected, defaultArchivePath)
    if (!packDialog.showAndGet()) return

    var archivePath = Path.of(packDialog.archivePath)
    if (archivePath.parent == null) {
        archivePath = currentPath.resolve(archivePath)
    }
    val format = packDialog.archiveFormat
    val deleteAfterPacking = packDialog.deleteAfterPacking
    val sourcePaths = selected.map { it.path }

    val archiveExists = Files.exists(archivePath)
    var appendToExisting = false

    if (archiveExists) {
        val options = if (format == ArchiveFormat.ZIP) {
            arrayOf("Overwrite", "Add to Existing", "Cancel")
        } else {
            arrayOf("Overwrite", "Cancel")
        }
        val result = Messages.showDialog(
            project,
            "Archive already exists:\n${archivePath.fileName}\n\nWhat would you like to do?",
            "Archive Exists",
            options,
            0,
            Messages.getQuestionIcon(),
        )
        if (format == ArchiveFormat.ZIP) {
            when (result) {
                0 -> {} // overwrite
                1 -> appendToExisting = true
                else -> return
            }
        } else {
            when (result) {
                0 -> {} // overwrite
                else -> return
            }
        }
    }

    val archiveService = project.service<ArchiveService>()
    val finalArchivePath = archivePath

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Packing files", true) {
        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            indicator.text = "Counting files..."

            runBlocking {
                val totalFiles = countFiles(sourcePaths)
                indicator.isIndeterminate = false

                try {
                    val packedCount = when (format) {
                        ArchiveFormat.ZIP -> archiveService.packZip(
                            finalArchivePath, sourcePaths, appendToExisting, archiveExists,
                            onProgress = { count, name ->
                                indicator.fraction = if (totalFiles > 0) count.toDouble() / totalFiles else 1.0
                                indicator.text = "Packing $count / $totalFiles"
                                indicator.text2 = name
                            },
                            onError = { path, error ->
                                fileErrorNotification("Failed to pack ${path.fileName}: ${fileErrorMessage(error)}")
                            },
                            isCancelled = { indicator.isCanceled },
                        )
                        ArchiveFormat.TAR_GZ -> archiveService.packTarGz(
                            finalArchivePath, sourcePaths,
                            onProgress = { count, name ->
                                indicator.fraction = if (totalFiles > 0) count.toDouble() / totalFiles else 1.0
                                indicator.text = "Packing $count / $totalFiles"
                                indicator.text2 = name
                            },
                            onError = { path, error ->
                                fileErrorNotification("Failed to pack ${path.fileName}: ${fileErrorMessage(error)}")
                            },
                            isCancelled = { indicator.isCanceled },
                        )
                    }

                    if (packedCount == 0 && !appendToExisting) {
                        withContext(Dispatchers.IO) { Files.deleteIfExists(finalArchivePath) }
                    }

                    if (!indicator.isCanceled && packedCount > 0 && deleteAfterPacking) {
                        indicator.isIndeterminate = false
                        indicator.fraction = 0.0
                        indicator.text = "Deleting source files..."

                        fileOps.deleteFilesWithProgress(
                            paths = sourcePaths,
                            onProgress = { count, name ->
                                indicator.fraction = if (totalFiles > 0) count.toDouble() / totalFiles else 1.0
                                indicator.text = "Deleting $count / $totalFiles"
                                indicator.text2 = name
                            },
                            onError = { path, error ->
                                fileErrorNotification("Failed to delete ${path.fileName}: ${fileErrorMessage(error)}")
                            },
                            isCancelled = { indicator.isCanceled },
                        )
                    }
                } catch (e: Exception) {
                    fileErrorNotification("Packing failed: ${fileErrorMessage(e)}")
                }

                val archiveFileName = finalArchivePath.fileName.toString()
                val archiveParent = finalArchivePath.parent
                if (archiveParent != null && archiveParent == currentPath) {
                    refreshAfterVfsChange(selectName = archiveFileName)
                    withContext(Dispatchers.EDT) {
                        onRefreshOtherPanel()
                    }
                } else {
                    refreshAfterVfsChange()
                    val otherPath = otherPanelPathProvider()
                    if (archiveParent != null && otherPath != null && archiveParent == otherPath) {
                        withContext(Dispatchers.EDT) {
                            val svc = stateService ?: return@withContext
                            val activePanel = svc.getActivePanel()
                            val otherPanel = if (activePanel == svc.leftPanel) svc.rightPanel else svc.leftPanel
                            otherPanel?.refreshActiveTab(archiveFileName)
                        }
                    } else {
                        withContext(Dispatchers.EDT) {
                            onRefreshOtherPanel()
                        }
                    }
                }
            }
        }
    })
}

internal fun FileTab.performExtract() {
    val selected = getSelectedEntries().filter { isArchiveFile(it) }
    if (selected.isEmpty()) return
    val destination = otherPanelPathProvider() ?: currentPath

    val dialog = ExtractDialog(project, selected, destination.toString())
    if (!dialog.showAndGet()) return

    var destPath = Path.of(dialog.destinationPath)
    if (!destPath.isAbsolute) {
        destPath = currentPath.resolve(destPath)
    }
    val overwriteAll = dialog.overwriteExisting

    extractArchives(selected.map { it.path }, destPath, overwriteAll)
}

internal fun FileTab.performExtractHere() {
    val selected = getSelectedEntries().filter { isArchiveFile(it) }
    if (selected.isEmpty()) return
    extractArchives(selected.map { it.path }, currentPath, overwriteAll = false)
}

internal fun FileTab.extractArchives(archivePaths: List<Path>, destination: Path, overwriteAll: Boolean) {
    val archiveService = project.service<ArchiveService>()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Extracting files", true) {
        override fun run(indicator: ProgressIndicator) {
            runBlocking {
                for (archivePath in archivePaths) {
                    if (indicator.isCanceled) break

                    indicator.isIndeterminate = true
                    indicator.text = "Counting entries in ${archivePath.fileName}..."

                    val totalEntries = archiveService.countArchiveEntries(archivePath)
                    indicator.isIndeterminate = false

                    archiveService.extractArchiveWithProgress(
                        archivePath = archivePath,
                        destination = destination,
                        overwriteAll = overwriteAll,
                        onProgress = { count, name ->
                            indicator.fraction = if (totalEntries > 0) count.toDouble() / totalEntries else 1.0
                            indicator.text = "Extracting $count / $totalEntries"
                            indicator.text2 = name
                        },
                        onOverwriteConfirm = { path ->
                            askOverwriteConfirm(path)
                        },
                        onError = { path, error ->
                            fileErrorNotification("Failed to extract ${path.fileName}: ${fileErrorMessage(error)}")
                        },
                        isCancelled = { indicator.isCanceled },
                    )
                }

                refreshAfterVfsChange()
                withContext(Dispatchers.EDT) {
                    onRefreshOtherPanel()
                }
            }
        }
    })
}

internal fun FileTab.openInSystemExplorer(entry: FileEntry) {
    val path = if (entry.isDirectory) entry.path else entry.path.parent ?: return
    val os = System.getProperty("os.name").lowercase()
    val command = when {
        os.contains("win") -> arrayOf("explorer.exe", "/select,", entry.path.toString())
        os.contains("mac") -> arrayOf("open", "-R", entry.path.toString())
        else -> arrayOf("xdg-open", path.toString())
    }
    try {
        Runtime.getRuntime().exec(command)
    } catch (e: Exception) {
        fileErrorNotification("Failed to open in explorer: ${fileErrorMessage(e)}")
    }
}

internal fun FileTab.openFile(entry: FileEntry) {
    val vfs = currentVfs
    if (vfs != null) {
        if (vfs.isReadOnly) {
            openVfsFileReadOnly(entry)
        } else {
            openVfsFileEditable(entry)
        }
        return
    }
    fileOps.launch {
        val virtualFile = withContext(Dispatchers.IO) {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry.path)
            vf?.fileType // pre-cache file type detection off EDT
            vf
        } ?: return@launch
        withContext(Dispatchers.EDT) {
            OpenFileDescriptor(project, virtualFile).navigate(true)
        }
    }
}

private fun FileTab.openVfsFileReadOnly(entry: FileEntry) {
    val vfs = currentVfs ?: return
    val isTempDirVfs = vfs !is ZipVirtualFileSystem
    fileOps.launch {
        try {
            val filePath = if (isTempDirVfs) {
                entry.path
            } else {
                val tempDir = withContext(Dispatchers.IO) {
                    Files.createTempDirectory("turtle-vfs-view-")
                }
                val tempPath = tempDir.resolve(entry.path.fileName.toString())
                withContext(Dispatchers.IO) {
                    Files.copy(entry.path, tempPath)
                }
                tempPath
            }
            val virtualFile = withContext(Dispatchers.IO) {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath)
                vf?.fileType
                vf
            } ?: return@launch
            withContext(Dispatchers.EDT) {
                OpenFileDescriptor(project, virtualFile).navigate(true)
            }
        } catch (e: Exception) {
            fileErrorNotification("Failed to open file: ${fileErrorMessage(e)}")
        }
    }
}

private fun FileTab.openVfsFileEditable(entry: FileEntry) {
    if (currentVfs == null) return
    val vfsFilePath = entry.path
    val stackRef = vfsStack.toMutableList()
    fileOps.launch {
        try {
            val tempDir = withContext(Dispatchers.IO) {
                Files.createTempDirectory("turtle-vfs-edit-")
            }
            val tempPath = tempDir.resolve(entry.path.fileName.toString())
            withContext(Dispatchers.IO) {
                Files.copy(entry.path, tempPath)
            }
            val virtualFile = withContext(Dispatchers.IO) {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(tempPath)
                vf?.fileType
                vf
            } ?: return@launch

            val editService = project.service<VfsEditService>()
            editService.trackEdit(VfsEditEntry(vfsFilePath, tempPath, stackRef,
                onBeforeFlush = {
                    val innerVfs = currentVfs ?: return@VfsEditEntry ""
                    vfsRelativePath(innerVfs, currentPath)
                },
                onAfterFlush = { relPath ->
                    val innerVfs = currentVfs ?: return@VfsEditEntry
                    val newPath = if (relPath.isEmpty()) innerVfs.root else innerVfs.root.resolve(relPath)
                    fileOps.launch { navigateTo(newPath) }
                },
            ))

            withContext(Dispatchers.EDT) {
                OpenFileDescriptor(project, virtualFile).navigate(true)
            }
        } catch (e: Exception) {
            fileErrorNotification("Failed to open file: ${fileErrorMessage(e)}")
        }
    }
}

internal fun FileTab.openSelectedInAssociatedApp() {
    val entry = getSelectedEntry() ?: return
    if (entry.isParentLink || entry.isDirectory) return
    val vfs = currentVfs
    if (vfs != null) {
        val isZipVfs = vfs is ZipVirtualFileSystem
        fileOps.launch {
            try {
                val filePath = if (isZipVfs) {
                    val tempDir = withContext(Dispatchers.IO) { Files.createTempDirectory("turtle-vfs-app-") }
                    val tempPath = tempDir.resolve(entry.path.fileName.toString())
                    withContext(Dispatchers.IO) { Files.copy(entry.path, tempPath) }
                    tempPath
                } else {
                    entry.path
                }
                withContext(Dispatchers.EDT) {
                    Desktop.getDesktop().open(filePath.toFile())
                }
            } catch (e: Exception) {
                fileErrorNotification("Failed to open file: ${fileErrorMessage(e)}")
            }
        }
    } else {
        try {
            Desktop.getDesktop().open(entry.path.toFile())
        } catch (e: Exception) {
            fileErrorNotification("Failed to open file: ${fileErrorMessage(e)}")
        }
    }
}

internal fun FileTab.startRename() {
    val entry = getSelectedEntry() ?: return
    if (entry.isParentLink) return
    if (viewMode == ViewMode.TABLE) {
        val row = table.selectedRow
        if (row >= 0) {
            val nameViewCol = (0 until table.columnCount).firstOrNull {
                table.convertColumnIndexToModel(it) == FileTableModel.COL_NAME
            } ?: 0
            table.editCellAt(row, nameViewCol)
        }
    } else {
        val newName = Messages.showInputDialog(
            project,
            "Enter new name:",
            "Rename",
            Messages.getQuestionIcon(),
            entry.name,
            null,
        )
        if (!newName.isNullOrBlank() && newName != entry.name) {
            performRename(entry, newName)
        }
    }
}

internal fun FileTab.performRename(entry: FileEntry, newName: String) {
    fileOps.launch {
        try {
            val vfs = currentVfs
            if (vfs != null) {
                vfs.renameFile(entry.path, newName)
                val relativePath = vfsRelativePath(vfs, currentPath)
                navigateTo(vfs.getPath(relativePath), selectName = newName)
            } else {
                // Rename via IntelliJ VFS if possible, so open editors track the rename
                val vFile = LocalFileSystem.getInstance().findFileByNioFile(entry.path)
                if (vFile != null) {
                    withContext(Dispatchers.EDT) {
                        WriteAction.run<Exception> {
                            vFile.rename(this, newName)
                        }
                    }
                } else {
                    fileOps.renameFile(entry.path, newName)
                }
                navigateTo(currentPath, selectName = newName)
            }
        } catch (e: Exception) {
            fileErrorNotification("Rename failed: ${fileErrorMessage(e)}")
        }
    }
}

internal fun FileTab.viewSelectedFile() {
    val entry = getSelectedEntry() ?: return
    if (!entry.isParentLink && !entry.isDirectory) {
        openFile(entry)
    }
}

private suspend fun askOverwriteConfirm(path: Path): OverwriteResponse {
    return withContext(Dispatchers.EDT) {
        val result = Messages.showDialog(
            null,
            "File already exists:\n${path.fileName}\n\nOverwrite?",
            "File Exists",
            arrayOf("Yes", "No", "Yes to All", "No to All"),
            0,
            Messages.getQuestionIcon(),
        )
        when (result) {
            0 -> OverwriteResponse.YES
            1 -> OverwriteResponse.NO
            2 -> OverwriteResponse.YES_TO_ALL
            3 -> OverwriteResponse.NO_TO_ALL
            else -> OverwriteResponse.NO
        }
    }
}
