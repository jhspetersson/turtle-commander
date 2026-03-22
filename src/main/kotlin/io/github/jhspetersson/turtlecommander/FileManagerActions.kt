package io.github.jhspetersson.turtlecommander

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.wm.ToolWindowManager

private fun findActiveTab(e: AnActionEvent): FileTab? {
    val project = e.project ?: return null
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Turtle Commander") ?: return null
    if (!toolWindow.isVisible) return null
    val stateService = project.service<FileManagerStateService>()
    return stateService.getActiveTab()
}

private fun isToolWindowActive(e: AnActionEvent): Boolean {
    val project = e.project ?: return false
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Turtle Commander") ?: return false
    return toolWindow.isVisible
}

abstract class FileManagerAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val tab = findActiveTab(e)
        e.presentation.isEnabled = tab != null && (tab.table.hasFocus() || tab.list.hasFocus() || tab.tree.hasFocus())
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
        val tab = findActiveTab(e) ?: return
        val row = tab.table.selectedRow
        if (row >= 0) tab.table.editCellAt(row, 0)
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

class ContextSearchInDirectoryAction : AnAction("Search in Directory...", "Search for files in the selected directory", AllIcons.Actions.Find) {
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

class OpenKeymapSettingsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            { it is com.intellij.openapi.options.SearchableConfigurable && it.id == "preferences.keymap" },
            { configurable ->
                scheduleTreeNavigation(configurable, 0)
            },
        )
    }

    private fun scheduleTreeNavigation(configurable: com.intellij.openapi.options.Configurable, attempt: Int) {
        if (attempt > 50) return
        val delay = if (attempt == 0) 500 else 200
        javax.swing.Timer(delay, null).apply {
            isRepeats = false
            addActionListener {
                val component = configurable.createComponent()
                val tree = component?.let { findTree(it) }
                if (tree != null && canNavigate(tree, "Plugins", "Turtle Commander")) {
                    // Apply selection multiple times to survive post-initialization resets
                    for (d in listOf(0, 100, 300, 500, 750, 1000)) {
                        javax.swing.Timer(d, null).apply {
                            isRepeats = false
                            addActionListener {
                                expandAndSelect(tree, "Plugins", "Turtle Commander")
                            }
                            start()
                        }
                    }
                } else {
                    scheduleTreeNavigation(configurable, attempt + 1)
                }
            }
            start()
        }
    }

    private fun canNavigate(tree: javax.swing.JTree, vararg path: String): Boolean {
        val model = tree.model
        var current: Any = model.root ?: return false
        for (name in path) {
            val child = findChild(model, current, name) ?: return false
            current = child
        }
        return true
    }

    private fun findChild(model: javax.swing.tree.TreeModel, parent: Any, name: String): Any? {
        for (i in 0 until model.getChildCount(parent)) {
            val child = model.getChild(parent, i)
            if (getNodeName(child) == name) return child
        }
        return null
    }

    private fun getNodeName(node: Any): String {
        val obj = if (node is javax.swing.tree.DefaultMutableTreeNode) node.userObject else node
        obj ?: return ""
        // Try getName() via reflection (Group, QuickList, etc.)
        try {
            val method = obj.javaClass.getMethod("getName")
            val result = method.invoke(obj) as? String
            if (result != null) return result
        } catch (_: Exception) {}
        return obj.toString()
    }

    private fun findTree(component: java.awt.Component): javax.swing.JTree? {
        if (component is com.intellij.ui.treeStructure.Tree) return component
        if (component is java.awt.Container) {
            for (child in component.components) {
                val found = findTree(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun expandAndSelect(tree: javax.swing.JTree, vararg path: String) {
        val model = tree.model
        val root = model.root ?: return

        val nodes = mutableListOf(root)
        var current: Any = root
        for (name in path) {
            val child = findChild(model, current, name) ?: return
            nodes.add(child)
            current = child
        }

        // Expand each parent level first
        for (i in 1 until nodes.size) {
            tree.expandPath(javax.swing.tree.TreePath(nodes.subList(0, i).toTypedArray()))
        }
        val treePath = javax.swing.tree.TreePath(nodes.toTypedArray())
        tree.selectionPath = treePath
        tree.scrollPathToVisible(treePath)
    }
}

class OpenPluginSettingsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            "Turtle Commander",
        )
    }
}

object TabContextMenuState {
    var clickedTabIndex: Int = -1
    var clickedPanel: FileManagerPanel? = null
}

abstract class TabContextAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = TabContextMenuState.clickedPanel != null
                && TabContextMenuState.clickedTabIndex >= 0
    }
}

class CloseTabAction : TabContextAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val panel = TabContextMenuState.clickedPanel ?: return
        e.presentation.isEnabled = e.presentation.isEnabled && panel.getRealTabCount() > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val panel = TabContextMenuState.clickedPanel ?: return
        panel.closeTab(TabContextMenuState.clickedTabIndex)
    }
}

class CloseOtherTabsAction : TabContextAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val panel = TabContextMenuState.clickedPanel ?: return
        e.presentation.isEnabled = e.presentation.isEnabled && panel.getRealTabCount() > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val panel = TabContextMenuState.clickedPanel ?: return
        panel.closeOtherTabs(TabContextMenuState.clickedTabIndex)
    }
}

