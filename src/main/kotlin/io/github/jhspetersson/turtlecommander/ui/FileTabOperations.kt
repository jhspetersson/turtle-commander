package io.github.jhspetersson.turtlecommander.ui
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.keymap.KeymapUtil
import java.nio.file.AccessDeniedException

import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.util.DeleteHandler
import com.intellij.notification.NotificationAction.createSimpleExpiring
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import io.github.jhspetersson.turtlecommander.dialog.*
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.operation.CombineFilesOperation
import io.github.jhspetersson.turtlecommander.operation.SplitFileOperation
import io.github.jhspetersson.turtlecommander.service.ArchiveService
import io.github.jhspetersson.turtlecommander.service.OverwritePolicy
import io.github.jhspetersson.turtlecommander.service.OverwriteResponse
import io.github.jhspetersson.turtlecommander.service.VfsTempCleanup
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.util.countFiles
import io.github.jhspetersson.turtlecommander.util.fileErrorMessage
import io.github.jhspetersson.turtlecommander.util.formatSize
import io.github.jhspetersson.turtlecommander.util.withIndicatorProgress
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry
import io.github.jhspetersson.turtlecommander.vfs.VfsEditEntry
import io.github.jhspetersson.turtlecommander.vfs.VfsEditService
import io.github.jhspetersson.turtlecommander.vfs.VfsOpenProgress
import io.github.jhspetersson.turtlecommander.vfs.ZipVirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    val destination = if (otherTab?.isInsideArchive == true) otherTab.currentPath else otherPanelPathProvider() ?: return
    performCopyEntries(getSelectedEntries(), destination)
}

internal fun FileTab.performCopyEntries(selected: List<FileEntry>, destination: Path, destinationDisplayPath: String? = null) {
    if (selected.isEmpty()) return
    val displayPath = destinationDisplayPath ?: getOtherPanelDisplayPath() ?: destination.toString()
    val dialog = TransferDialog(project, "Copy", selected, destination, displayPath)
    if (!dialog.showAndGet()) return
    runTransfer(
        sources = selected.map { it.path },
        destination = dialog.targetDirectory,
        initialPolicy = dialog.policy,
        taskTitle = "Copying files",
        progressVerb = "Copying",
        errorVerb = "copy",
        op = fileOps::copyFilesWithProgress,
    )
}

internal fun FileTab.performMove() {
    val selected = getSelectedEntries()
    if (selected.isEmpty()) return
    val otherTab = getOtherPanelTab()
    if (otherTab?.currentVfs?.isReadOnly == true) {
        fileErrorNotification("Cannot move into a read-only archive")
        return
    }
    val destination = if (otherTab?.isInsideArchive == true) otherTab.currentPath else otherPanelPathProvider() ?: return
    performMoveEntries(selected, destination)
}

internal fun FileTab.performMoveEntries(selected: List<FileEntry>, destination: Path, destinationDisplayPath: String? = null) {
    if (selected.isEmpty()) return
    val displayPath = destinationDisplayPath ?: getOtherPanelDisplayPath() ?: destination.toString()
    val dialog = TransferDialog(project, "Move", selected, destination, displayPath)
    if (!dialog.showAndGet()) return
    runTransfer(
        sources = selected.map { it.path },
        destination = dialog.targetDirectory,
        initialPolicy = dialog.policy,
        taskTitle = "Moving files",
        progressVerb = "Moving",
        errorVerb = "move",
        op = fileOps::moveFilesWithProgress,
    )
}

/**
 * Shape that `FileOperationService.copyFilesWithProgress` and `moveFilesWithProgress` share —
 * the only thing that differs between copy and move at this layer is which method gets called.
 * Extracting the alias keeps [runTransfer]'s signature readable.
 */
private typealias TransferOp = suspend (
    sources: List<Path>,
    destination: Path,
    initialPolicy: OverwritePolicy,
    onProgress: suspend (Int, String) -> Unit,
    onOverwriteConfirm: suspend (Path) -> OverwriteResponse,
    onError: suspend (Path, Exception) -> Unit,
    isCancelled: () -> Boolean,
) -> Unit

