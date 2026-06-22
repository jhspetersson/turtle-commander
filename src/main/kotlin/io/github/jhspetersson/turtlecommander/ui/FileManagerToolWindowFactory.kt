package io.github.jhspetersson.turtlecommander.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.KeymapManagerListener
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.content.ContentFactory
import io.github.jhspetersson.turtlecommander.action.FileCopyBuffer
import io.github.jhspetersson.turtlecommander.service.FavoritesChangeListener
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.service.FileOperationService
import io.github.jhspetersson.turtlecommander.service.ThumbnailCache
import io.github.jhspetersson.turtlecommander.settings.ComponentStyle
import io.github.jhspetersson.turtlecommander.settings.PanelLayout
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettingsListener
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.*

class FileManagerToolWindowFactory : ToolWindowFactory, DumbAware {

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

        // Set before restoreState so the restored tabs are registered too: tabs must be
        // disposed with the tool window (project close, plugin unload), not only when the
        // user closes them — otherwise an open archive leaks its filesystem handles and
        // extraction temp directory.
        leftPanel.parentDisposable = toolWindow.disposable
        rightPanel.parentDisposable = toolWindow.disposable

        leftPanel.restoreState(savedState.leftPanel, stateService)
        rightPanel.restoreState(savedState.rightPanel, stateService)

        val layoutHost = JPanel(BorderLayout())
        var currentSplitter: OnePixelSplitter? = null
        var singleShowsLeft = true

        fun currentLayout(): PanelLayout = try {
            PanelLayout.valueOf(TurtleCommanderSettings.getInstance().state.panelLayout)
        } catch (_: Exception) {
            PanelLayout.HORIZONTAL
        }

        fun applyLayout() {
            // Persist the user's current drag position before we rebuild the splitter.
            currentSplitter?.let { savedState.splitProportion = it.proportion }
            layoutHost.removeAll()
            currentSplitter = null
            when (currentLayout()) {
                PanelLayout.HORIZONTAL, PanelLayout.VERTICAL -> {
                    val vertical = currentLayout() == PanelLayout.VERTICAL
                    val splitter = OnePixelSplitter(vertical, savedState.splitProportion).apply {
                        firstComponent = leftPanel
                        secondComponent = rightPanel
                    }
                    currentSplitter = splitter
                    layoutHost.add(splitter, BorderLayout.CENTER)
                }
                PanelLayout.SINGLE -> {
                    val visible = if (singleShowsLeft) leftPanel else rightPanel
                    layoutHost.add(visible, BorderLayout.CENTER)
                }
            }
            stateService.updateSplitter(currentSplitter)
            layoutHost.revalidate()
            layoutHost.repaint()
        }

        applyLayout()
        stateService.registerPanels(leftPanel, rightPanel, currentSplitter)
        stateService.setSwapSinglePanelCallback {
            singleShowsLeft = !singleShowsLeft
            if (currentLayout() == PanelLayout.SINGLE) applyLayout()
        }

        val settings = TurtleCommanderSettings.getInstance()
        val bottomBar = createBottomBar(leftPanel, rightPanel, toolWindow)
        bottomBar.isVisible = settings.state.showCommandBar

