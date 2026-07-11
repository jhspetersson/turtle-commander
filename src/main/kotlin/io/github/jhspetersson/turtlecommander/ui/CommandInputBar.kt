package io.github.jhspetersson.turtlecommander.ui

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.TextFieldWithHistory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.operation.CdCommand
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

/**
 * Bottom command line shared by both panels. Hidden until [activate]d (by the Execute Command
 * action). The input is an editable combo box whose drop-down lists the command history (pick one
 * with the mouse, or open it with the arrow button). While the editor holds focus the user can keep
 * navigating files: Tab retargets the other panel (routed through
 * [io.github.jhspetersson.turtlecommander.service.FileManagerStateService.switchToOtherPanel] so
 * focus stays in the field), Up/Down move the active tab's selection, Ctrl+Up/Ctrl+Down walk the
 * history, and Ctrl+Enter inserts the selected file name — except while the drop-down is open, when
 * those keys drive the list as usual. Enter runs the typed command in the active panel's directory;
 * on a non-zero exit or launch failure an error notification is shown, and both panels are refreshed
 * once the process finishes. Esc hides the bar and returns focus to the active panel.
 */
class CommandInputBar(
    private val project: Project,
    private val leftPanel: FileManagerPanel,
    private val rightPanel: FileManagerPanel,
) : JPanel(BorderLayout(6, 0)) {

    private val promptLabel = JBLabel()
    private val field = TextFieldWithHistory()
    private val editor: JTextField = field.textEditor
    private var activePanel: FileManagerPanel = leftPanel
    private val stateService = project.service<FileManagerStateService>()

    // Command-history navigation. [historyIndex] is -1 when not browsing; otherwise an index into
    // the history list (its size == "back to the unsent draft"). [draft] preserves what the user
    // had typed before they started walking back, and [suppressHistoryReset] keeps programmatic
    // text changes from clearing the browsing position.
    private var historyIndex = -1
    private var draft = ""
    private var suppressHistoryReset = false

    init {
        isVisible = false
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLineTop(JBColor.border()),
            JBUI.Borders.empty(3, 6),
        )
        add(promptLabel, BorderLayout.WEST)
        add(field, BorderLayout.CENTER)

        field.setHistorySize(-1) // the persisted history is already capped; never trim the list we set
        editor.installStandardContextMenu()
        editor.focusTraversalKeysEnabled = false

        editor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // While the drop-down is open, let the combo box own these keys (navigate/pick/close).
                if (field.isPopupVisible) return
                when (e.keyCode) {
                    KeyEvent.VK_ESCAPE -> { hideBar(); e.consume() }
                    KeyEvent.VK_UP -> {
                        if (e.isControlDown) recallHistory(-1) else activeTab()?.moveSelection(-1)
                        e.consume()
                    }
                    KeyEvent.VK_DOWN -> {
                        if (e.isControlDown) recallHistory(1) else activeTab()?.moveSelection(1)
                        e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        if (e.isControlDown) insertSelectedName() else executeCommand()
                        e.consume()
                    }
                }
            }
        })

        // Typing in the field abandons history browsing and returns to a fresh draft.
        editor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!suppressHistoryReset) historyIndex = -1
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
        syncHistory()
        historyIndex = -1
        editor.requestFocusInWindow()
        editor.selectAll()
    }

    fun hasFieldFocus(): Boolean = editor.hasFocus()

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

    /** Refresh the drop-down list from the persisted history, most-recent first. */
    private fun syncHistory() {
        field.history = stateService.getCommandHistory().reversed()
    }

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
        editor.replaceSelection(if (name.any { it.isWhitespace() }) "\"$name\"" else name)
    }

    private fun executeCommand() {
        val command = editor.text.trim()
        if (command.isEmpty()) return
        val tab = activeTab() ?: return
        if (tab.currentVfs != null) {
            notifyError("Cannot run commands inside an archive")
            return
        }
        stateService.addCommandToHistory(command)
        syncHistory()
        // A `cd` can't outlive a one-shot child shell, so handle it ourselves by navigating the
        // active tab instead of spawning a process that would immediately discard the change.
        val cdTarget = CdCommand.parseArgument(command)
        if (cdTarget != null) {
            tab.changeDirectoryFromCommand(cdTarget) { updatePrompt() }
        } else {
            runCommand(command, tab.currentPath)
        }
        editor.text = ""
        historyIndex = -1
    }

    /** Walk the command history: [delta] is -1 for older, +1 for newer. */
    private fun recallHistory(delta: Int) {
        val history = stateService.getCommandHistory()
        if (history.isEmpty()) return
        if (historyIndex == -1) {
            // Entering history browsing: remember the in-progress draft to restore at the bottom.
            draft = editor.text
            historyIndex = history.size
        }
        val target = (historyIndex + delta).coerceIn(0, history.size)
        historyIndex = target
        setFieldText(if (target == history.size) draft else history[target])
    }

    /** Replace the field text without resetting the history-browsing position. */
    private fun setFieldText(text: String) {
        suppressHistoryReset = true
        editor.text = text
        suppressHistoryReset = false
        editor.caretPosition = editor.text.length
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
