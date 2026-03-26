package io.github.jhspetersson.turtlecommander

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.KeymapManagerListener
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.RenderingHints
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

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

        val contentPanel = JPanel(BorderLayout()).apply {
            add(splitter, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }

        val content = ContentFactory.getInstance().createContent(contentPanel, null, false)
        toolWindow.contentManager.addContent(content)

        registerShortcuts(contentPanel, toolWindow)

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

        fun rebuildTitleActions() {
            val titleActions = mutableListOf<AnAction>()
            val entries = stateService.getFavoriteEntries()

            val maxVisible = 5
            val visibleEntries = entries.take(maxVisible)
            val overflowEntries = entries.drop(maxVisible)

            for ((index, entry) in visibleEntries.withIndex()) {
                titleActions.add(FavoriteAction(entry.path, index + 1, entry.color, project))
                titleActions.add(RemoveFavoriteAction(entry.path, project))
            }

            if (overflowEntries.isNotEmpty()) {
                titleActions.add(FavoriteOverflowAction(overflowEntries, maxVisible, project))
            }

            if (titleActions.isNotEmpty()) {
                titleActions.add(Separator.getInstance())
            }

            titleActions.add(bufferAction)
            toolWindow.setTitleActions(titleActions)
        }

        rebuildTitleActions()

        project.messageBus
            .connect(toolWindow.disposable)
            .subscribe(FileManagerStateService.FAVORITES_TOPIC, object : FavoritesChangeListener {
                override fun favoritesChanged() {
                    rebuildTitleActions()
                }
            })

        ApplicationManager.getApplication().messageBus
            .connect(toolWindow.disposable)
            .subscribe(TurtleCommanderSettings.TOPIC, object : TurtleCommanderSettingsListener {
                override fun settingsChanged() {
                    val s = TurtleCommanderSettings.getInstance().state
                    bottomBar.isVisible = s.showCommandBar
                    applyCommandBarStyle(bottomBar, s.commandBarStyle)
                    leftPanel.applyFonts()
                    rightPanel.applyFonts()
                    leftPanel.applyVisibilitySettings()
                    rightPanel.applyVisibilitySettings()
                    leftPanel.reSort()
                    rightPanel.reSort()
                    contentPanel.revalidate()
                    contentPanel.repaint()
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
            "TurtleCommander.ShowContextMenu",
            "TurtleCommander.SearchFiles",
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

    private fun applyCommandBarStyle(bar: JPanel, style: ComponentStyle) {
        val font = style.getFont(null)
        val fg = style.getFontColor()
        for (comp in bar.components) {
            if (comp is javax.swing.JButton) {
                if (font != null) comp.font = font
                if (fg != null) comp.foreground = fg
            }
        }
    }

    private fun createBottomBar(leftPanel: FileManagerPanel, rightPanel: FileManagerPanel): JPanel {
        val bar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }

        fun activePanel(): FileManagerPanel {
            val leftTab = leftPanel.getActiveTab()
            val leftFocused = leftTab?.table?.hasFocus() == true
            return if (leftFocused) leftPanel else rightPanel
        }

        fun getShortcut(actionId: String): KeyboardShortcut? {
            val am = ActionManager.getInstance()
            val action = am.getAction(actionId) ?: return null
            return action.shortcutSet.shortcuts.firstOrNull() as? KeyboardShortcut
        }

        fun hasShiftModifier(actionId: String): Boolean {
            val shortcut = getShortcut(actionId) ?: return false
            return shortcut.firstKeyStroke.modifiers and InputEvent.SHIFT_DOWN_MASK != 0
        }

        fun shortcutText(actionId: String): String {
            val shortcut = getShortcut(actionId) ?: return ""
            return KeymapUtil.getKeystrokeText(shortcut.firstKeyStroke)
        }

        data class BarButton(val actionId: String, val label: String, val button: JButton)

        val buttonDefs = listOf(
            Triple("TurtleCommander.ViewFile", "View") { activePanel().getActiveTab()?.viewSelectedFile() },
            Triple("TurtleCommander.OpenInApp", "Open") { activePanel().getActiveTab()?.openSelectedInAssociatedApp() },
            Triple("TurtleCommander.CopyFiles", "Copy") { activePanel().getActiveTab()?.performCopy() },
            Triple("TurtleCommander.MoveFiles", "Move") { activePanel().getActiveTab()?.performMove() },
            Triple("TurtleCommander.CreateDirectory", "Mkdir") { activePanel().getActiveTab()?.performCreateDirectory() },
            Triple("TurtleCommander.DeleteFiles", "Delete") { activePanel().getActiveTab()?.performDelete() },
            Triple("TurtleCommander.CreateFile", "New File") { activePanel().getActiveTab()?.performCreateFile() },
            Triple("TurtleCommander.Rename", "Rename") { activePanel().getActiveTab()?.startRename() },
        )

        val barButtons = buttonDefs.map { (actionId, label, action) ->
            val button = JButton().apply {
                isFocusable = false
                addActionListener { action() }
            }
            bar.add(button)
            BarButton(actionId, label, button)
        }

        fun updateBar(shiftPressed: Boolean) {
            for (btn in barButtons) {
                val isShift = hasShiftModifier(btn.actionId)
                btn.button.isVisible = isShift == shiftPressed
                val key = shortcutText(btn.actionId)
                btn.button.text = if (key.isNotEmpty()) "$key ${btn.label}" else btn.label
            }
            bar.revalidate()
            bar.repaint()
        }

        updateBar(false)
        applyCommandBarStyle(bar, TurtleCommanderSettings.getInstance().state.commandBarStyle)

        ApplicationManager.getApplication().messageBus
            .connect()
            .subscribe(KeymapManagerListener.TOPIC, object : KeymapManagerListener {
                override fun activeKeymapChanged(keymap: Keymap?) {
                    updateBar(false)
                }

                override fun shortcutsChanged(keymap: Keymap, actionIds: Collection<String>, fromSettings: Boolean) {
                    updateBar(false)
                }
            })

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            if (bar.isShowing) {
                when (e.id) {
                    KeyEvent.KEY_PRESSED -> if (e.keyCode == KeyEvent.VK_SHIFT) updateBar(true)
                    KeyEvent.KEY_RELEASED -> if (e.keyCode == KeyEvent.VK_SHIFT) updateBar(false)
                }
            }
            false
        }

        return bar
    }
}

private class FavoriteAction(
    val favPath: String,
    index: Int,
    colorHex: String,
    private val project: Project,
) : AnAction() {
    init {
        val path = Path.of(favPath)
        val name = path.fileName?.toString() ?: favPath
        templatePresentation.setText(name, false)
        val shortcut = if (index in 1..9) "<br>Ctrl+$index" else ""
        templatePresentation.description = "<html>$favPath$shortcut</html>"
        templatePresentation.icon = favoriteIcon(colorHex)
        templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val stateService = project.service<FileManagerStateService>()
        val panel = stateService.getActivePanel() ?: return
        panel.openDirectoryInNewTab(Path.of(favPath))
    }
}

private class RemoveFavoriteAction(
    private val favPath: String,
    private val project: Project,
) : AnAction() {
    init {
        val name = Path.of(favPath).fileName?.toString() ?: favPath
        templatePresentation.text = "Remove $name from favorites"
        templatePresentation.icon = AllIcons.Actions.Close
        templatePresentation.hoveredIcon = AllIcons.Actions.CloseHovered
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        project.service<FileManagerStateService>().removeFavorite(favPath)
    }
}

private class FavoriteOverflowAction(
    private val overflowEntries: List<FileManagerStateService.FavoriteEntry>,
    private val startIndex: Int,
    private val project: Project,
) : AnAction("More Favorites...", "Show more favorites", AllIcons.General.ChevronDown) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val popupMenu = JPopupMenu()
        for ((i, entry) in overflowEntries.withIndex()) {
            val name = Path.of(entry.path).fileName?.toString() ?: entry.path
            val menuItem = JMenuItem(name, favoriteIcon(entry.color))
            val favIndex = startIndex + i + 1
            val shortcut = if (favIndex in 1..9) "<br>Ctrl+$favIndex" else ""
            menuItem.toolTipText = "<html>${entry.path}$shortcut</html>"
            menuItem.addActionListener {
                val stateService = project.service<FileManagerStateService>()
                val panel = stateService.getActivePanel() ?: return@addActionListener
                panel.openDirectoryInNewTab(Path.of(entry.path))
            }
            popupMenu.add(menuItem)
        }
        val component = e.inputEvent?.component ?: return
        popupMenu.show(component, 0, component.height)
    }
}

