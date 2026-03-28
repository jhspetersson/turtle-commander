package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager
import io.github.jhspetersson.turtlecommander.dialog.FileSearchDialog
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.ui.*
import java.nio.file.Path

internal fun findActiveTab(e: AnActionEvent): FileTab? {
    val project = e.project ?: return null
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Turtle Commander") ?: return null
    if (!toolWindow.isVisible) return null
    val stateService = project.service<FileManagerStateService>()
    return stateService.getActiveTab()
}

internal fun isToolWindowActive(e: AnActionEvent): Boolean {
    val project = e.project ?: return false
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Turtle Commander") ?: return false
    return toolWindow.isVisible
}

abstract class FileManagerAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val tab = findActiveTab(e)
        e.presentation.isEnabled = tab != null && (tab.table.hasFocus() || tab.list.hasFocus() || tab.thumbnailList.hasFocus() || tab.tree.hasFocus())
    }
}

class OpenEntryAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val tab = findActiveTab(e) ?: return
        tab.openSelectedEntry()
    }
}

class ViewFileAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        findActiveTab(e)?.viewSelectedFile()
    }
}

class OpenInAppAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        findActiveTab(e)?.openSelectedInAssociatedApp()
    }
}

class GoUpAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val tab = findActiveTab(e) ?: return
        tab.goUp()
    }
}

class RenameAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        findActiveTab(e)?.startRename()
    }
}

class CopyFilesAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        findActiveTab(e)?.performCopy()
    }
}

class MoveFilesAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        findActiveTab(e)?.performMove()
    }
}

class CreateDirectoryAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        findActiveTab(e)?.performCreateDirectory()
    }
}

class CreateFileAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        findActiveTab(e)?.performCreateFile()
    }
}

class DeleteFilesAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        findActiveTab(e)?.performDelete()
    }
}

class SwitchPanelAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isToolWindowActive(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateService = project.service<FileManagerStateService>()
        stateService.switchToOtherPanel()
    }
}

class GoToFirstAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val tab = findActiveTab(e) ?: return
        if (tab.table.rowCount > 0) {
            tab.table.setRowSelectionInterval(0, 0)
            tab.table.scrollRectToVisible(tab.table.getCellRect(0, 0, true))
        }
    }
}

class GoToLastAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val tab = findActiveTab(e) ?: return
        val last = tab.table.rowCount - 1
        if (last >= 0) {
            tab.table.setRowSelectionInterval(last, last)
            tab.table.scrollRectToVisible(tab.table.getCellRect(last, 0, true))
        }
    }
}

class ToggleSelectionAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        findActiveTab(e)?.toggleSelectionAndMoveDown()
    }
}

class QuickFilterAction : FileManagerAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isToolWindowActive(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        findActiveTab(e)?.showQuickFilter()
    }
}

class ShowContextMenuAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        findActiveTab(e)?.showContextMenu()
    }
}

class SearchFilesAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val tab = findActiveTab(e) ?: return
        val project = e.project ?: return
        val dialog = FileSearchDialog(project, tab.currentPath)
        if (!dialog.showAndGet()) return
        val criteria = dialog.getCriteria()
        val stateService = project.service<FileManagerStateService>()
        stateService.getActivePanel()?.openSearchTab(criteria)
    }
}

class RefreshAction : FileManagerAction() {
    override fun actionPerformed(e: AnActionEvent) {
        findActiveTab(e)?.refresh()
    }
}

class LeftDriveSelectorAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isToolWindowActive(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateService = project.service<FileManagerStateService>()
        stateService.leftPanel?.showDriveSelector()
    }
}

class RightDriveSelectorAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isToolWindowActive(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateService = project.service<FileManagerStateService>()
        stateService.rightPanel?.showDriveSelector()
    }
}

class NewTabAction : FileManagerAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isToolWindowActive(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateService = project.service<FileManagerStateService>()
        stateService.getActivePanel()?.openNewTab()
    }
}

class NextTabAction : FileManagerAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isToolWindowActive(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateService = project.service<FileManagerStateService>()
        stateService.getActivePanel()?.selectNextTab()
    }
}

class PreviousTabAction : FileManagerAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isToolWindowActive(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateService = project.service<FileManagerStateService>()
        stateService.getActivePanel()?.selectPreviousTab()
    }
}

class QuickAccessFavoriteAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isToolWindowActive(e)
        val slot = extractSlot(e) ?: return
        val project = e.project ?: return
        val stateService = project.service<FileManagerStateService>()
        val favorites = stateService.getFavorites()
        val path = favorites.getOrNull(slot - 1)
        e.presentation.text = if (path != null) {
            val name = Path.of(path).fileName?.toString() ?: path
            "Go to $name"
        } else {
            "Favorite $slot (not set)"
        }
        e.presentation.isEnabled = e.presentation.isEnabled && path != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val slot = extractSlot(e) ?: return
        val project = e.project ?: return
        val stateService = project.service<FileManagerStateService>()
        val path = stateService.getFavorites().getOrNull(slot - 1) ?: return
        val panel = stateService.getActivePanel() ?: return
        panel.openDirectoryInNewTab(Path.of(path))
    }

    private fun extractSlot(e: AnActionEvent): Int? {
        return e.actionManager.getId(this)?.substringAfterLast('.')?.toIntOrNull()
    }
}

class OpenInTurtleCommanderAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val path = Path.of(vf.path)

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Turtle Commander") ?: return
        toolWindow.activate {
            val stateService = project.service<FileManagerStateService>()
            val panel = stateService.getActivePanel() ?: return@activate
            if (vf.isDirectory) {
                panel.openDirectoryInNewTab(path)
            } else {
                val dir = path.parent ?: return@activate
                panel.openDirectoryInNewTab(dir, path.fileName.toString())
            }
        }
    }
}
