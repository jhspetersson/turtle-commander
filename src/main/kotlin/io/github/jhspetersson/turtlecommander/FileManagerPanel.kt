package io.github.jhspetersson.turtlecommander

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class FileManagerPanel(
    private val project: Project,
    private val initialPath: Path,
    private val otherPanelPathProvider: () -> Path?,
) : JPanel(BorderLayout()) {

    private val tabbedPane = JBTabbedPane()
    private val defaultTabFont by lazy { tabbedPane.font }
    private val addTabPlaceholder = JPanel()
    var otherPanel: FileManagerPanel? = null
    private var stateService: FileManagerStateService? = null

    private var dragSourceIndex = -1
    private var dropTargetIndex = -1
    private var dragActive = false
    private var dragStartPoint: Point? = null

    init {
        appendPlusTab()

        tabbedPane.addChangeListener {
            val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
            if (tabbedPane.selectedIndex == plusIndex && plusIndex > 0) {
                tabbedPane.selectedIndex = plusIndex - 1
            }
        }

        tabbedPane.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                handlePopup(e)
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    val tabIndex = getTabIndexAt(e.point)
                    val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
                    if (tabIndex >= 0 && tabIndex != plusIndex) {
                        closeTab(tabIndex)
                    }
                    return
                }
                if (!e.isPopupTrigger && SwingUtilities.isLeftMouseButton(e)) {
                    val tabIndex = getTabIndexAt(e.point)
                    val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
                    if (tabIndex >= 0 && tabIndex != plusIndex) {
                        dragSourceIndex = tabIndex
                        dragStartPoint = e.point
                    }
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                handlePopup(e)
                if (dragActive && dragSourceIndex >= 0) {
                    performDrop(e)
                }
                dragSourceIndex = -1
                dropTargetIndex = -1
                dragActive = false
                dragStartPoint = null
                tabbedPane.repaint()
            }

            private fun handlePopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val tabIndex = getTabIndexAt(e.point)
                val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
                if (tabIndex < 0 || tabIndex == plusIndex) return

                TabContextMenuState.clickedTabIndex = tabIndex
                TabContextMenuState.clickedPanel = this@FileManagerPanel

                val am = ActionManager.getInstance()
                val group = am.getAction("TurtleCommander.TabContextMenu") as? DefaultActionGroup ?: return
                val popupMenu = am.createActionPopupMenu(ActionPlaces.POPUP, group)
                popupMenu.component.show(tabbedPane, e.x, e.y)
            }
        })

        tabbedPane.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (dragSourceIndex < 0) return
                val start = dragStartPoint ?: return
                if (!dragActive && start.distance(e.point) < 5) return
                dragActive = true
                tabbedPane.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)

                // Check if dragging over the other panel
                val screenPoint = e.locationOnScreen
                val other = otherPanel
                if (other != null && other.tabbedPane.isShowing) {
                    val otherLocation = other.tabbedPane.locationOnScreen
                    val otherBounds = Rectangle(otherLocation.x, otherLocation.y,
                        other.tabbedPane.width, other.tabbedPane.height)
                    if (otherBounds.contains(screenPoint)) {
                        // Over other panel — update its drop indicator
                        val localPoint = Point(screenPoint.x - otherLocation.x, screenPoint.y - otherLocation.y)
                        other.updateDropIndicator(localPoint)
                        dropTargetIndex = -1
                        tabbedPane.repaint()
                        return
                    } else {
                        other.clearDropIndicator()
                    }
                }

                // Over this panel
                val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
                val tabIndex = getTabIndexAt(e.point)
                dropTargetIndex = if (tabIndex >= 0 && tabIndex != plusIndex && tabIndex != dragSourceIndex) {
                    tabIndex
                } else {
                    -1
                }
                tabbedPane.repaint()
            }
        })

        add(DraggableTabbedPaneWrapper(tabbedPane, this), BorderLayout.CENTER)
    }

    private fun updateDropIndicator(localPoint: Point) {
        val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
        val tabIndex = getTabIndexAt(localPoint)
        dropTargetIndex = if (tabIndex >= 0 && tabIndex != plusIndex) tabIndex else {
            // If past all tabs, target the last real tab position
            val lastReal = tabbedPane.tabCount - 2  // before "+"
            if (lastReal >= 0) lastReal else -1
        }
        tabbedPane.repaint()
    }

    private fun clearDropIndicator() {
        if (dropTargetIndex >= 0) {
            dropTargetIndex = -1
            tabbedPane.repaint()
        }
    }

    private fun performDrop(e: MouseEvent) {
        tabbedPane.cursor = Cursor.getDefaultCursor()

        val screenPoint = e.locationOnScreen
        val other = otherPanel
        if (other != null && other.tabbedPane.isShowing) {
            val otherLocation = other.tabbedPane.locationOnScreen
            val otherBounds = Rectangle(otherLocation.x, otherLocation.y,
                other.tabbedPane.width, other.tabbedPane.height)
            if (otherBounds.contains(screenPoint)) {
                // Drop on other panel — move tab there
                other.clearDropIndicator()
                moveTabToOtherPanel(dragSourceIndex, other, other.dropTargetIndex)
                return
            }
        }

        // Drop within same panel — reorder
        if (dropTargetIndex >= 0 && dropTargetIndex != dragSourceIndex) {
            reorderTab(dragSourceIndex, dropTargetIndex)
        }
    }

    private fun reorderTab(fromIndex: Int, toIndex: Int) {
        val component = tabbedPane.getComponentAt(fromIndex)
        val title = tabbedPane.getTitleAt(fromIndex)
        val tabComponent = tabbedPane.getTabComponentAt(fromIndex)

        tabbedPane.removeTabAt(fromIndex)
        val adjustedTo = if (fromIndex < toIndex) toIndex - 1 else toIndex
        tabbedPane.insertTab(title, null, component, null, adjustedTo)
        tabbedPane.setTabComponentAt(adjustedTo, tabComponent)
        tabbedPane.selectedIndex = adjustedTo
    }

    private fun moveTabToOtherPanel(sourceIndex: Int, target: FileManagerPanel, targetIndex: Int) {
        val fileTab = tabbedPane.getComponentAt(sourceIndex) as? FileTab ?: return
        val path = fileTab.currentPath
        val realTabCount = tabbedPane.tabCount - 1

        // Remove from source (but keep at least one tab)
        if (realTabCount > 1) {
            tabbedPane.removeTabAt(sourceIndex)
        } else {
            return
        }

        // Add to target panel
        val plusIndex = target.tabbedPane.indexOfComponent(target.addTabPlaceholder)
        val insertAt = if (targetIndex in 0 until plusIndex) targetIndex else plusIndex
        val title = path.fileName?.toString() ?: path.toString()
        target.tabbedPane.insertTab(title, null, fileTab, null, insertAt)
        target.tabbedPane.setTabComponentAt(insertAt, target.createTabHeader(title, fileTab))
        target.tabbedPane.selectedIndex = insertAt

        // Update the tab's callbacks to point to the new panel context
        fileTab.updatePanelCallbacks(
            otherPanelPathProvider = { target.otherPanel?.getActiveTab()?.currentPath },
            onDirectoryChanged = { tab -> target.updateTabTitle(tab) },
            onSwitchToOtherPanel = { target.otherPanel?.focusActiveTab() },
            onRefreshOtherPanel = { target.otherPanel?.refreshActiveTab() },
        )
    }

    fun getDropIndicatorRect(): Rectangle? {
        if (dropTargetIndex < 0) return null
        val bounds = tabbedPane.getBoundsAt(dropTargetIndex) ?: return null
        return Rectangle(bounds.x, bounds.y, 2, bounds.height)
    }

    fun restoreState(panelState: FileManagerStateService.PanelState, stateService: FileManagerStateService) {
        this.stateService = stateService
        val paths = panelState.tabPaths.mapNotNull { path ->
            try {
                val p = Path.of(path)
                if (p.toFile().isDirectory) p else null
            } catch (_: Exception) { null }
        }
        if (paths.isEmpty()) {
            addNewTab(initialPath)
        } else {
            paths.forEach { addNewTab(it) }
            val idx = panelState.selectedTabIndex.coerceIn(0, (tabbedPane.tabCount - 2).coerceAtLeast(0))
            tabbedPane.selectedIndex = idx
        }
    }

    fun saveState(): FileManagerStateService.PanelState {
        val state = FileManagerStateService.PanelState()
        val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
        for (i in 0 until tabbedPane.tabCount) {
            if (i == plusIndex) continue
            val tab = tabbedPane.getComponentAt(i) as? FileTab ?: continue
            state.tabPaths.add(tab.currentPath.toString())
            tab.saveColumnState()
        }
        state.selectedTabIndex = tabbedPane.selectedIndex
        return state
    }

    fun openNewTab() {
        val currentTab = getActiveTab()
        addNewTab(currentTab?.currentPath ?: initialPath)
    }

    fun openDirectoryInNewTab(path: Path) {
        addNewTab(path)
    }

    private fun addNewTab(path: Path) {
        val fileTab = FileTab(
            project = project,
            initialPath = path,
            otherPanelPathProvider = { otherPanel?.getActiveTab()?.currentPath ?: otherPanelPathProvider() },
            onDirectoryChanged = { tab -> updateTabTitle(tab) },
            onSwitchToOtherPanel = { otherPanel?.focusActiveTab() },
            onRefreshOtherPanel = { otherPanel?.refreshActiveTab() },
        )

        // Insert before the "+" tab
        val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
        val insertIndex = if (plusIndex >= 0) plusIndex else tabbedPane.tabCount
        tabbedPane.insertTab(path.fileName?.toString() ?: path.toString(), null, fileTab, null, insertIndex)
        tabbedPane.setTabComponentAt(insertIndex, createTabHeader(path.fileName?.toString() ?: path.toString(), fileTab))
        tabbedPane.selectedIndex = insertIndex

        stateService?.let { svc ->
            fileTab.setStateService(svc)
            val entry = svc.getColumnState(path.toString())
            if (entry != null) {
                fileTab.applyColumnState(svc.parseWidths(entry), svc.parseOrder(entry))
            }
        }

        val fileOps = project.service<FileOperationService>()
        fileOps.launch {
            fileTab.navigateTo(path)
        }
    }

    private fun appendPlusTab() {
        tabbedPane.addTab("", addTabPlaceholder)
        val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
        tabbedPane.setTabComponentAt(plusIndex, NewTabButton { openNewTab() })
    }

    internal fun createTabHeader(title: String, fileTab: FileTab): JPanel {
        val panel = JPanel(BorderLayout(4, 0))
        panel.isOpaque = false

        val label = JLabel(title)
        TurtleCommanderSettings.getInstance().getTabFont()?.let { label.font = it }
        panel.add(label, BorderLayout.CENTER)

        val closeButton = TabCloseButton {
            val realTabCount = tabbedPane.tabCount - 1
            if (realTabCount > 1) {
                val idx = tabbedPane.indexOfComponent(fileTab)
                if (idx >= 0) {
                    tabbedPane.removeTabAt(idx)
                }
            }
        }
        panel.add(closeButton, BorderLayout.EAST)

        return panel
    }

    private fun updateTabTitle(tab: FileTab) {
        val index = tabbedPane.indexOfComponent(tab)
        if (index < 0) return
        val title = tab.currentPath.fileName?.toString() ?: tab.currentPath.toString()
        tabbedPane.setTitleAt(index, title)

        val tabComponent = tabbedPane.getTabComponentAt(index)
        if (tabComponent is JPanel) {
            val label = tabComponent.getComponent(0)
            if (label is JLabel) {
                label.text = title
            }
        }
    }

    fun getActiveTab(): FileTab? {
        val selected = tabbedPane.selectedComponent
        if (selected === addTabPlaceholder) return null
        return selected as? FileTab
    }

    fun closeTab(tabIndex: Int) {
        val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
        val realTabCount = tabbedPane.tabCount - 1
        if (realTabCount <= 1) return
        if (tabIndex < 0 || tabIndex == plusIndex) return
        tabbedPane.removeTabAt(tabIndex)
    }

    fun closeOtherTabs(tabIndex: Int) {
        val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
        if (tabIndex < 0 || tabIndex == plusIndex) return
        val indicesToRemove = (0 until tabbedPane.tabCount)
            .filter { it != tabIndex && it != plusIndex }
            .sortedDescending()
        for (idx in indicesToRemove) {
            tabbedPane.removeTabAt(idx)
        }
    }

    fun closeAllTabs() {
        val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
        val indicesToRemove = (0 until tabbedPane.tabCount)
            .filter { it != plusIndex }
            .sortedDescending()
        // Keep at least one tab
        if (indicesToRemove.size <= 1) return
        for (idx in indicesToRemove.drop(1)) {
            tabbedPane.removeTabAt(idx)
        }
    }

    fun closeTabsToTheLeft(tabIndex: Int) {
        val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
        if (tabIndex <= 0 || tabIndex == plusIndex) return
        val indicesToRemove = (0 until tabIndex)
            .filter { it != plusIndex }
            .sortedDescending()
        for (idx in indicesToRemove) {
            tabbedPane.removeTabAt(idx)
        }
    }

    fun closeTabsToTheRight(tabIndex: Int) {
        val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
        if (tabIndex < 0 || tabIndex == plusIndex) return
        val indicesToRemove = (tabIndex + 1 until tabbedPane.tabCount)
            .filter { it != plusIndex }
            .sortedDescending()
        for (idx in indicesToRemove) {
            tabbedPane.removeTabAt(idx)
        }
    }

    fun getSelectedTabIndex(): Int = tabbedPane.selectedIndex

    fun getTabIndexAt(point: java.awt.Point): Int {
        for (i in 0 until tabbedPane.tabCount) {
            val bounds = tabbedPane.getBoundsAt(i) ?: continue
            if (bounds.contains(point)) return i
        }
        return -1
    }

    fun getRealTabCount(): Int {
        return tabbedPane.tabCount - 1 // exclude "+" tab
    }

    fun applyFonts() {
        val settings = TurtleCommanderSettings.getInstance()

        // Apply tab font
        val tabFont = settings.getTabFont()
        val tabFontSize = settings.getTabFontSize()
        val plusIndex = tabbedPane.indexOfComponent(addTabPlaceholder)
        val effectiveTabFont = when {
            tabFont != null -> tabFont
            tabFontSize > 0 -> defaultTabFont.deriveFont(tabFontSize.toFloat())
            else -> defaultTabFont
        }
        tabbedPane.font = effectiveTabFont
        for (i in 0 until tabbedPane.tabCount) {
            val tabComponent = tabbedPane.getTabComponentAt(i)
            if (i == plusIndex) continue
            if (tabComponent is JPanel) {
                val label = tabComponent.getComponent(0)
                if (label is JLabel) {
                    label.font = effectiveTabFont
                }
            }
        }

        // Apply panel font to all file tabs
        for (i in 0 until tabbedPane.tabCount) {
            if (i == plusIndex) continue
            val tab = tabbedPane.getComponentAt(i) as? FileTab ?: continue
            tab.applyPanelFont()
        }
    }

    fun showDriveSelector() {
        getActiveTab()?.showDriveSelector()
    }

    fun focusActiveTab() {
        getActiveTab()?.table?.requestFocusInWindow()
    }

    fun refreshActiveTab() {
        getActiveTab()?.refresh()
    }
}