        val contentPanel = JPanel(BorderLayout()).apply {
            add(layoutHost, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }
        val content = ContentFactory.getInstance().createContent(contentPanel, null, false)
        toolWindow.contentManager.addContent(content)

        registerShortcuts(contentPanel, toolWindow)

        val am = ActionManager.getInstance()
        val gearActions = DefaultActionGroup()
        am.getAction("TurtleCommander.OpenPluginSettings")?.let { gearActions.add(it) }
        am.getAction("TurtleCommander.OpenKeymapSettings")?.let { gearActions.add(it) }
        gearActions.addSeparator()
        val layoutGroup = DefaultActionGroup("Layout", true).apply { isPopup = true }
        layoutGroup.add(LayoutToggleAction(PanelLayout.HORIZONTAL, "Horizontal Dual-panel"))
        layoutGroup.add(LayoutToggleAction(PanelLayout.VERTICAL, "Vertical Dual-panel"))
        layoutGroup.add(LayoutToggleAction(PanelLayout.SINGLE, "Single-panel"))
        gearActions.add(layoutGroup)
        gearActions.addSeparator()
        am.getAction("TurtleCommander.UserManual")?.let { gearActions.add(it) }
        am.getAction("TurtleCommander.About")?.let { gearActions.add(it) }
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
                titleActions.add(FavoriteAction(entry.path, index + 1, entry.color, entry.icon, project))
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

        val dataColumns = setOf("User", "Group", "Permissions", "Inode", "Links")
        fun visibleDataColumns(): Set<String> =
            TurtleCommanderSettings.getInstance().getEffectiveColumns()
                .filter { it.visible && it.id in dataColumns }
                .map { it.id }
                .toSet()
        var lastVisibleDataColumns = visibleDataColumns()

        ApplicationManager.getApplication().messageBus
            .connect(toolWindow.disposable)
            .subscribe(TurtleCommanderSettings.TOPIC, object : TurtleCommanderSettingsListener {
                override fun settingsChanged() {
                    val s = TurtleCommanderSettings.getInstance().state
                    // Column visibility affects which per-file fields (owner, group, permissions)
                    // are populated. Cached listings from before the change may have empty values
                    // for the newly-visible columns, so drop the cache on any settings change.
                    project.service<FileOperationService>().clearListingCache()
                    val newVisibleDataColumns = visibleDataColumns()
                    val gainedDataColumn = (newVisibleDataColumns - lastVisibleDataColumns).isNotEmpty()
                    lastVisibleDataColumns = newVisibleDataColumns
                    if (gainedDataColumn) {
                        leftPanel.refreshActiveTab(requestFocus = false)
                        rightPanel.refreshActiveTab(requestFocus = false)
                    }
                    bottomBar.isVisible = s.showCommandBar
                    applyCommandBarStyle(bottomBar, s.styles.commandBarStyle, s.styles.commandButtonStyle)
                    leftPanel.applyFonts()
                    rightPanel.applyFonts()
                    leftPanel.applyVisibilitySettings()
                    rightPanel.applyVisibilitySettings()
                    // Drop the in-memory icon cache so visible thumbnails re-render
                    // at the new display size; on-disk caches per size are preserved.
                    ThumbnailCache.getInstance().clearMemoryCache()
                    leftPanel.applyThumbnailSettings()
                    rightPanel.applyThumbnailSettings()
                    leftPanel.reSort()
                    rightPanel.reSort()
                    applyLayout()
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
            "TurtleCommander.CreateLink",
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
            "TurtleCommander.Quit",
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

    private fun applyCommandBarStyle(bar: JPanel, barStyle: ComponentStyle, buttonStyle: ComponentStyle) {
        // Save original defaults on first call
        if (bar.getClientProperty("defaultBg") == null) {
            bar.putClientProperty("defaultBg", bar.background)
        }
        val defaultBarBg = bar.getClientProperty("defaultBg") as? Color ?: bar.background

        val barBg = barStyle.parsedBackgroundColor()
        bar.background = barBg ?: defaultBarBg

        val btnFont = buttonStyle.getFont(null)
        val btnFg = buttonStyle.parsedFontColor()
        val btnBg = buttonStyle.parsedBackgroundColor()
        for (comp in bar.components) {
            if (comp is JButton) {
                if (comp.getClientProperty("defaultFont") == null) {
                    comp.putClientProperty("defaultFont", comp.font)
                    comp.putClientProperty("defaultFg", comp.foreground)
                    comp.putClientProperty("defaultBg", comp.background)
                }
                comp.font = btnFont ?: comp.getClientProperty("defaultFont") as? Font ?: comp.font
                comp.foreground = btnFg ?: comp.getClientProperty("defaultFg") as? Color ?: comp.foreground
                comp.background = btnBg ?: comp.getClientProperty("defaultBg") as? Color ?: comp.background
                comp.isContentAreaFilled = btnBg == null
                comp.isOpaque = btnBg != null
            }
        }
    }

    private fun createBottomBar(leftPanel: FileManagerPanel, rightPanel: FileManagerPanel, toolWindow: ToolWindow): JPanel {
        val bar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }

        // hasFocusInPanel covers all four view modes (table/list/thumbnail/tree) and
        // search-results tabs — checking only `table.hasFocus()` made the bar act on the
        // wrong panel whenever the left panel was focused in a non-table view. Falls back
        // to the right panel when neither has focus, matching
        // FileManagerStateService.getActivePanel.
        fun activePanel(): FileManagerPanel =
            if (leftPanel.hasFocusInPanel()) leftPanel else rightPanel

        fun getShortcut(actionId: String): KeyboardShortcut? {
            val am = ActionManager.getInstance()
            val action = am.getAction(actionId) ?: return null
            return action.shortcutSet.shortcuts.firstOrNull() as? KeyboardShortcut
        }

        val modifierMask = InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or
            InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK

        fun modifierOf(actionId: String): Int {
            val shortcut = getShortcut(actionId) ?: return 0
            return shortcut.firstKeyStroke.modifiers and modifierMask
        }

        fun shortcutText(actionId: String): String {
            val shortcut = getShortcut(actionId) ?: return ""
            return KeymapUtil.getKeystrokeText(shortcut.firstKeyStroke)
        }

        data class BarButton(val actionId: String, val label: String, val button: JButton)

        fun invokeById(actionId: String) {
            val action = ActionManager.getInstance().getAction(actionId) ?: return
            val dataContext = DataManager.getInstance().getDataContext(bar)
            val event = AnActionEvent.createEvent(action, dataContext, null, "CommandBar", ActionUiKind.NONE, null)
            ActionUtil.performAction(action, event)
        }

        val buttonDefs = listOf(
            Triple("TurtleCommander.LeftDriveSelector", "Left") { leftPanel.showDriveSelector() },
            Triple("TurtleCommander.RightDriveSelector", "Right") { rightPanel.showDriveSelector() },
            Triple("TurtleCommander.SearchFiles", "Search") { invokeById("TurtleCommander.SearchFiles") },
            Triple("TurtleCommander.PreviousTab", "Prev") { activePanel().selectPreviousTab() },
            Triple("TurtleCommander.NextTab", "Next") { activePanel().selectNextTab() },
            Triple("TurtleCommander.ViewFile", "View") { activePanel().getActiveTab()?.viewSelectedFile() },
            Triple("TurtleCommander.OpenInApp", "Open") { activePanel().getActiveTab()?.openSelectedInAssociatedApp() },
            Triple("TurtleCommander.Refresh", "Refresh") { activePanel().getActiveTab()?.refresh() },
            Triple("TurtleCommander.QuickFilter", "Filter") { activePanel().getActiveTab()?.showQuickFilter() },
            Triple("TurtleCommander.NewTab", "Tab") { activePanel().openNewTab() },
            Triple("TurtleCommander.CopyFiles", "Copy") { activePanel().getActiveTab()?.performCopy() },
            Triple("TurtleCommander.MoveFiles", "Move") { activePanel().getActiveTab()?.performMove() },
            Triple("TurtleCommander.CreateDirectory", "Mkdir") { activePanel().getActiveTab()?.performCreateDirectory() },
            Triple("TurtleCommander.CreateLink", "Link") { activePanel().getActiveTab()?.performCreateLink() },
            Triple("TurtleCommander.CloseTab", "Close") { activePanel().closeTab(activePanel().getActiveTabIndex()) },
            Triple("TurtleCommander.DeleteFiles", "Delete") { activePanel().getActiveTab()?.performDelete() },
            Triple("TurtleCommander.CreateFile", "New File") { activePanel().getActiveTab()?.performCreateFile() },
            Triple("TurtleCommander.Rename", "Rename") { activePanel().getActiveTab()?.startRename() },
            Triple("TurtleCommander.Quit", "Quit") { toolWindow.hide() },
        )

        val barButtons = buttonDefs.map { (actionId, label, action) ->
            val button = JButton().apply {
                isFocusable = false
                addActionListener { action() }
            }
            BarButton(actionId, label, button)
        }

        fun sortKey(actionId: String): Int {
            val ks = getShortcut(actionId)?.firstKeyStroke ?: return Int.MAX_VALUE
            return ks.keyCode
        }

        fun updateBar(activeModifier: Int) {
            val matching = barButtons.filter { getShortcut(it.actionId) != null && modifierOf(it.actionId) == activeModifier }
            val visibleButtons = (if (matching.isEmpty() && activeModifier != 0) {
                barButtons.filter { getShortcut(it.actionId) != null && modifierOf(it.actionId) == 0 }
            } else matching).sortedBy { sortKey(it.actionId) }

            bar.removeAll()
            visibleButtons.forEachIndexed { index, btn ->
                if (index > 0) bar.add(Box.createHorizontalStrut(2))
                val key = shortcutText(btn.actionId)
                btn.button.text = if (key.isNotEmpty()) "$key ${btn.label}" else btn.label
                bar.add(btn.button)
            }
            // Re-apply the theme to the buttons now on the bar. Modifier rows are detached
            // between key presses, so a settings change only restyles the attached set — the
            // freshly-shown row would otherwise appear with stale (default) styling.
            val styles = TurtleCommanderSettings.getInstance().state.styles
            applyCommandBarStyle(bar, styles.commandBarStyle, styles.commandButtonStyle)
            bar.revalidate()
            bar.repaint()
        }

        barButtons.forEach { bar.add(it.button) }
        val settings = TurtleCommanderSettings.getInstance().state
        applyCommandBarStyle(bar, settings.styles.commandBarStyle, settings.styles.commandButtonStyle)
        updateBar(0)

        ApplicationManager.getApplication().messageBus
            .connect(toolWindow.disposable)
            .subscribe(KeymapManagerListener.TOPIC, object : KeymapManagerListener {
                override fun activeKeymapChanged(keymap: Keymap?) {
                    updateBar(0)
                }

                override fun shortcutsChanged(keymap: Keymap, actionIds: Collection<String>, fromSettings: Boolean) {
                    updateBar(0)
                }
            })

        IdeEventQueue.getInstance().addDispatcher(
            object : IdeEventQueue.NonLockedEventDispatcher {
                override fun dispatch(e: AWTEvent): Boolean {
                    if (e is KeyEvent && bar.isShowing &&
                        (e.id == KeyEvent.KEY_PRESSED || e.id == KeyEvent.KEY_RELEASED)
                    ) {
                        when (e.keyCode) {
                            KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL,
                            KeyEvent.VK_ALT, KeyEvent.VK_META -> {
                                updateBar(e.modifiersEx and modifierMask)
                            }
                        }
                    }
                    return false
                }
            },
            toolWindow.disposable,
        )

        return bar
    }
}

private class LayoutToggleAction(
    private val layout: PanelLayout,
    text: String,
) : ToggleAction(text), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean =
        TurtleCommanderSettings.getInstance().state.panelLayout == layout.name

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (!state) return
        val settings = TurtleCommanderSettings.getInstance()
        if (settings.state.panelLayout == layout.name) return
        settings.state.panelLayout = layout.name
        settings.fireSettingsChanged()
    }
}
