package io.github.jhspetersson.turtlecommander.action

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.jhspetersson.turtlecommander.dialog.FileSearchDialog
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.ui.*

object FileContextMenuState {
    var clickedEntry: FileEntry? = null
    var clickedTab: FileTab? = null
}

object FileCopyBuffer {
    var entries: List<FileEntry> = emptyList()
        set(value) {
            field = value
            listeners.forEach { it() }
        }
    var isCut: Boolean = false

    private val listeners = mutableListOf<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }
}

class ContextSearchInDirectoryAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = entry != null && entry.isDirectory && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry ?: return
        val project = e.project ?: return
        val dialog = FileSearchDialog(project, entry.path)
        if (!dialog.showAndGet()) return
        val criteria = dialog.getCriteria()
        val stateService = project.service<FileManagerStateService>()
        stateService.getActivePanel()?.openSearchTab(criteria)
    }
}

class OpenInNewTabAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = entry != null && entry.isDirectory && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry ?: return
        val project = e.project ?: return
        val stateService = project.service<FileManagerStateService>()
        stateService.getActivePanel()?.openDirectoryInNewTab(entry.path)
    }
}

class OpenFileAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = entry != null && !entry.isDirectory && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.openSelectedEntry()
    }
}

class OpenInAssociatedAppAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = entry != null && !entry.isDirectory && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.openSelectedInAssociatedApp()
    }
}

class OpenInExplorerAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabled = entry != null && !entry.isParentLink
        val os = System.getProperty("os.name").lowercase()
        e.presentation.text = when {
            os.contains("win") -> "Open in Explorer"
            os.contains("mac") -> "Reveal in Finder"
            else -> "Open in File Manager"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry ?: return
        FileContextMenuState.clickedTab?.openInSystemExplorer(entry)
    }
}

class ContextCopyAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val tab = FileContextMenuState.clickedTab ?: findActiveTab(e)
        e.presentation.isEnabled = tab != null && tab.getSelectedEntries().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val tab = FileContextMenuState.clickedTab ?: findActiveTab(e) ?: return
        FileCopyBuffer.isCut = false
        FileCopyBuffer.entries = tab.getSelectedEntries()
    }
}

class ContextCutAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val tab = FileContextMenuState.clickedTab ?: findActiveTab(e)
        e.presentation.isEnabled = tab != null && tab.getSelectedEntries().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val tab = FileContextMenuState.clickedTab ?: findActiveTab(e) ?: return
        FileCopyBuffer.isCut = true
        FileCopyBuffer.entries = tab.getSelectedEntries()
    }
}

class ContextPasteAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val hasBuffer = FileCopyBuffer.entries.isNotEmpty()
        val tab = FileContextMenuState.clickedTab ?: findActiveTab(e)
        e.presentation.isEnabled = hasBuffer && tab != null && tab.currentVfs?.isReadOnly != true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entries = FileCopyBuffer.entries
        if (entries.isEmpty()) return
        val cut = FileCopyBuffer.isCut
        val tab = FileContextMenuState.clickedTab ?: findActiveTab(e) ?: return
        val destination = tab.currentPath

        if (cut) {
            FileCopyBuffer.entries = emptyList()
            tab.performMoveEntries(entries, destination, tab.getDisplayPath())
        } else {
            tab.performCopyEntries(entries, destination, tab.getDisplayPath())
        }
    }
}

class ContextPasteIntoAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val hasBuffer = FileCopyBuffer.entries.isNotEmpty()
        val entry = FileContextMenuState.clickedEntry
        val tab = FileContextMenuState.clickedTab ?: findActiveTab(e)
        e.presentation.isEnabledAndVisible = hasBuffer && entry != null && entry.isDirectory && !entry.isParentLink && tab?.currentVfs?.isReadOnly != true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entries = FileCopyBuffer.entries
        if (entries.isEmpty()) return
        val cut = FileCopyBuffer.isCut
        val entry = FileContextMenuState.clickedEntry ?: return
        val tab = FileContextMenuState.clickedTab ?: findActiveTab(e) ?: return

        if (cut) {
            FileCopyBuffer.entries = emptyList()
            tab.performMoveEntries(entries, entry.path, tab.getDisplayPath())
        } else {
            tab.performCopyEntries(entries, entry.path, tab.getDisplayPath())
        }
    }
}

class ContextRenameAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabled = entry != null && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.startRename()
    }
}

class ContextMultiRenameAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val tab = FileContextMenuState.clickedTab
        e.presentation.isEnabled = tab != null && tab.getSelectedEntries().any { !it.isParentLink }
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.performMultiRename()
    }
}

class ContextDeleteAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val tab = FileContextMenuState.clickedTab
        e.presentation.isEnabled = tab != null && tab.getSelectedEntries().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.performDelete()
    }
}

class PackFilesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val tab = FileContextMenuState.clickedTab
        e.presentation.isEnabled = tab != null && tab.getSelectedEntries().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.performPack()
    }
}

class ExtractFilesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = entry != null && isArchiveFile(entry)
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.performExtract()
    }
}

class ExtractHereAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = entry != null && isArchiveFile(entry)
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.performExtractHere()
    }
}

class ExtractToSubdirAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = entry != null && isArchiveFile(entry)
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.performExtractToSubdir()
    }
}

class SplitFileAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val tab = FileContextMenuState.clickedTab
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = tab != null && entry != null
            && !entry.isDirectory && !entry.isParentLink && !tab.isInsideArchive
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.performSplitFile()
    }
}

class CombineFilesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val tab = FileContextMenuState.clickedTab
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = tab != null && entry != null
            && !entry.isDirectory && !entry.isParentLink && !tab.isInsideArchive
            && isSplitChunkOrCrc(entry.name)
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.performCombineFiles()
    }

    private fun isSplitChunkOrCrc(name: String): Boolean {
        val ext = name.substringAfterLast('.', "")
        if (ext == "crc") return true
        return ext.length in 3..6 && ext.all { it.isDigit() }
    }
}

class CompareFilesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = resolveFiles(e).let { it.first != null && it.second != null }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (left, right) = resolveFiles(e)
        if (left == null || right == null) return

        val vfsLeft = LocalFileSystem.getInstance().findFileByNioFile(left.path)
        val vfsRight = LocalFileSystem.getInstance().findFileByNioFile(right.path)
        if (vfsLeft == null || vfsRight == null) return

        val factory = DiffContentFactory.getInstance()
        val leftContent = factory.create(project, vfsLeft)
        val rightContent = factory.create(project, vfsRight)

        val request = SimpleDiffRequest(
            "${left.name} vs ${right.name}",
            leftContent,
            rightContent,
            left.name,
            right.name,
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun resolveFiles(e: AnActionEvent): Pair<FileEntry?, FileEntry?> {
        val tab = FileContextMenuState.clickedTab ?: return null to null
        val selected = tab.getSelectedEntries().filter { !it.isDirectory && !it.isParentLink }

        if (selected.size == 2) {
            return selected[0] to selected[1]
        }

        if (selected.size == 1) {
            val project = e.project ?: return null to null
            val stateService = project.service<FileManagerStateService>()
            val leftPanel = stateService.leftPanel
            val rightPanel = stateService.rightPanel
            val otherPanel = if (leftPanel?.getActiveTab() === tab) rightPanel else leftPanel
            val otherSelected = otherPanel?.getActiveTab()?.getSelectedEntries()
                ?.filter { !it.isDirectory && !it.isParentLink }
            if (otherSelected?.size == 1) {
                // Left panel's file always goes on the left side
                return if (leftPanel?.getActiveTab() === tab) {
                    selected[0] to otherSelected[0]
                } else {
                    otherSelected[0] to selected[0]
                }
            }
        }

        return null to null
    }
}

class AddToFavoritesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = entry != null && entry.isDirectory && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry ?: return
        val project = e.project ?: return
        val stateService = project.service<FileManagerStateService>()
        stateService.addFavorite(entry.path.toString())
    }
}
