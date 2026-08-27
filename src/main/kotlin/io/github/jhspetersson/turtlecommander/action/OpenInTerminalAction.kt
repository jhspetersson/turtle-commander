package io.github.jhspetersson.turtlecommander.action
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.util.Base64

class OpenInTerminalAction : EdtAction() {
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
        openTerminal(project, dir.toString(), elevated = isShiftPressed(e))
    }
}

class OpenTabInTerminalAction : EdtAction() {
    override fun update(e: AnActionEvent) {
        val tab = resolveTab(e)
        e.presentation.isEnabledAndVisible = tab != null && tab.currentVfs == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tab = resolveTab(e) ?: return
        if (tab.currentVfs != null) return
        openTerminal(project, tab.currentPath.toString(), elevated = isShiftPressed(e))
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

private fun isShiftPressed(e: AnActionEvent): Boolean = e.inputEvent?.isShiftDown == true

private fun openTerminal(project: Project, workingDir: String, elevated: Boolean = false) {
    if (elevated && SystemInfo.isWindows) {
        openElevatedTerminal(workingDir)
        return
    }
    val vf = LocalFileSystem.getInstance().findFileByPath(workingDir) ?: return
    TerminalToolWindowManager.getInstance(project).openTerminalIn(vf)
}

private fun openElevatedTerminal(workingDir: String) {
    val escaped = workingDir.replace("'", "''")
    val command = "Start-Process cmd -ArgumentList '/K','cd /d \"$escaped\"' -Verb RunAs"
    val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_16LE))
    runCatching {
        ProcessBuilder("powershell.exe", "-NoProfile", "-EncodedCommand", encoded).start()
    }.onFailure {
        logger<OpenInTerminalAction>().warn("Failed to launch elevated terminal in $workingDir", it)
    }
}