/**
 * Shared background-progress driver for copy and move: count the files, kick off [op] with a
 * progress-bound `onProgress`, wire the per-file overwrite prompt, surface errors as notifications,
 * and refresh both panels when done. Caller picks the dialog, the operation, and the user-facing
 * verbs ([taskTitle] for the task header, [progressVerb] for the bar text, [errorVerb] for the
 * "Failed to … foo.txt" notification).
 *
 * Cancellation contract (shared by every operation in this file, matching the historical
 * `Task.Backgroundable` behavior): the cancel button is observed through the polled
 * `isCancelled` lambda, so the operation finishes its in-flight file and still runs cache
 * invalidation and the panel refresh. The `NonCancellable` wrapper keeps the
 * CancellationException that would otherwise fire at the next suspension point from
 * skipping that graceful path — the captured [kotlinx.coroutines.Job] still observes the
 * cancel request (including the one triggered by project close cancelling the service scope).
 */
private fun FileTab.runTransfer(
    sources: List<Path>,
    destination: Path,
    initialPolicy: OverwritePolicy,
    taskTitle: String,
    progressVerb: String,
    errorVerb: String,
    op: TransferOp,
) {
    fileOps.launch {
        withIndicatorProgress(project, taskTitle) { indicator ->
            withContext(NonCancellable) {
                indicator.text = "Counting files..."
                val totalFiles = countFiles(sources)
                indicator.isIndeterminate = false

                op(
                    sources,
                    destination,
                    initialPolicy,
                    { count, name ->
                        indicator.fraction = if (totalFiles > 0) count.toDouble() / totalFiles else 1.0
                        indicator.text = "$progressVerb $count / $totalFiles"
                        indicator.text2 = name
                    },
                    { path -> askOverwriteConfirm(path) },
                    { path, error ->
                        fileErrorNotification("Failed to $errorVerb ${path.fileName}: ${fileErrorMessage(error)}")
                    },
                    { indicator.isCanceled },
                )

                refreshAfterVfsChange()
                withContext(Dispatchers.EDT) {
                    onRefreshOtherPanel()
                }
            }
        }
    }
}

internal fun FileTab.performDelete(forcePermanent: Boolean = false) {
    val selected = getSelectedEntries()
    if (selected.isEmpty()) return

    // Shift+DELETE (forcePermanent) always does an irreversible delete, bypassing the
    // recycle-bin setting. Otherwise honor the user's preference — but only when the
    // platform actually has a trash: otherwise the user must knowingly confirm a
    // permanent delete instead of getting the reversible one they asked for silently
    // downgraded.
    val wantsRecycleBin = !forcePermanent && TurtleCommanderSettings.getInstance().state.deleteToRecycleBin
    val useRecycleBin = wantsRecycleBin && fileOps.isRecycleBinAvailable()

    // IDE's DeleteHandler performs a permanent delete with its own UX; we only hand off to it
    // when the user expects a permanent delete (recycle-bin mode has its own dialog + service
    // path so we keep it consistent with VFS-side deletes).
    if (!useRecycleBin && currentVfs == null && tryIdeaDelete(selected)) return

    val downgradedFromRecycleBin = wantsRecycleBin && !useRecycleBin
    val deleteDialog = DeleteDialog(
        project, selected, useRecycleBin = useRecycleBin,
        note = if (downgradedFromRecycleBin) {
            "Recycle Bin is not available on this system — items will be deleted permanently."
        } else {
            null
        },
        checkboxText = if (downgradedFromRecycleBin) {
            "Turn off \"Delete to Recycle Bin\" and don't show this note again"
        } else {
            null
        },
    )
    if (!deleteDialog.showAndGet()) return
    if (downgradedFromRecycleBin && deleteDialog.isCheckboxSelected) {
        TurtleCommanderSettings.getInstance().state.deleteToRecycleBin = false
    }
    val sourcePaths = selected.map { it.path }

    val taskTitle = if (useRecycleBin) "Moving files to Recycle Bin" else "Deleting files"
    val progressVerb = if (useRecycleBin) "Moving" else "Deleting"
    fileOps.launch {
        withIndicatorProgress(project, taskTitle) { indicator ->
            withContext(NonCancellable) {
                // Recycle-bin mode moves each top-level path to the trash in a single
                // OS call and the service ticks once per path, so the denominator is
                // the path count — countFiles' recursive total would leave the bar
                // stuck near zero, and the tree walk itself would be wasted work.
                val totalFiles = if (useRecycleBin) {
                    sourcePaths.size
                } else {
                    indicator.text = "Counting files..."
                    countFiles(sourcePaths)
                }
                indicator.isIndeterminate = false

                fileOps.deleteFilesWithProgress(
                    paths = sourcePaths,
                    onProgress = { count, name ->
                        indicator.fraction = if (totalFiles > 0) count.toDouble() / totalFiles else 1.0
                        indicator.text = "$progressVerb $count / $totalFiles"
                        indicator.text2 = name
                    },
                    onError = { path, error ->
                        fileErrorNotification("Failed to delete ${path.fileName}: ${fileErrorMessage(error)}")
                    },
                    isCancelled = { indicator.isCanceled },
                    useRecycleBin = useRecycleBin,
                )

                refreshAfterVfsChange()
                withContext(Dispatchers.EDT) {
                    onRefreshOtherPanel()
                }
            }
        }
    }
}

