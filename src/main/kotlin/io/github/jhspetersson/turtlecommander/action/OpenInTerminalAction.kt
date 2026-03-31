package io.github.jhspetersson.turtlecommander.action
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

class OpenInTerminalAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val tab = FileContextMenuState.clickedTab
        val entry = FileContextMenuState.clickedEntry
        e.presentation.isEnabledAndVisible = tab != null
                && tab.currentVfs == null
                && entry != null
                && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tab = FileContextMenuState.clickedTab ?: return
        val entry = FileContextMenuState.clickedEntry ?: return
        if (tab.currentVfs != null) return

        val dir = if (entry.isDirectory) entry.path else entry.path.parent ?: return
        openTerminal(project, dir.toString())
    }
}

class OpenTabInTerminalAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val tab = resolveTab(e)
        e.presentation.isEnabledAndVisible = tab != null && tab.currentVfs == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tab = resolveTab(e) ?: return
        if (tab.currentVfs != null) return
        openTerminal(project, tab.currentPath.toString())
    }

    private fun resolveTab(e: AnActionEvent): FileTab? {
        val contextPanel = TabContextMenuState.clickedPanel
        if (contextPanel != null && TabContextMenuState.clickedTabIndex >= 0) {
            return contextPanel.getTabAt(TabContextMenuState.clickedTabIndex)
        }
        val project = e.project ?: return null
        return project.service<FileManagerStateService>().getActivePanel()?.getActiveTab()
    }
}

private fun openTerminal(project: Project, workingDir: String) {
    val vf = LocalFileSystem.getInstance().findFileByPath(workingDir) ?: return
    TerminalToolWindowManager.getInstance(project).openTerminalIn(vf)
}