class CloseAllTabsAction : TabContextAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val panel = TabContextMenuState.clickedPanel ?: return
        e.presentation.isEnabled = e.presentation.isEnabled && panel.getRealTabCount() > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val panel = TabContextMenuState.clickedPanel ?: return
        panel.closeAllTabs()
    }
}

class CloseTabsToTheLeftAction : TabContextAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.presentation.isEnabled
                && TabContextMenuState.clickedTabIndex > 0
    }

    override fun actionPerformed(e: AnActionEvent) {
        val panel = TabContextMenuState.clickedPanel ?: return
        panel.closeTabsToTheLeft(TabContextMenuState.clickedTabIndex)
    }
}

class CloseTabsToTheRightAction : TabContextAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val panel = TabContextMenuState.clickedPanel ?: return
        val plusIndex = panel.getRealTabCount()
        e.presentation.isEnabled = e.presentation.isEnabled
                && TabContextMenuState.clickedTabIndex < plusIndex - 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val panel = TabContextMenuState.clickedPanel ?: return
        panel.closeTabsToTheRight(TabContextMenuState.clickedTabIndex)
    }
}

// --- File context menu ---

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

class OpenInNewTabAction : AnAction("Open in New Tab", "Open directory in a new tab", AllIcons.Actions.OpenNewTab) {
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

class OpenFileAction : AnAction("Open", "Open file in editor", AllIcons.Actions.MenuOpen) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = entry != null && !entry.isDirectory && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.openSelectedEntry()
    }
}

class OpenInAssociatedAppAction : AnAction("Open in Associated Application", "Open file with system default application", AllIcons.Actions.Execute) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = entry != null && !entry.isDirectory && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.openSelectedInAssociatedApp()
    }
}

class OpenInExplorerAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    init {
        val os = System.getProperty("os.name").lowercase()
        val name = when {
            os.contains("win") -> "Open in Explorer"
            os.contains("mac") -> "Reveal in Finder"
            else -> "Open in File Manager"
        }
        templatePresentation.text = name
        templatePresentation.icon = AllIcons.Actions.MenuOpen
    }

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabled = entry != null && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry ?: return
        FileContextMenuState.clickedTab?.openInSystemExplorer(entry)
    }
}

class ContextCopyAction : AnAction("Copy", "Copy selected files to buffer", AllIcons.Actions.Copy) {
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

class ContextCutAction : AnAction("Cut", "Cut selected files to buffer", AllIcons.Actions.MenuCut) {
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

class ContextPasteAction : AnAction("Paste", "Paste files from buffer into current directory", AllIcons.Actions.MenuPaste) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val hasBuffer = FileCopyBuffer.entries.isNotEmpty()
        val tab = FileContextMenuState.clickedTab ?: findActiveTab(e)
        e.presentation.isEnabled = hasBuffer && tab != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entries = FileCopyBuffer.entries
        if (entries.isEmpty()) return
        val cut = FileCopyBuffer.isCut
        val tab = FileContextMenuState.clickedTab ?: findActiveTab(e) ?: return
        val destination = tab.currentPath

        FileCopyBuffer.entries = emptyList()
        if (cut) {
            tab.performMoveEntries(entries, destination)
        } else {
            tab.performCopyEntries(entries, destination)
        }
    }
}

class ContextPasteIntoAction : AnAction("Paste Into", "Paste files from buffer into selected directory", AllIcons.Actions.MenuPaste) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val hasBuffer = FileCopyBuffer.entries.isNotEmpty()
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = hasBuffer && entry != null && entry.isDirectory && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entries = FileCopyBuffer.entries
        if (entries.isEmpty()) return
        val cut = FileCopyBuffer.isCut
        val entry = FileContextMenuState.clickedEntry ?: return
        val tab = FileContextMenuState.clickedTab ?: findActiveTab(e) ?: return

        FileCopyBuffer.entries = emptyList()
        if (cut) {
            tab.performMoveEntries(entries, entry.path)
        } else {
            tab.performCopyEntries(entries, entry.path)
        }
    }
}

class ContextRenameAction : AnAction("Rename", "Rename selected file or directory", AllIcons.Actions.Edit) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabled = entry != null && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        val tab = FileContextMenuState.clickedTab ?: return
        val row = tab.table.selectedRow
        if (row >= 0) tab.table.editCellAt(row, 0)
    }
}

class ContextDeleteAction : AnAction("Delete", "Delete selected files", AllIcons.Actions.GC) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val tab = FileContextMenuState.clickedTab
        e.presentation.isEnabled = tab != null && tab.getSelectedEntries().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.performDelete()
    }
}

class PackFilesAction : AnAction("Pack Files", "Pack selected files into a zip archive", AllIcons.FileTypes.Archive) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val tab = FileContextMenuState.clickedTab
        e.presentation.isEnabled = tab != null && tab.getSelectedEntries().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.performPack()
    }
}

class ExtractFilesAction : AnAction("Extract Files...", "Extract archive to a directory", AllIcons.Actions.Uninstall) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = entry != null && isArchiveFile(entry)
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.performExtract()
    }
}

class ExtractHereAction : AnAction("Extract Here", "Extract archive into current directory", AllIcons.Actions.Uninstall) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = entry != null && isArchiveFile(entry)
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileContextMenuState.clickedTab?.performExtractHere()
    }
}

class AddToFavoritesAction : AnAction("Add to Favorites", "Add directory to favorites", AllIcons.Nodes.Favorite) {
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
