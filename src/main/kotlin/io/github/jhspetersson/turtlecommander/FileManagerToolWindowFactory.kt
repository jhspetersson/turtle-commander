package io.github.jhspetersson.turtlecommander

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class FileManagerToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val initialPath = project.basePath?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"))
        val stateService = project.service<FileManagerStateService>()
        val savedState = stateService.state

        val leftPanel = FileManagerPanel(
            project = project,
            initialPath = initialPath,
            otherPanelPathProvider = { initialPath },
        )

        val rightPanel = FileManagerPanel(
            project = project,
            initialPath = initialPath,
            otherPanelPathProvider = { initialPath },
        )

        leftPanel.otherPanel = rightPanel
        rightPanel.otherPanel = leftPanel

        leftPanel.restoreState(savedState.leftPanel, stateService)
        rightPanel.restoreState(savedState.rightPanel, stateService)

        val splitter = OnePixelSplitter(false, savedState.splitProportion).apply {
            firstComponent = leftPanel
            secondComponent = rightPanel
        }

        stateService.registerPanels(leftPanel, rightPanel, splitter)

        val settings = TurtleCommanderSettings.getInstance()
        val bottomBar = createBottomBar(leftPanel, rightPanel)
        bottomBar.isVisible = settings.state.showCommandBar

        val mainPanel = JPanel(BorderLayout()).apply {
            add(splitter, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }

        val content = ContentFactory.getInstance().createContent(mainPanel, null, false)
        toolWindow.contentManager.addContent(content)

        registerShortcuts(mainPanel, toolWindow)

        val am = ActionManager.getInstance()
        val gearActions = DefaultActionGroup()
        am.getAction("TurtleCommander.OpenPluginSettings")?.let { gearActions.add(it) }
        am.getAction("TurtleCommander.OpenKeymapSettings")?.let { gearActions.add(it) }
        toolWindow.setAdditionalGearActions(gearActions)

        val bufferAction = object : AnAction() {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                val count = FileCopyBuffer.entries.size
                if (count > 0) {
                    val verb = if (FileCopyBuffer.isCut) "cut" else "copied"
                    e.presentation.isVisible = true
                    e.presentation.icon = if (FileCopyBuffer.isCut) AllIcons.Actions.MenuCut else AllIcons.Actions.Copy
                    e.presentation.text = if (count == 1) "1 item $verb" else "$count items $verb"
                } else {
                    e.presentation.isVisible = false
                }
            }

            override fun actionPerformed(e: AnActionEvent) {
                FileCopyBuffer.entries = emptyList()
            }
        }
        toolWindow.setTitleActions(listOf(bufferAction))

        ApplicationManager.getApplication().messageBus
            .connect(toolWindow.disposable)
            .subscribe(TurtleCommanderSettings.TOPIC, object : TurtleCommanderSettingsListener {
                override fun settingsChanged() {
                    val s = TurtleCommanderSettings.getInstance().state
                    bottomBar.isVisible = s.showCommandBar
                    leftPanel.applyFonts()
                    rightPanel.applyFonts()
                    mainPanel.revalidate()
                    mainPanel.repaint()
                }
            })
    }

    private fun registerShortcuts(component: JPanel, toolWindow: ToolWindow) {
        val am = ActionManager.getInstance()
        val actionIds = listOf(
            "TurtleCommander.OpenEntry",
            "TurtleCommander.GoUp",
            "TurtleCommander.Rename",
            "TurtleCommander.CopyFiles",
            "TurtleCommander.MoveFiles",
            "TurtleCommander.CreateDirectory",
            "TurtleCommander.CreateFile",
            "TurtleCommander.DeleteFiles",
            "TurtleCommander.SwitchPanel",
            "TurtleCommander.GoToFirst",
            "TurtleCommander.GoToLast",
            "TurtleCommander.Refresh",
            "TurtleCommander.LeftDriveSelector",
            "TurtleCommander.RightDriveSelector",
            "TurtleCommander.NewTab",
            "TurtleCommander.ContextCopy",
            "TurtleCommander.ContextCut",
            "TurtleCommander.ContextPaste",
        )
        for (actionId in actionIds) {
            val action = am.getAction(actionId) ?: continue
            action.registerCustomShortcutSet(action.shortcutSet, component, toolWindow.disposable)
        }
    }

    override fun shouldBeAvailable(project: Project) = true

    private fun createBottomBar(leftPanel: FileManagerPanel, rightPanel: FileManagerPanel): JPanel {
        val bar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }

        fun activePanel(): FileManagerPanel {
            val leftTab = leftPanel.getActiveTab()
            val rightTab = rightPanel.getActiveTab()
            val leftFocused = leftTab?.table?.hasFocus() == true
            return if (leftFocused) leftPanel else rightPanel
        }

        bar.add(JButton("F5 Copy").apply {
            isFocusable = false
            addActionListener { activePanel().getActiveTab()?.performCopy() }
        })
        bar.add(JButton("F6 Move").apply {
            isFocusable = false
            addActionListener { activePanel().getActiveTab()?.performMove() }
        })
        bar.add(JButton("F7 Mkdir").apply {
            isFocusable = false
            addActionListener { activePanel().getActiveTab()?.performCreateDirectory() }
        })
        bar.add(JButton("F8 Delete").apply {
            isFocusable = false
            addActionListener { activePanel().getActiveTab()?.performDelete() }
        })
        return bar
    }
}