internal fun allUnderProjectBase(paths: List<Path>, basePathStr: String?): Boolean {
    val basePath = basePathStr?.let {
        runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull()
    } ?: return false
    return paths.isNotEmpty() && paths.all { p ->
        runCatching { p.toAbsolutePath().normalize().startsWith(basePath) }.getOrDefault(false)
    }
}

private fun FileTab.tryIdeaDelete(selected: List<FileEntry>): Boolean {
    if (!allUnderProjectBase(selected.map { it.path }, project.basePath)) return false

    val lfs = LocalFileSystem.getInstance()
    val psiManager = PsiManager.getInstance(project)
    val elements = mutableListOf<PsiElement>()
    for (entry in selected) {
        val vf = lfs.refreshAndFindFileByNioFile(entry.path) ?: return false
        val psi = if (vf.isDirectory) psiManager.findDirectory(vf) else psiManager.findFile(vf)
        elements.add(psi ?: return false)
    }

    DeleteHandler.deletePsiElement(elements.toTypedArray(), project)

    fileOps.launch {
        refreshAfterVfsChange()
        withContext(Dispatchers.EDT) { onRefreshOtherPanel() }
    }
    return true
}

internal fun FileTab.performCreateDirectory() {
    val dialog = InputDialog(project, "New Directory", "Enter directory name:")
    if (!dialog.showAndGet()) return
    val name = dialog.inputValue
    if (name.isBlank()) return

    fileOps.launch {
        try {
            fileOps.createDirectory(currentPath, name)
            if (currentVfs != null) {
                refreshAfterVfsChange(selectName = name)
            } else {
                invalidateFreeSpaceCache()
                navigateTo(currentPath, selectName = name)
            }
            withContext(Dispatchers.EDT) {
                onRefreshOtherPanel()
            }
        } catch (e: Exception) {
            fileErrorNotification("Create directory failed: ${fileErrorMessage(e)}")
        }
    }
}

/**
 * Dual-pane link creation: the active panel's selection becomes symbolic or hard links (per the
 * dialog's choice) in the opposite panel's directory (the orthodox "link in other panel" idiom).
 * Links are created off the EDT; afterwards the other panel is refreshed and the new link selected,
 * without stealing focus from the active panel.
 */
internal fun FileTab.performCreateLink() {
    if (isInsideArchive) {
        fileErrorNotification("Cannot create symbolic links inside an archive")
        return
    }
    val selected = getSelectedEntries().filter { !it.isParentLink }
    if (selected.isEmpty()) return
    val otherTab = getOtherPanelTab()
    if (otherTab?.currentVfs != null) {
        fileErrorNotification("Cannot create symbolic links inside an archive")
        return
    }
    val destination = otherPanelPathProvider() ?: return
    val displayPath = getOtherPanelDisplayPath() ?: destination.toString()

    val dialog = CreateLinkDialog(project, selected, destination, displayPath)
    if (!dialog.showAndGet()) return
    val specs = dialog.specs
    if (specs.isEmpty()) return

    fileOps.launch {
        var failures = 0
        for (spec in specs) {
            try {
                if (spec.hard) {
                    fileOps.createHardLink(spec.link, spec.target)
                } else {
                    fileOps.createSymbolicLink(spec.link, spec.target)
                }
            } catch (e: Exception) {
                failures++
                fileErrorNotification(symlinkErrorMessage(spec.link, spec.hard, e))
            }
        }
        if (failures < specs.size) {
            val target = getOtherPanelTab()
            if (target != null) {
                val selectName = if (specs.size == 1) specs[0].link.fileName?.toString() else null
                target.navigateTo(target.currentPath, selectName = selectName, requestFocus = false)
            } else {
                withContext(Dispatchers.EDT) { onRefreshOtherPanel() }
            }
        }
    }
}

private fun symlinkErrorMessage(link: Path, hard: Boolean, e: Exception): String {
    val privilege = e is AccessDeniedException ||
        e.message?.contains("privilege", ignoreCase = true) == true
    return if (privilege && !hard) {
        "Creating symbolic links requires Windows Developer Mode or administrator rights."
    } else {
        "Failed to create link ${link.fileName}: ${fileErrorMessage(e)}"
    }
}

