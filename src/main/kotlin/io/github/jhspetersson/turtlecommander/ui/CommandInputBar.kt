package io.github.jhspetersson.turtlecommander.ui

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JPanel

/**
 * Bottom command line shared by both panels. Hidden until [activate]d (by the Execute Command
 * action). While the field holds focus the user can keep navigating files: Tab retargets the
 * other panel (routed through [io.github.jhspetersson.turtlecommander.service.FileManagerStateService.switchToOtherPanel]
 * so focus stays in the field), Up/Down move the active tab's selection, and Ctrl+Enter inserts
 * the selected file name. Enter runs the typed command in the active panel's directory; on a
 * non-zero exit or launch failure an error notification is shown, and both panels are refreshed
 * once the process finishes. Esc hides the bar and returns focus to the active panel.
 */
class CommandInputBar(
    private val project: Project,
    private val leftPanel: FileManagerPanel,
    private val rightPanel: FileManagerPanel,
) : JPanel(BorderLayout(6, 0)) {

    private val promptLabel = JBLabel()
    private val field = JBTextField()
    private var activePanel: FileManagerPanel = leftPanel

    init {
        isVisible = false
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLineTop(JBColor.border()),
            JBUI.Borders.empty(3, 6),
        )
        add(promptLabel, BorderLayout.WEST)
        add(field, BorderLayout.CENTER)

        field.installStandardContextMenu()
        field.focusTraversalKeysEnabled = false

        field.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ESCAPE -> { hideBar(); e.consume() }
                    KeyEvent.VK_UP -> { activeTab()?.moveSelection(-1); e.consume() }
                    KeyEvent.VK_DOWN -> { activeTab()?.moveSelection(1); e.consume() }
                    KeyEvent.VK_ENTER -> {
                        if (e.isControlDown) insertSelectedName() else executeCommand()
                        e.consume()
                    }
                }
            }
        })
    }

    fun activate(panel: FileManagerPanel?) {
        if (!isVisible) {
            activePanel = panel ?: activePanel
            isVisible = true
            updatePrompt()
            revalidate()
        }
        field.requestFocusInWindow()
        field.selectAll()
    }

    fun hasFieldFocus(): Boolean = field.hasFocus()

    fun switchTargetPanel() {
        activePanel = if (activePanel === leftPanel) rightPanel else leftPanel
        updatePrompt()
    }

    private fun hideBar() {
        isVisible = false
        revalidate()
        activePanel.focusActiveTab()
    }

    private fun activeTab(): FileTab? = activePanel.getActiveTab()

    private fun updatePrompt() {
        val dir = activeTab()?.currentPath?.toString() ?: ""
        val shown = if (dir.length > 48) "…" + dir.takeLast(47) else dir
        promptLabel.text = "$shown>"
        promptLabel.toolTipText = dir
    }

    private fun insertSelectedName() {
        val entry = activeTab()?.getSelectedEntry() ?: return
        if (entry.isParentLink) return
        val name = entry.name
        // Quote names with spaces so they reach the shell as a single argument.
        field.replaceSelection(if (name.any { it.isWhitespace() }) "\"$name\"" else name)
    }

    private fun executeCommand() {
        val command = field.text.trim()
        if (command.isEmpty()) return
        val tab = activeTab() ?: return
        if (tab.currentVfs != null) {
            notifyError("Cannot run commands inside an archive")
            return
        }
        runCommand(command, tab.currentPath)
        field.text = ""
    }

    private fun runCommand(command: String, workingDir: Path) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val cmdLine = when {
                    SystemInfo.isWindows && command.lowercase() in BARE_SHELLS ->
                        GeneralCommandLine("cmd.exe", "/c", "start", "", command)
                    SystemInfo.isWindows ->
                        GeneralCommandLine("cmd.exe", "/c", command)
                    else ->
                        GeneralCommandLine("/bin/sh", "-c", command)
                }.withWorkDirectory(workingDir.toFile())
                val handler = OSProcessHandler(cmdLine)
                val capture = CapturingProcessAdapter()
                handler.addProcessListener(capture)
                handler.startNotify()
                if (!handler.waitFor(LONG_RUN_REFRESH_MS)) {
                    refreshPanels()
                    handler.waitFor()
                }
                val output = capture.output
                if (output.exitCode != 0) {
                    val detail = output.stderr.ifBlank { output.stdout }.trim().take(500)
                    notifyErrorLater(buildString {
                        append("Command exited with code ${output.exitCode}: $command")
                        if (detail.isNotEmpty()) append("\n").append(detail)
                    })
                }
            } catch (e: ExecutionException) {
                notifyErrorLater("Failed to run \"$command\": ${e.message}")
            } catch (e: Exception) {
                notifyErrorLater("Failed to run \"$command\": ${e.message}")
            }
            refreshPanels()
        }
    }

    private fun refreshPanels() {
        ApplicationManager.getApplication().invokeLater {
            leftPanel.refreshActiveTab(requestFocus = false)
            rightPanel.refreshActiveTab(requestFocus = false)
        }
    }

    private fun notifyErrorLater(content: String) {
        ApplicationManager.getApplication().invokeLater { notifyError(content) }
    }

    private fun notifyError(content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Turtle Commander")
            .createNotification(content, NotificationType.ERROR)
            .notify(project)
    }

    private companion object {
        /** Refresh the panels (without waiting for completion) once a command runs this long. */
        const val LONG_RUN_REFRESH_MS = 10_000L

        /** Bare interactive shells (no arguments) that we launch via `start` so they get a window. */
        val BARE_SHELLS = setOf(
            "cmd", "cmd.exe",
            "powershell", "powershell.exe",
            "pwsh", "pwsh.exe",
            "bash", "bash.exe",
            "wsl", "wsl.exe",
        )
    }
}
