package io.github.jhspetersson.turtlecommander

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalOptionsProvider
import org.jetbrains.plugins.terminal.TerminalTabState
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

        if (entry.isDirectory) {
            openTerminal(project, entry.path.toString(), entry.name)
        } else {
            val dir = entry.path.parent.fileName.toString()
            openTerminal(project, dir, dir, entry.name)
        }
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
        openTerminal(project, tab.currentPath.toString(), tab.currentPath.fileName?.toString() ?: "Terminal")
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

private fun openTerminal(project: Project, workingDir: String, tabName: String, fileNameToType: String? = null) {
    val tabState = TerminalTabState().apply {
        myTabName = tabName
        myWorkingDirectory = workingDir
    }
    val engine = service<TerminalOptionsProvider>().terminalEngine
    val manager = TerminalToolWindowManager.getInstance(project)
    val widget = manager.createNewTab(engine, null, tabState)
    if (fileNameToType != null) {
        val escaped = if (' ' in fileNameToType) "\"$fileNameToType\"" else fileNameToType
        widget.ttyConnectorAccessor.executeWithTtyConnector { connector ->
            connector.write(escaped)
        }
    }
}
