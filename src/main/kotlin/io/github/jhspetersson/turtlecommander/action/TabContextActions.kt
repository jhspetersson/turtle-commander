package io.github.jhspetersson.turtlecommander.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import io.github.jhspetersson.turtlecommander.dialog.FileSearchDialog
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel

object TabContextMenuState {
    var clickedTabIndex: Int = -1
    var clickedPanel: FileManagerPanel? = null
}

abstract class TabContextAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val (panel, _) = resolveTabContext(e)
        e.presentation.isEnabled = panel != null
    }

    protected fun resolveTabContext(e: AnActionEvent): Pair<FileManagerPanel?, Int> {
        val contextPanel = TabContextMenuState.clickedPanel
        if (contextPanel != null && TabContextMenuState.clickedTabIndex >= 0) {
            return contextPanel to TabContextMenuState.clickedTabIndex
        }
        val project = e.project ?: return null to -1
        val panel = project.service<FileManagerStateService>().getActivePanel() ?: return null to -1
        val index = panel.getActiveTabIndex()
        return if (index >= 0) panel to index else null to -1
    }
}

class CloseTabAction : TabContextAction() {
    override fun update(e: AnActionEvent) {
        val (panel, _) = resolveTabContext(e)
        e.presentation.isEnabled = panel != null && panel.getRealTabCount() > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        panel?.closeTab(tabIndex)
    }
}

class CloseOtherTabsAction : TabContextAction() {
    override fun update(e: AnActionEvent) {
        val (panel, _) = resolveTabContext(e)
        e.presentation.isEnabled = panel != null && panel.getRealTabCount() > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        panel?.closeOtherTabs(tabIndex)
    }
}

class CloseAllTabsAction : TabContextAction() {
    override fun update(e: AnActionEvent) {
        val (panel, _) = resolveTabContext(e)
        e.presentation.isEnabled = panel != null && panel.getRealTabCount() > 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (panel, _) = resolveTabContext(e)
        panel?.closeAllTabs()
    }
}

class CloseTabsToTheLeftAction : TabContextAction() {
    override fun update(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        e.presentation.isEnabled = panel != null && tabIndex > 0
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        panel?.closeTabsToTheLeft(tabIndex)
    }
}

class CloseTabsToTheRightAction : TabContextAction() {
    override fun update(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        e.presentation.isEnabled = panel != null && tabIndex < (panel.getRealTabCount() ?: 0) - 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        panel?.closeTabsToTheRight(tabIndex)
    }
}

class TabSearchInDirectoryAction : TabContextAction() {
    init {
        templatePresentation.text = "Search in Directory..."
        templatePresentation.icon = AllIcons.Actions.Find
    }

    override fun update(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        val tab = panel?.getTabAt(tabIndex)
        e.presentation.isEnabledAndVisible = tab != null && tab.currentVfs == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        val tab = panel?.getTabAt(tabIndex) ?: return
        val project = e.project ?: return
        val dialog = FileSearchDialog(project, tab.currentPath)
        if (!dialog.showAndGet()) return
        val criteria = dialog.getCriteria()
        panel.openSearchTab(criteria)
    }
}

class TabAddToFavoritesAction : TabContextAction() {
    init {
        templatePresentation.text = "Add to Favorites"
        templatePresentation.icon = AllIcons.Nodes.Favorite
    }

    override fun update(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        val tab = panel?.getTabAt(tabIndex)
        e.presentation.isEnabledAndVisible = tab != null && tab.currentVfs == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        val tab = panel?.getTabAt(tabIndex) ?: return
        val project = e.project ?: return
        val stateService = project.service<FileManagerStateService>()
        stateService.addFavorite(tab.currentPath.toString())
    }
}