val FAVORITE_PRESET_COLORS = linkedMapOf(
    "None" to "",
    "Blue" to "#5B9BD5",
    "Green" to "#70AD47",
    "Orange" to "#ED7D31",
    "Red" to "#E06666",
    "Purple" to "#A855F7",
    "Teal" to "#4DB6AC",
    "Brown" to "#A0522D",
    "Pink" to "#F48FB1",
    "Gray" to "#9E9E9E",
)

private fun favoriteIcon(colorHex: String): Icon {
    if (colorHex.isBlank()) return AllIcons.Nodes.Folder
    val color = try { Color.decode(colorHex) } catch (_: Exception) { return AllIcons.Nodes.Folder }
    return ColorFolderIcon(color)
}

private class ColorFolderIcon(private val color: Color) : Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = (g.create() as Graphics2D).apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        }
        try {
            g2.color = color
            // Folder tab
            val tabWidth = 6
            val tabHeight = 3
            g2.fillRoundRect(x + 1, y + 2, tabWidth, tabHeight + 1, 2, 2)
            // Folder body
            g2.fillRoundRect(x + 1, y + 4, iconWidth - 2, iconHeight - 6, 3, 3)
        } finally {
            g2.dispose()
        }
    }

    override fun getIconWidth(): Int = 16
    override fun getIconHeight(): Int = 16
}