internal fun FileTab.performCreateFile() {
    val dialog = InputDialog(project, "New File", "Enter file name:")
    if (!dialog.showAndGet()) return
    val name = dialog.inputValue
    if (name.isBlank()) return

    fileOps.launch {
        try {
            val filePath = withContext(Dispatchers.IO) {
                Files.createFile(currentPath.resolve(name))
            }
            fileOps.invalidateListingCache(currentPath)
            if (currentVfs != null) {
                refreshAfterVfsChange(selectName = name)
            } else {
                invalidateFreeSpaceCache()
                navigateTo(currentPath, selectName = name)
            }
            withContext(Dispatchers.EDT) {
                onRefreshOtherPanel()
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
    val chunkSize = if (dialog.isSplitBySize) {
        dialog.chunkSize
    } else {
        (entry.size + dialog.numberOfParts - 1) / dialog.numberOfParts
    }

    fileOps.launch {
        try {
            withContext(Dispatchers.IO) { Files.createDirectories(targetDir) }
        } catch (e: Exception) {
            fileErrorNotification("Failed to create target directory: ${e.message}")
            return@launch
        }
        withIndicatorProgress(project, "Splitting ${entry.name}") { indicator ->
            withContext(NonCancellable) {
                indicator.isIndeterminate = false
                try {
                    withContext(Dispatchers.IO) {
                        SplitFileOperation.split(
                            sourceFile = entry.path,
                            targetDirectory = targetDir,
                            chunkSize = chunkSize,
                            onProgress = { chunkIndex, totalChunks, bytesWritten, totalBytes ->
                                indicator.fraction = if (totalBytes > 0) bytesWritten.toDouble() / totalBytes else 1.0
                                indicator.text = "Writing chunk $chunkIndex of $totalChunks"
                                indicator.text2 = "${formatSize(bytesWritten)} / ${formatSize(totalBytes)}"
                            },
                            isCancelled = { indicator.isCanceled },
                        )
                    }

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

                // Chunks + .crc file are written into targetDir; invalidate so
                // whichever panel lands on it doesn't serve a stale listing.
                fileOps.invalidateListingCache(targetDir)
                refreshAfterVfsChange()
                withContext(Dispatchers.EDT) {
                    onRefreshOtherPanel()
                }
            }
        }
    }
}

internal fun FileTab.performCombineFiles() {
    if (isInsideArchive) return
    val entry = getSelectedEntry() ?: return
    if (entry.isDirectory || entry.isParentLink) return

    val directory = entry.path.parent ?: return
    val baseFileName = CombineFilesOperation.resolveBaseFileName(entry.path) ?: return
    val otherPath = otherPanelPathProvider()?.toString() ?: currentPath.toString()

    fileOps.launch {
        // The chunk scan probes Files.exists per index and stats every chunk for its size —
        // thousands of calls for a finely-split file — so the whole discovery runs off the
        // EDT, hopping back only for the dialogs.
        val crcFile = directory.resolve("$baseFileName.crc")
        val hasCrc = withContext(Dispatchers.IO) { Files.exists(crcFile) }

        val crcInfo = if (hasCrc) {
            try {
                withContext(Dispatchers.IO) { CombineFilesOperation.parseCrcFile(crcFile) }
            } catch (e: Exception) {
                fileErrorNotification("Failed to parse CRC file: ${e.message}")
                return@launch
            }
        } else null

        val targetFileName = crcInfo?.filename ?: baseFileName
        val chunkFiles = withContext(Dispatchers.IO) {
            CombineFilesOperation.findChunkFiles(directory, crcInfo?.filename ?: baseFileName)
        }
        if (chunkFiles.isEmpty()) {
            fileErrorNotification("No chunk files found for $baseFileName")
            return@launch
        }

        val totalSize = withContext(Dispatchers.IO) { chunkFiles.sumOf { Files.size(it) } }

        val dialogChoice = withContext(Dispatchers.EDT) {
            val dialog = CombineFilesDialog(project, chunkFiles.size, totalSize, hasCrc, otherPath, targetFileName)
            if (dialog.showAndGet()) dialog.targetDirectory to dialog.targetFile else null
        } ?: return@launch
        val (targetDirString, finalTargetFileName) = dialogChoice

        val targetDir = Path.of(targetDirString)
        try {
            withContext(Dispatchers.IO) { Files.createDirectories(targetDir) }
        } catch (e: Exception) {
            fileErrorNotification("Failed to create target directory: ${e.message}")
            return@launch
        }
        val targetFile = targetDir.resolve(finalTargetFileName)

        if (withContext(Dispatchers.IO) { Files.exists(targetFile) }) {
            val overwrite = withContext(Dispatchers.EDT) {
                Messages.showYesNoDialog(
                    project,
                    "File \"$finalTargetFileName\" already exists. Overwrite?",
                    "Combine Files",
                    Messages.getQuestionIcon(),
                ) == Messages.YES
            }
            if (!overwrite) return@launch
        }

        withIndicatorProgress(project, "Combining $finalTargetFileName") { indicator ->
            withContext(NonCancellable) {
                indicator.isIndeterminate = false
                try {
                    withContext(Dispatchers.IO) {
                        CombineFilesOperation.combine(
                            chunkFiles = chunkFiles,
                            targetFile = targetFile,
                            expectedSize = crcInfo?.size,
                            expectedCrc32 = crcInfo?.crc32,
                            onProgress = { chunkIndex, totalChunks, bytesWritten, totalBytes ->
                                indicator.fraction = if (totalBytes > 0) bytesWritten.toDouble() / totalBytes else 1.0
                                indicator.text = "Reading chunk $chunkIndex of $totalChunks"
                                indicator.text2 = "${formatSize(bytesWritten)} / ${formatSize(totalBytes)}"
                            },
                            isCancelled = { indicator.isCanceled },
                        )
                    }

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

                // The combined file is created in targetDir — invalidate so
                // either panel sees it on refresh.
                fileOps.invalidateListingCache(targetDir)
                refreshAfterVfsChange()
                withContext(Dispatchers.EDT) {
                    onRefreshOtherPanel()
                }
            }
        }
    }
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

    val archiveService = project.service<ArchiveService>()
    val finalArchivePath = archivePath

    fileOps.launch {
        // The exists probe can block on a network destination — stat off the EDT,
        // hop back only to show the overwrite/append prompt.
        val archiveExists = withContext(Dispatchers.IO) { Files.exists(finalArchivePath) }
        var appendToExisting = false

        if (archiveExists) {
            val action = withContext(Dispatchers.EDT) {
                askArchiveExistsAction(project, finalArchivePath, format)
            }
            when (action) {
                ArchiveExistsAction.OVERWRITE -> {}
                ArchiveExistsAction.APPEND -> appendToExisting = true
                ArchiveExistsAction.CANCEL -> return@launch
            }
        }

        withIndicatorProgress(project, "Packing files") { indicator ->
            withContext(NonCancellable) {
                indicator.text = "Counting files..."
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
                // The archive is created (or updated) inside archiveParent; neither
                // refreshAfterVfsChange nor onRefreshOtherPanel invalidates the
                // listing cache for that directory.
                if (archiveParent != null) fileOps.invalidateListingCache(archiveParent)
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
                            otherPanel?.refreshActiveTab(archiveFileName, requestFocus = false)
                        }
                    } else {
                        withContext(Dispatchers.EDT) {
                            onRefreshOtherPanel()
                        }
                    }
                }
            }
        }
    }
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

    extractArchives(selected.map { it.path }, destPath, dialog.policy)
}

internal fun FileTab.performExtractHere() {
    val selected = getSelectedEntries().filter { isArchiveFile(it) }
    if (selected.isEmpty()) return
    extractArchives(selected.map { it.path }, currentPath, OverwritePolicy.ASK)
}

internal fun FileTab.performExtractToSubdir() {
    val selected = getSelectedEntries().filter { isArchiveFile(it) }
    if (selected.isEmpty()) return
    val subdir = archiveBaseName(selected.first().name)
    val destination = currentPath.resolve(subdir)

    val dialog = ExtractDialog(project, selected, destination.toString())
    if (!dialog.showAndGet()) return

    var destPath = Path.of(dialog.destinationPath)
    if (!destPath.isAbsolute) {
        destPath = currentPath.resolve(destPath)
    }

    extractArchives(selected.map { it.path }, destPath, dialog.policy)
}

/**
 * Returns the archive name with its extension stripped, handling the common compound
 * forms (`foo.tar.gz` → `foo`, `foo.tar.bz2` → `foo`, `foo.tar.xz` → `foo`) as well as
 * single-extension archives (`foo.zip` → `foo`, `foo.tgz` → `foo`). The result is what
 * "Extract to Subdir" offers as the default subdirectory name.
 */
internal fun archiveBaseName(archiveName: String): String {
    val afterLast = archiveName.substringBeforeLast('.', archiveName)
    if (afterLast == archiveName) return archiveName
    return if (afterLast.endsWith(".tar", ignoreCase = true)) {
        afterLast.substringBeforeLast('.', afterLast)
    } else {
        afterLast
    }
}

internal fun FileTab.extractArchives(archivePaths: List<Path>, destination: Path, initialPolicy: OverwritePolicy) {
    val archiveService = project.service<ArchiveService>()

    fileOps.launch {
        withIndicatorProgress(project, "Extracting files") { indicator ->
            withContext(NonCancellable) {
                // Reports (and allows cancelling) the temp-dir extraction that formats
                // without a header-only counter (.rar, .iso, corrupt .zip) perform when
                // their VFS is opened — work that used to be visible only through the
                // thread-bound global indicator of the old Task.
                val openProgress = VfsOpenProgress.fromIndicator(indicator)

                for (archivePath in archivePaths) {
                    if (indicator.isCanceled) break

                    indicator.isIndeterminate = true
                    indicator.text = "Counting entries in ${archivePath.fileName}..."

                    val totalEntries = archiveService.countArchiveEntries(archivePath, openProgress)
                    indicator.isIndeterminate = false

                    archiveService.extractArchiveWithProgress(
                        archivePath = archivePath,
                        destination = destination,
                        initialPolicy = initialPolicy,
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
                        openProgress = openProgress,
                    )
                }

                // Extract writes into `destination`; invalidate it so the
                // refreshed panel shows the new entries.
                fileOps.invalidateListingCache(destination)
                refreshAfterVfsChange()
                withContext(Dispatchers.EDT) {
                    onRefreshOtherPanel()
                }
            }
        }
    }
}

internal fun openInSystemExplorer(entry: FileEntry) {
    RevealFileAction.openFile(entry.path)
}

internal fun openDirectoryInSystemExplorer(path: Path) {
    RevealFileAction.openDirectory(path)
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

/**
 * Copy a VFS-internal file out to a fresh local temp directory so external code
 * (LocalFileSystem-backed editors, the OS file association handler) can see a
 * real path. Marks both the temp file and its parent dir for delete-on-exit and
 * runs the periodic stale-temp sweep so abandoned previous extractions don't
 * accumulate. [prefix] disambiguates the dir name purely for forensic use when
 * a leak shows up in /var/folders or %TEMP%.
 */
private suspend fun materializeVfsEntryToTemp(entry: FileEntry, prefix: String): Path =
    withContext(Dispatchers.IO) {
        VfsTempCleanup.cleanupOnce()
        // Pull bytes from the source archive into the stub first if this VFS does lazy
        // materialization (currently just .iso) — otherwise Files.copy below sees an empty
        // sparse file and the user opens a zero-byte view of their file.
        OpenVfsRegistry.materializeIfNeeded(entry.path)
        val tempDir = Files.createTempDirectory(prefix)
        val tempPath = tempDir.resolve(entry.path.fileName.toString())
        Files.copy(entry.path, tempPath)
        tempPath.toFile().deleteOnExit()
        tempDir.toFile().deleteOnExit()
        tempPath
    }

private fun FileTab.openVfsFileReadOnly(entry: FileEntry) {
    val vfs = currentVfs ?: return
    val isTempDirVfs = vfs !is ZipVirtualFileSystem
    fileOps.launch {
        try {
            val filePath = if (isTempDirVfs) {
                // Force a lazy VFS to stream this file's bytes onto the stub before the
                // editor opens it. No-op for fully-materialised VFSs (Tar/Crx/Rpm/Pak/Ar).
                withContext(Dispatchers.IO) { vfs.materialize(entry.path) }
                entry.path
            } else {
                materializeVfsEntryToTemp(entry, "turtle-vfs-view-")
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
            val tempPath = materializeVfsEntryToTemp(entry, "turtle-vfs-edit-")
            val virtualFile = withContext(Dispatchers.IO) {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(tempPath)
                vf?.fileType
                vf
            } ?: return@launch

            val editService = project.service<VfsEditService>()
            editService.trackEdit(VfsEditEntry(vfsFilePath, tempPath, stackRef,
                vfsWriteMutex = vfsWriteMutex,
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
                    materializeVfsEntryToTemp(entry, "turtle-vfs-app-")
                } else {
                    entry.path
                }
                // Desktop.open resolves the file association and launches the handler
                // synchronously — the JDK explicitly warns against calling it on the EDT.
                withContext(Dispatchers.IO) {
                    Desktop.getDesktop().open(filePath.toFile())
                }
            } catch (e: Exception) {
                fileErrorNotification("Failed to open file: ${fileErrorMessage(e)}")
            }
        }
    } else {
        fileOps.launch {
            try {
                withContext(Dispatchers.IO) {
                    Desktop.getDesktop().open(entry.path.toFile())
                }
            } catch (e: Exception) {
                fileErrorNotification("Failed to open file: ${fileErrorMessage(e)}")
            }
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
        val renameDialog = InputDialog(project, "Rename", "Enter new name:", entry.name)
        if (!renameDialog.showAndGet()) return
        val newName = renameDialog.inputValue
        if (newName.isNotBlank() && newName != entry.name) {
            performRename(entry, newName)
        }
    }
}

internal fun FileTab.performRename(entry: FileEntry, newName: String) {
    fileOps.launch {
        try {
            val vfs = currentVfs
            if (vfs != null) {
                if (vfs.isReadOnly) {
                    fileErrorNotification("Cannot rename in a read-only archive")
                    return@launch
                }
                vfs.renameFile(entry.path, newName)
                val relativePath = vfsRelativePath(vfs, currentPath)
                val newPath = if (relativePath.isEmpty()) vfs.root else vfs.root.resolve(relativePath)
                navigateTo(newPath, selectName = newName)
            } else {
                // Rename via IntelliJ VFS if possible, so open editors track the rename
                val vFile = LocalFileSystem.getInstance().findFileByNioFile(entry.path)
                if (vFile != null) {
                    withContext(Dispatchers.EDT) {
                        WriteAction.run<Exception> {
                            vFile.rename(this, newName)
                        }
                    }
                    // FileOperationService.renameFile() invalidates the listing cache
                    // itself; the VFS rename path bypasses it, so do it here.
                    entry.path.parent?.let { fileOps.invalidateListingCache(it) }
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

/**
 * Stores the last multi-rename as (originalPath, finalPath) pairs so Undo can reverse it.
 * Undo is a per-plugin-process single slot — rerunning multi-rename overwrites the slot.
 * Stored on the object, not per-tab, so undoing works regardless of which panel is focused
 * (the user might switch tabs between the rename and the undo).
 */
internal object MultiRenameUndo {
    @Volatile
    var lastBatch: List<Pair<Path, Path>>? = null
}

internal fun FileTab.performMultiRename() {
    if (currentVfs?.isReadOnly == true) {
        fileErrorNotification("Cannot rename inside a read-only archive")
        return
    }
    val selected = getSelectedEntries().filter { !it.isParentLink }
    val targets = selected.ifEmpty {
        // When nothing is selected, default to every non-parent entry in the current view.
        (0 until tableModel.rowCount).mapNotNull { tableModel.getEntryAt(it) }.filter { !it.isParentLink }
    }
    if (targets.isEmpty()) {
        fileErrorNotification("Nothing to rename")
        return
    }
    val dialog = MultiRenameDialog(project, targets)
    if (!dialog.showAndGet()) return
    val pairs = dialog.result?.pairs.orEmpty()
    if (pairs.isEmpty()) return
    runMultiRename(pairs)
}

private fun FileTab.runMultiRename(pairs: List<Pair<FileEntry, String>>) {
    fileOps.launch {
        // Two-phase rename to survive cyclic swaps like a↔b: first move every source to a
        // uniquely-named temp file, then move each temp to its final target. Without this,
        // renaming a→b when b also needs renaming would collide on the first move.
        val completed = mutableListOf<Pair<Path, Path>>()
        val temps = mutableListOf<Triple<Path, Path, String>>() // original, tempPath, finalName
        try {
            withContext(Dispatchers.IO) {
                for ((entry, finalName) in pairs) {
                    val parent = entry.path.parent ?: continue
                    val temp = uniqueTemp(parent, entry.path.fileName.toString())
                    Files.move(entry.path, temp)
                    temps.add(Triple(entry.path, temp, finalName))
                }
                for ((original, temp, finalName) in temps) {
                    val finalPath = temp.resolveSibling(finalName)
                    Files.move(temp, finalPath)
                    completed.add(original to finalPath)
                }
            }
            completed.mapNotNull { it.first.parent }.toSet().forEach { fileOps.invalidateListingCache(it) }
            MultiRenameUndo.lastBatch = completed
            withContext(Dispatchers.EDT) {
                val tab = this@runMultiRename
                // Look up the live shortcut for the Undo action so the hint stays correct if
                // the user has rebound it. Empty when the user has cleared the binding.
                val undoAction = ActionManager.getInstance()
                    .getAction("TurtleCommander.MultiRenameUndo")
                val shortcut = undoAction?.let {
                    KeymapUtil.getFirstKeyboardShortcutText(it)
                }.orEmpty()
                val hint = if (shortcut.isNotEmpty()) " Press $shortcut to undo." else ""
                val content = "Renamed ${completed.size} file(s).$hint"
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Turtle Commander")
                    .createNotification(content, NotificationType.INFORMATION)
                    .addAction(createSimpleExpiring("Undo") {
                        tab.performMultiRenameUndo()
                    })
                    .notify(project)
            }
            navigateTo(currentPath)
        } catch (e: Exception) {
            // Best-effort rollback of any temps we've already created.
            withContext(Dispatchers.IO) {
                for ((original, temp, _) in temps) {
                    if (original !in completed.map { it.first } && Files.exists(temp)) {
                        try { Files.move(temp, original) } catch (_: Exception) {}
                    }
                }
            }
            fileErrorNotification("Multi-rename failed: ${fileErrorMessage(e)}")
        }
    }
}

internal fun FileTab.performMultiRenameUndo() {
    val batch = MultiRenameUndo.lastBatch
    if (batch.isNullOrEmpty()) {
        fileErrorNotification("Nothing to undo")
        return
    }
    fileOps.launch {
        try {
            // Reverse via the same two-phase trick.
            withContext(Dispatchers.IO) {
                val temps = mutableListOf<Triple<Path, Path, Path>>() // final, temp, original
                for ((original, current) in batch) {
                    if (!Files.exists(current)) continue
                    val parent = current.parent ?: continue
                    val temp = uniqueTemp(parent, current.fileName.toString())
                    Files.move(current, temp)
                    temps.add(Triple(current, temp, original))
                }
                for ((_, temp, original) in temps) {
                    Files.move(temp, original)
                }
            }
            (batch.mapNotNull { it.first.parent } + batch.mapNotNull { it.second.parent })
                .toSet()
                .forEach { fileOps.invalidateListingCache(it) }
            MultiRenameUndo.lastBatch = null
            navigateTo(currentPath)
        } catch (e: Exception) {
            fileErrorNotification("Undo failed: ${fileErrorMessage(e)}")
        }
    }
}

private fun uniqueTemp(parent: Path, seed: String): Path {
    var i = 0
    while (true) {
        val candidate = parent.resolve(".tc-multirename-$seed.$i.tmp")
        if (!Files.exists(candidate)) return candidate
        i++
    }
}

/**
 * Outcome of the "archive already exists" prompt in [performPack]. Distinct from the
 * raw button index so the dispatch in performPack reads as a typed three-way decision
 * regardless of which format-specific button set was actually shown.
 */
private enum class ArchiveExistsAction { OVERWRITE, APPEND, CANCEL }

/**
 * Show the "archive already exists" dialog and translate the chosen button into
 * an [ArchiveExistsAction]. Append is only offered for formats that support it
 * (currently ZIP); for other formats the dialog is reduced to Overwrite / Cancel
 * and "1" maps to Cancel rather than Append.
 */
private fun askArchiveExistsAction(
    project: Project,
    archivePath: Path,
    format: ArchiveFormat,
): ArchiveExistsAction {
    val supportsAppend = format == ArchiveFormat.ZIP
    val options = if (supportsAppend) {
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
    return when (result) {
        0 -> ArchiveExistsAction.OVERWRITE
        1 if supportsAppend -> ArchiveExistsAction.APPEND
        else -> ArchiveExistsAction.CANCEL
    }
}

private suspend fun askOverwriteConfirm(path: Path): OverwriteResponse {
    return withContext(Dispatchers.EDT) {
        // Order matches the array index returned by Messages.showDialog. Messages.CANCEL_BUTTON
        // (Esc / X) maps to the same OverwriteResponse.CANCEL slot at index 6 so the user can't
        // accidentally fall into a stale default.
        val labels = arrayOf(
            "Overwrite",
            "Skip",
            "Overwrite All",
            "Skip All",
            "Overwrite if Older",
            "Overwrite All Older",
            "Cancel",
        )
        val result = Messages.showDialog(
            null,
            "File already exists:\n${path.fileName}\n\nWhat would you like to do?",
            "File Exists",
            labels,
            0,
            Messages.getQuestionIcon(),
        )
        when (result) {
            0 -> OverwriteResponse.OVERWRITE
            1 -> OverwriteResponse.SKIP
            2 -> OverwriteResponse.OVERWRITE_ALL
            3 -> OverwriteResponse.SKIP_ALL
            4 -> OverwriteResponse.OVERWRITE_IF_OLDER
            5 -> OverwriteResponse.OVERWRITE_ALL_IF_OLDER
            else -> OverwriteResponse.CANCEL
        }
    }
}