private class DraggableTabbedPaneWrapper(
    private val tabbedPane: JBTabbedPane,
    private val panel: FileManagerPanel,
) : JPanel(BorderLayout()) {
    init {
        add(tabbedPane, BorderLayout.CENTER)
        isOpaque = false
    }

    override fun paint(g: Graphics) {
        super.paint(g)
        val rect = panel.getDropIndicatorRect() ?: return
        val g2 = g as Graphics2D
        g2.color = java.awt.Color(0x3574F0) // IntelliJ blue
        g2.fillRect(rect.x, rect.y, rect.width, rect.height)
    }
}

private class NewTabButton(private val onClick: () -> Unit) : JComponent() {
    private var hovered = false

    init {
        preferredSize = Dimension(16, 16)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "New tab"
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    onClick()
                }
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val icon = if (hovered) AllIcons.General.Add else AllIcons.General.InlineAdd
        val x = (width - icon.iconWidth) / 2
        val y = (height - icon.iconHeight) / 2
        icon.paintIcon(this, g, x, y)
    }
}

private class TabCloseButton(private val onClose: () -> Unit) : JComponent() {
    private var hovered = false

    init {
        preferredSize = Dimension(16, 16)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    onClose()
                }
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val icon = if (hovered) AllIcons.Actions.CloseHovered else AllIcons.Actions.Close
        val x = (width - icon.iconWidth) / 2
        val y = (height - icon.iconHeight) / 2
        icon.paintIcon(this, g, x, y)
    }
}
