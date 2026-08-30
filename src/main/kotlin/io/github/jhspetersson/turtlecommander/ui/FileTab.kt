package io.github.jhspetersson.turtlecommander.ui
import io.github.jhspetersson.turtlecommander.util.DriveLabels
import java.nio.file.PathMatcher

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.IdeGlassPaneUtil
import com.intellij.ui.*
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.speedSearch.SpeedSearchSupply
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import io.github.jhspetersson.turtlecommander.dialog.DriveSpaceDialog
import io.github.jhspetersson.turtlecommander.dialog.collectDriveInfo
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.operation.CdCommand
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.service.FileOperationService
import io.github.jhspetersson.turtlecommander.service.ThumbnailCache
import io.github.jhspetersson.turtlecommander.settings.ColumnConfig
import io.github.jhspetersson.turtlecommander.settings.ResolvedStyle
import io.github.jhspetersson.turtlecommander.settings.RuleIcons
import io.github.jhspetersson.turtlecommander.settings.ThumbnailSize
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.util.fileErrorMessage
import io.github.jhspetersson.turtlecommander.util.formatSize
import io.github.jhspetersson.turtlecommander.util.formatSizeAuto
import io.github.jhspetersson.turtlecommander.util.wrapAsSubstringGlobIfPlain
import io.github.jhspetersson.turtlecommander.vfs.System7z
import io.github.jhspetersson.turtlecommander.vfs.System7zUnavailableException
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.intellij.openapi.diagnostic.thisLogger
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import javax.swing.event.*
import javax.swing.table.TableColumn
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class FileTab(
    internal val project: Project,
    initialPath: Path,
    internal var otherPanelPathProvider: () -> Path?,
    private var onDirectoryChanged: (FileTab) -> Unit,
    internal var onRefreshOtherPanel: () -> Unit = {},
) : JPanel(BorderLayout()), Disposable {

    internal val fileOps = project.service<FileOperationService>()
    internal val tableModel = FileTableModel()
    val table = JBTable(tableModel)
    private val defaultTableFont by lazy { table.font }
    private val defaultTableFg = UIManager.getColor("Table.foreground")
    private val defaultTableBg = UIManager.getColor("Table.background")
    private val defaultSelectionBg = UIManager.getColor("Table.selectionBackground")
    private val defaultDriveComboFont by lazy { driveCombo.font }
    private val defaultDriveComboFg = UIManager.getColor("ComboBox.foreground")
    private val defaultDriveComboBg = UIManager.getColor("ComboBox.background")
    private val defaultHeaderFont by lazy { table.tableHeader?.font }
    private val defaultHeaderFg = UIManager.getColor("TableHeader.foreground")
    private val defaultHeaderBg = UIManager.getColor("TableHeader.background")
    private val defaultStatusFont by lazy { statusLabel.font }
    private val defaultStatusFg = UIManager.getColor("Label.foreground")
    private val defaultStatusBg = UIManager.getColor("Panel.background")

    internal val listModel = DefaultListModel<FileEntry>()
    val list = JBList(listModel)
    internal val thumbnailListModel = DefaultListModel<FileEntry>()
    val thumbnailList = JBList(thumbnailListModel)
    internal val treeRootNode = DefaultMutableTreeNode()
    internal val treeModel = DefaultTreeModel(treeRootNode)
    val tree = Tree(treeModel)
    internal val viewCardLayout = CardLayout()
    internal val viewPanel = JPanel(viewCardLayout)
    var viewMode: ViewMode = try {
        ViewMode.valueOf(TurtleCommanderSettings.getInstance().state.defaultViewMode)
    } catch (_: Exception) {
        ViewMode.TABLE
    }
        internal set

    private val driveCombo = ComboBox<String>()
    private val pathField = BreadcrumbPathField()
    private val statusLabel = JLabel(" ")
    private val freeSpaceLabel = JLabel(" ")
    private var freeSpaceLastPath: Path? = null

    internal fun invalidateFreeSpaceCache() {
        freeSpaceLastPath = null
    }
    private val statusPanel = JPanel(BorderLayout())
    private var allEntries: List<FileEntry> = emptyList()

    /** [allEntries] after the quick filter; identical to it when no filter is active. */
    private var filteredEntries: List<FileEntry> = emptyList()
    private val quickFilterBar = QuickFilterBar(
        onFilterChanged = { applyFilter() },
        onHideRequested = { hideQuickFilter() },
        onMoveSelection = { offset -> moveSelection(offset) },
        onCommit = { focusActiveView() },
    )
    private var updatingDriveCombo = false
    private var driveComboPopupOpen = false
    private var unsubscribeDriveRoots: (() -> Unit)? = null
    // Roots update that arrived while the combo popup was open or the tab was inside an
    // archive — applied once the combo is safe to mutate again.
    private var pendingDriveRoots: List<String>? = null
    private val cursorPositions = mutableMapOf<Path, Int>()
    private data class TabColumnState(val widths: List<Int>, val order: List<Int>)
    private val tabColumnStates = mutableMapOf<String, TabColumnState>()
    private var cachedFilterGlob: String? = null
    private var cachedFilterMatcher: PathMatcher? = null
    internal var stateService: FileManagerStateService? = null
    private var initialized = false
    internal var pendingTabState: FileManagerStateService.TabState? = null
    internal var enableFileNameHighlighting = TurtleCommanderSettings.getInstance().state.enableFileNameHighlighting
    private var nameEditor: DefaultCellEditor? = null

    // Explorer-style "click the name of the already-selected item to rename": a click only
    // arms a row once it is the sole selection; the *next* single click on that same name
    // starts the rename. [renameArmedRow] is the armed view row (-1 = none) and is cleared
    // whenever the selection changes. [renameClickTimer] defers the rename by the double-click
    // interval so a double-click (which opens) cancels it instead.
    private var renameArmedRow = -1
    private var renameClickTimer: Timer? = null

    var currentPath: Path = initialPath
        private set

    var showAllNestedFiles: Boolean = false
        internal set

    internal val vfsStack = mutableListOf<VfsStackEntry>()

    /**
     * Serializes VFS write-back work across [refresh], [refreshAfterVfsChange],
     * [writeBackNestedArchives] and [io.github.jhspetersson.turtlecommander.vfs.VfsEditService.writeBack]
     * (the edited-file save path). All of these walk [vfsStack] calling `vfs.flush()` on parent
     * ZipFileSystems and copying temp files into ZipPaths; running any two concurrently would
     * close a parent fs underneath the other's in-flight `Files.copy`, producing
     * `ClosedFileSystemException`.
     */
    internal val vfsWriteMutex = Mutex()

    var currentVfs: VirtualFileSystem?
        get() = vfsStack.lastOrNull()?.vfs
        private set(_) {}  // managed via stack

    val isInsideArchive: Boolean
        get() = vfsStack.isNotEmpty()

    /** The real filesystem path: the directory containing the archive when inside VFS, otherwise currentPath. */
    val realFilesystemPath: Path
        get() = if (vfsStack.isNotEmpty()) vfsStack.first().parentPath else currentPath



    init {
        tableModel.directorySizeProvider = { path -> directorySizes[path] }
        setupHeader()
        setupTable()
        setupList()
        setupThumbnailList()
        setupTree()
        registerQuickFilterShortcut()
        setupViewPanel()
        if (!TurtleCommanderSettings.getInstance().state.hideDriveSelector) {
            startDriveUpdates()
        }
        applyPanelFont()
        applyVisibilitySettings()
        // Route BasicTabbedPaneUI's post-selection focus transfer to the active view instead
        // of the drive combo (which would otherwise be the first focusable descendant).
        isFocusCycleRoot = true
        focusTraversalPolicy = object : LayoutFocusTraversalPolicy() {
            override fun getDefaultComponent(aContainer: Container?): Component = when (viewMode) {
                ViewMode.TABLE -> table
                ViewMode.LIST -> list
                ViewMode.THUMBNAIL -> thumbnailList
                ViewMode.TREE -> tree
            }
        }
    }

    fun applyVisibilitySettings() {
        val settings = TurtleCommanderSettings.getInstance().state
        driveCombo.isVisible = !settings.hideDriveSelector
        statusPanel.isVisible = !settings.hideStatusBar
        enableFileNameHighlighting = settings.enableFileNameHighlighting
        if (settings.hideDriveSelector) {
            stopDriveUpdates()
        } else {
            startDriveUpdates()
        }
    }

    fun applyThumbnailSettings() {
        val size = ThumbnailSize.fromName(TurtleCommanderSettings.getInstance().state.thumbnailSize)
        thumbnailList.fixedCellWidth = size.cellW
        thumbnailList.fixedCellHeight = size.cellH
        thumbnailList.revalidate()
        thumbnailList.repaint()
    }

    fun applyPanelFont() {
        val settings = TurtleCommanderSettings.getInstance()
        val font = settings.getPanelFont()
        val size = settings.getPanelFontSize()
        if (font != null) {
            table.font = font
            table.rowHeight = font.size + 6
            list.font = font
            list.fixedCellHeight = font.size + 6
            thumbnailList.font = font
            tree.font = font
            tree.rowHeight = font.size + 6
        } else if (size > 0) {
            table.font = defaultTableFont.deriveFont(size.toFloat())
            table.rowHeight = size + 6
            list.font = defaultTableFont.deriveFont(size.toFloat())
            list.fixedCellHeight = size + 6
            thumbnailList.font = defaultTableFont.deriveFont(size.toFloat())
            tree.font = defaultTableFont.deriveFont(size.toFloat())
            tree.rowHeight = size + 6
        } else {
            table.font = defaultTableFont
            table.rowHeight = 20
            list.font = defaultTableFont
            list.fixedCellHeight = 20
            thumbnailList.font = defaultTableFont
            tree.font = defaultTableFont
            tree.rowHeight = 20
        }

        val panelStyle = settings.state.styles.panelStyle
        val fg = panelStyle.parsedFontColor() ?: defaultTableFg
        table.foreground = fg
        list.foreground = fg
        thumbnailList.foreground = fg
        tree.foreground = fg

        val bg = panelStyle.parsedBackgroundColor() ?: defaultTableBg
        table.background = bg
        list.background = bg
        thumbnailList.background = bg
        tree.background = bg
        (table.parent as? JViewport)?.background = bg

        val activeSel = panelStyle.parsedActiveSelectedColor() ?: defaultSelectionBg
        table.selectionBackground = activeSel
        list.selectionBackground = activeSel
        thumbnailList.selectionBackground = activeSel

        applyDriveSelectorStyle()
        applyColumnHeaderStyle()
        applyStatusBarStyle()
        applyPathBarStyle()
        reapplyColumnConfig()
    }

    private fun reapplyColumnConfig() {
        val cm = table.columnModel
        // Save current column order and widths before rebuilding
        val savedOrder = mutableListOf<Int>()
        val savedWidths = mutableMapOf<Int, Int>()
        for (i in 0 until cm.columnCount) {
            val col = cm.getColumn(i)
            savedOrder.add(col.modelIndex)
            savedWidths[col.modelIndex] = col.width
        }

        // Rebuild column model from scratch: add back all model columns, then apply config
        while (cm.columnCount > 0) {
            table.removeColumn(cm.getColumn(0))
        }
        for (i in 0 until tableModel.columnCount) {
            val tc = TableColumn(i)
            tc.headerValue = tableModel.getColumnName(i)
            table.addColumn(tc)
        }
        applyColumnConfig(this)

        // Restore previous column order (only for columns that are still visible)
        if (savedOrder.isNotEmpty()) {
            val visibleModelIndices = mutableSetOf<Int>()
            for (i in 0 until cm.columnCount) {
                visibleModelIndices.add(cm.getColumn(i).modelIndex)
            }
            // Filter saved order to only include still-visible columns
            val filteredOrder = savedOrder.filter { it in visibleModelIndices }
            // Add any newly visible columns not in saved order at the end
            val newColumns = visibleModelIndices.filter { it !in savedOrder }
            val targetOrder = filteredOrder + newColumns

            if (targetOrder.size == cm.columnCount) {
                for (targetIdx in targetOrder.indices) {
                    val wantedModelIdx = targetOrder[targetIdx]
                    var currentViewIdx = -1
                    for (viewIdx in 0 until cm.columnCount) {
                        if (cm.getColumn(viewIdx).modelIndex == wantedModelIdx) {
                            currentViewIdx = viewIdx
                            break
                        }
                    }
                    if (currentViewIdx >= 0 && currentViewIdx != targetIdx) {
                        cm.moveColumn(currentViewIdx, targetIdx)
                    }
                }
            }
            // Restore widths for columns that existed before
            for (i in 0 until cm.columnCount) {
                val col = cm.getColumn(i)
                val savedWidth = savedWidths[col.modelIndex]
                if (savedWidth != null) {
                    col.preferredWidth = savedWidth
                    col.width = savedWidth
                }
            }
        }

        assignNameEditor()
    }

    private fun assignNameEditor() {
        val editor = nameEditor ?: return
        val cm = table.columnModel
        for (i in 0 until cm.columnCount) {
            if (cm.getColumn(i).modelIndex == FileTableModel.COL_NAME) {
                cm.getColumn(i).cellEditor = editor
                break
            }
        }
    }

    private fun setupHeaderContextMenu() {
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { showPopup(e) }
            override fun mouseReleased(e: MouseEvent) { showPopup(e) }
            private fun showPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val settings = TurtleCommanderSettings.getInstance()
                val columns = settings.getEffectiveColumns()
                val columnsById = columns.associateBy { it.id }

                // Build ordered list: visible columns in current table view order, then hidden columns
                val modelIndexToName = COLUMN_NAME_TO_MODEL_INDEX.entries.associate { (k, v) -> v to k }
                val cm = table.columnModel
                val visibleOrder = (0 until cm.columnCount).mapNotNull { modelIndexToName[cm.getColumn(it).modelIndex] }
                val hiddenOrder = columns.filter { !it.visible }.map { it.id }
                val ordered = visibleOrder + hiddenOrder

                val visibleCount = columns.count { it.visible }
                val popup = JPopupMenu()
                for (id in ordered) {
                    val col = columnsById[id] ?: continue
                    val item = JMenuItem(col.id)
                    item.icon = if (col.visible) AllIcons.Actions.Checked else null
                    if (col.visible && visibleCount <= 1) item.isEnabled = false
                    item.addActionListener {
                        col.visible = !col.visible
                        settings.state.columns.clear()
                        settings.state.columns.addAll(columns.map { c ->
                            ColumnConfig().apply {
                                this.id = c.id
                                visible = c.visible
                                style.copyFrom(c.style)
                            }
                        })
                        reapplyColumnConfig()
                        settings.fireSettingsChanged()
                    }
                    popup.add(item)
                }
                popup.show(e.component, e.x, e.y)
            }
        })
    }

    private fun setupHeaderResizeCursor() {
        val header = table.tableHeader ?: return
        val resizeZone = 3
        val hoverCursor = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
        val dragCursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        val cursorKey = Any()

        fun nearColumnBoundary(x: Int): Boolean {
            val cm = header.columnModel ?: return false
            var edge = 0
            for (i in 0 until cm.columnCount) {
                edge += cm.getColumn(i).width
                if (kotlin.math.abs(x - edge) <= resizeZone) return true
            }
            return false
        }

        // IntelliJ's IdeGlassPane sits above every tool window and owns cursor rendering —
        // calling Component.setCursor on the header has no visible effect because the glass
        // pane's cursor takes priority. IdeGlassPane.setCursor(cursor, key) is the official
        // way to request a cursor, keyed by an arbitrary object so different requestors
        // don't stomp each other. Passing a null cursor clears our request.
        fun requestCursor(cursor: Cursor?) {
            val glass = runCatching { IdeGlassPaneUtil.find(header) }.getOrNull() ?: return
            glass.setCursor(cursor, cursorKey)
        }

        header.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                requestCursor(if (nearColumnBoundary(e.x)) hoverCursor else null)
            }

            override fun mouseDragged(e: MouseEvent) {
                if (header.resizingColumn != null) requestCursor(dragCursor)
            }
        })
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                requestCursor(if (nearColumnBoundary(e.x)) hoverCursor else null)
            }

            override fun mouseExited(e: MouseEvent) {
                if (header.resizingColumn == null) requestCursor(null)
            }
        })
    }

    private fun applyDriveSelectorStyle() {
        val style = TurtleCommanderSettings.getInstance().state.styles.driveSelectorStyle
        driveCombo.font = style.getFont(defaultDriveComboFont) ?: defaultDriveComboFont
        driveCombo.foreground = style.parsedFontColor() ?: defaultDriveComboFg
        driveCombo.background = style.parsedBackgroundColor() ?: defaultDriveComboBg
    }

    private fun applyColumnHeaderStyle() {
        val style = TurtleCommanderSettings.getInstance().state.styles.columnHeaderStyle
        val header = table.tableHeader ?: return
        header.font = style.getFont(defaultHeaderFont) ?: defaultHeaderFont
        header.foreground = style.parsedFontColor() ?: defaultHeaderFg
        header.background = style.parsedBackgroundColor() ?: defaultHeaderBg
    }

    private fun applyStatusBarStyle() {
        val style = TurtleCommanderSettings.getInstance().state.styles.statusBarStyle
        val font = style.getFont(defaultStatusFont) ?: defaultStatusFont
        statusLabel.font = font
        freeSpaceLabel.font = font
        val fg = style.parsedFontColor() ?: defaultStatusFg
        statusLabel.foreground = fg
        freeSpaceLabel.foreground = fg
        val bg = style.parsedBackgroundColor() ?: defaultStatusBg
        statusPanel.background = bg
        statusLabel.background = bg
        freeSpaceLabel.background = bg
    }

    private fun applyPathBarStyle() {
        val style = TurtleCommanderSettings.getInstance().state.styles.pathBarStyle
        pathField.applyStyle(style)
    }

    private fun setupHeader() {
        val headerPanel = JPanel(BorderLayout())

        driveCombo.apply {
            renderer = DriveComboRenderer()
            addPopupMenuListener(object : PopupMenuListener {
                private var selectionOnOpen: String? = null

                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                    driveComboPopupOpen = true
                    selectionOnOpen = selectedItem as? String
                }
                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                    driveComboPopupOpen = false
                    flushPendingDriveRootsLater()
                    if (updatingDriveCombo) return
                    val selected = selectedItem as? String ?: return
                    // Only act on a real change of drive; a dismissed dropdown keeps the selection.
                    if (selected == selectionOnOpen) {
                        table.requestFocusInWindow()
                        return
                    }
                    // Use the shared poller's cache — calling getRoots() here would do
                    // File.listRoots() on the EDT, which can block on network drives.
                    val availableRoots = (fileOps.currentRoots() ?: emptyList()).map { Path.of(it) }
                    val targetPath = resolveDriveSelectionTarget(Path.of(selected), otherPanelPathProvider(), availableRoots)
                    // Exit VFS if active
                    if (vfsStack.isNotEmpty()) {
                        closeVfsStackAsync()
                    }
                    if (targetPath != currentPath) {
                        fileOps.launch {
                            navigateTo(targetPath)
                        }
                    }
                    table.requestFocusInWindow()
                }
                override fun popupMenuCanceled(e: PopupMenuEvent) {
                    driveComboPopupOpen = false
                    flushPendingDriveRootsLater()
                    table.requestFocusInWindow()
                }
            })
        }

        pathField.apply {
            onSegmentClick = { segmentPath ->
                if (vfsStack.isNotEmpty()) {
                    handleVfsBreadcrumbClick(segmentPath)
                } else {
                    val path = try { Path.of(segmentPath) } catch (_: Exception) { null }
                    if (path != null) {
                        // Stat off the EDT: a dead network path can block for many seconds.
                        fileOps.launch {
                            if (withContext(Dispatchers.IO) { Files.isDirectory(path) }) {
                                navigateTo(path)
                            }
                        }
                    }
                }
            }
            addActionListener {
                if (currentVfs != null) {
                    // In VFS mode, ignore path field edits
                    table.requestFocusInWindow()
                    return@addActionListener
                }
                val typed = try {
                    val p = Path.of(text)
                    if (p.isAbsolute) p else currentPath
                } catch (_: Exception) { currentPath }
                val fallback = currentPath
                // The ancestor walk stats each level; on a dead UNC path every stat can
                // block for the SMB timeout, so the whole resolution runs off the EDT.
                fileOps.launch {
                    val path = withContext(Dispatchers.IO) {
                        var p: Path? = typed
                        while (p != null && !Files.isDirectory(p)) {
                            p = p.parent
                        }
                        p ?: fallback
                    }
                    withContext(Dispatchers.EDT) { pathField.text = path.toString() }
                    navigateTo(path)
                }
                table.requestFocusInWindow()
            }
        }

        headerPanel.add(driveCombo, BorderLayout.WEST)
        headerPanel.add(pathField, BorderLayout.CENTER)
        add(headerPanel, BorderLayout.NORTH)
    }

    private fun setupTable() {
        table.apply {
            setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            setCellSelectionEnabled(false)
            setRowSelectionAllowed(true)
            autoResizeMode = JBTable.AUTO_RESIZE_OFF
            rowHeight = 20

            applyColumnConfig(this@FileTab)
            setupHeaderContextMenu()
            setupHeaderResizeCursor()

            rowSorter = ParentPinningRowSorter(tableModel)

            var editingEntry: FileEntry? = null
            nameEditor = object : DefaultCellEditor(JTextField().apply { installStandardContextMenu() }) {
                init {
                    clickCountToStart = Int.MAX_VALUE
                }

                override fun getTableCellEditorComponent(
                    table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
                ): Component {
                    val modelRow = table.convertRowIndexToModel(row)
                    val entry = tableModel.getEntryAt(modelRow)
                    editingEntry = entry
                    val fullName = entry?.name ?: value?.toString() ?: ""
                    return super.getTableCellEditorComponent(table, fullName, isSelected, row, column)
                }
            }
            nameEditor!!.addCellEditorListener(object : CellEditorListener {
                override fun editingStopped(e: ChangeEvent) {
                    val entry = editingEntry ?: return
                    editingEntry = null
                    val newName = (nameEditor!!.cellEditorValue as? String)?.trim() ?: return
                    if (newName.isNotEmpty() && newName != entry.name) {
                        performRename(entry, newName)
                    }
                }

                override fun editingCanceled(e: ChangeEvent) {
                    editingEntry = null
                }
            })
            assignNameEditor()

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        // A double-click opens; cancel any rename armed by its first click.
                        renameClickTimer?.stop()
                        openSelectedEntry()
                        return
                    }
                    if (e.clickCount == 1) {
                        if (maybeSingleClickOpenDirectory(e, table.rowAtPoint(e.point) >= 0)) return
                        maybeArmOrStartRename(e)
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    // Any new gesture cancels a pending click-to-rename.
                    renameClickTimer?.stop()
                    handleTableContextMenu(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    handleTableContextMenu(e)
                }
            })

            addFocusListener(object : FocusListener {
                override fun focusGained(e: FocusEvent) {
                    repaint()
                }
                override fun focusLost(e: FocusEvent) {
                    repaint()
                }
            })

            dragEnabled = true
            transferHandler = FileEntryTransferHandler(this@FileTab)

            selectionModel.addListSelectionListener {
                if (!insideToggle && !insideRestore && !insideViewSwitch && markedPaths.isNotEmpty()) {
                    restoreTableMarks()
                }
                // A selection change means this click (re)selected a row rather than landing on an
                // already-selected one, so disarm click-to-rename and drop any pending rename.
                renameArmedRow = -1
                renameClickTimer?.stop()
                updateStatusBar()
            }
        }
    }

    /**
     * Single-click-to-rename: a click only *arms* the row it leaves as the sole selection; the
     * next single left-click on that same already-selected name starts an inline rename. The
     * rename is deferred by the system double-click interval so a double-click (which opens the
     * entry) cancels it, and any selection change or new mouse gesture disarms it.
     */
    private fun maybeArmOrStartRename(e: MouseEvent) {
        if (!SwingUtilities.isLeftMouseButton(e)) return
        // Ctrl/Shift/Alt/Meta clicks are multi-select gestures, not rename intents.
        val modifierMask = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK or
            InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK
        if (e.modifiersEx and modifierMask != 0) return

        val row = table.rowAtPoint(e.point)
        val col = table.columnAtPoint(e.point)
        val onNameCell = row >= 0 && col >= 0 &&
            table.convertColumnIndexToModel(col) == FileTableModel.COL_NAME
        val soleSelection = table.selectedRowCount == 1 && table.selectedRow == row

        if (onNameCell && soleSelection && row == renameArmedRow && canRenameRow(row)) {
            renameClickTimer?.stop()
            renameClickTimer = Timer(doubleClickInterval()) {
                // Re-check: the user may have moved on (keyboard nav, another click, a view
                // switch) during the deferral window.
                if (viewMode == ViewMode.TABLE && table.selectedRowCount == 1 && table.selectedRow == row) {
                    startRename()
                }
            }.apply { isRepeats = false; start() }
        }
        renameArmedRow = if (soleSelection) row else -1
    }

    /**
     * Single-click-to-open: when the "open directories with one click" setting is on, a plain
     * left-click that lands on a row opens it if (and only if) it's a directory or the parent
     * link. Files are left alone so they keep their normal single-click behavior (selection /
     * click-to-rename). Returns true when it consumed the click by opening, so the caller can
     * skip its own handling. Modifier-clicks (Ctrl/Shift/Alt/Meta) are multi-select gestures
     * and never open.
     */
    private fun maybeSingleClickOpenDirectory(e: MouseEvent, onItem: Boolean): Boolean {
        if (!onItem) return false
        if (!TurtleCommanderSettings.getInstance().state.openDirectoriesWithSingleClick) return false
        if (!SwingUtilities.isLeftMouseButton(e)) return false
        val modifierMask = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK or
            InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK
        if (e.modifiersEx and modifierMask != 0) return false
        val entry = getSelectedEntry() ?: return false
        if (entry.isParentLink || entry.isDirectory) {
            openSelectedEntry()
            return true
        }
        return false
    }

    private fun canRenameRow(viewRow: Int): Boolean {
        val entry = tableModel.getEntryAt(table.convertRowIndexToModel(viewRow)) ?: return false
        return !entry.isParentLink
    }

    private fun doubleClickInterval(): Int {
        val v = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int
        return (v ?: 400).coerceIn(200, 800)
    }

    private fun setupList() {
        list.apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            layoutOrientation = JList.VERTICAL_WRAP
            visibleRowCount = 0
            cellRenderer = FileListCellRenderer(this@FileTab)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        openSelectedEntry()
                    } else if (e.clickCount == 1) {
                        val idx = locationToIndex(e.point)
                        val onItem = idx >= 0 && getCellBounds(idx, idx)?.contains(e.point) == true
                        maybeSingleClickOpenDirectory(e, onItem)
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    handleListContextMenu(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    handleListContextMenu(e)
                }
            })

            dragEnabled = true
            transferHandler = FileEntryTransferHandler(this@FileTab)

            addListSelectionListener {
                if (!insideToggle && !insideRestore && !insideViewSwitch && markedPaths.isNotEmpty()) {
                    restoreListMarks(list, listModel)
                }
                updateStatusBar()
                // VERTICAL_WRAP JList doesn't auto-scroll on programmatic selection
                // changes (e.g. speed-search Up/Down jumps), so force it here.
                if (SpeedSearchSupply.getSupply(list) != null) {
                    val idx = list.selectedIndex
                    if (idx >= 0) list.ensureIndexIsVisible(idx)
                }
            }

            installHomeEndBindings(this)
            installToggleSelectionBinding(this)
        }
    }

    private fun installToggleSelectionBinding(component: JComponent) {
        val spaceAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                toggleSelection()
            }
        }
        spaceAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), null)),
            component,
        )
        val insertAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                toggleSelectionAndMoveDown()
            }
        }
        insertAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), null)),
            component,
        )
    }

    private fun setupThumbnailList() {
        val initialSize = ThumbnailSize.fromName(TurtleCommanderSettings.getInstance().state.thumbnailSize)
        thumbnailList.apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            layoutOrientation = JList.HORIZONTAL_WRAP
            visibleRowCount = 0
            fixedCellWidth = initialSize.cellW
            fixedCellHeight = initialSize.cellH
            cellRenderer = FileThumbnailCellRenderer(this@FileTab)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        openSelectedEntry()
                    } else if (e.clickCount == 1) {
                        val idx = locationToIndex(e.point)
                        val onItem = idx >= 0 && getCellBounds(idx, idx)?.contains(e.point) == true
                        maybeSingleClickOpenDirectory(e, onItem)
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    handleThumbnailContextMenu(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    handleThumbnailContextMenu(e)
                }
            })

            dragEnabled = true
            transferHandler = FileEntryTransferHandler(this@FileTab)

            addListSelectionListener {
                if (!insideToggle && !insideRestore && !insideViewSwitch && markedPaths.isNotEmpty()) {
                    restoreListMarks(thumbnailList, thumbnailListModel)
                }
                updateStatusBar()
                if (SpeedSearchSupply.getSupply(thumbnailList) != null) {
                    val idx = thumbnailList.selectedIndex
                    if (idx >= 0) thumbnailList.ensureIndexIsVisible(idx)
                }
            }

            installHomeEndBindings(this)
            installToggleSelectionBinding(this)
        }
    }

    private fun installHomeEndBindings(jlist: JList<*>) {
        val selectFirst = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (jlist.model.size > 0) {
                    jlist.selectedIndex = 0
                    jlist.ensureIndexIsVisible(0)
                }
            }
        }
        val selectLast = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val last = jlist.model.size - 1
                if (last >= 0) {
                    jlist.selectedIndex = last
                    jlist.ensureIndexIsVisible(last)
                }
            }
        }
        val selectFirstExtend = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (jlist.model.size > 0) {
                    val anchor = jlist.selectionModel.anchorSelectionIndex.coerceAtLeast(0)
                    jlist.selectionModel.setSelectionInterval(anchor, 0)
                    jlist.ensureIndexIsVisible(0)
                }
            }
        }
        val selectLastExtend = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val last = jlist.model.size - 1
                if (last >= 0) {
                    val anchor = jlist.selectionModel.anchorSelectionIndex.coerceAtLeast(0)
                    jlist.selectionModel.setSelectionInterval(anchor, last)
                    jlist.ensureIndexIsVisible(last)
                }
            }
        }
        selectFirst.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0)), jlist
        )
        selectLast.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_END, 0)), jlist
        )
        selectFirstExtend.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, InputEvent.SHIFT_DOWN_MASK)), jlist
        )
        selectLastExtend.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_END, InputEvent.SHIFT_DOWN_MASK)), jlist
        )
    }

    private fun setupTree() {
        tree.apply {
            isRootVisible = false
            showsRootHandles = true
            background = table.background
            dragEnabled = true
            transferHandler = FileEntryTransferHandler(this@FileTab)

            selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
            cellRenderer = FileTreeCellRenderer(this@FileTab)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                        val entry = node.userObject as? FileEntry ?: return
                        if (entry.isParentLink) {
                            goUp()
                        } else if (entry.isDirectory) {
                            // Handled by tree expansion
                        } else if (isEntryBrowsableArchive(entry)) {
                            enterVfs(entry)
                        } else {
                            openFile(entry)
                        }
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    handleTreeContextMenu(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    handleTreeContextMenu(e)
                }
            })

            addTreeSelectionListener {
                if (!insideToggle && !insideRestore && !insideViewSwitch && markedPaths.isNotEmpty()) {
                    restoreTreeMarks()
                }
                updateStatusBar()
            }

            addTreeWillExpandListener(object : TreeWillExpandListener {
                override fun treeWillExpand(event: TreeExpansionEvent) {
                    val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val entry = node.userObject as? FileEntry ?: return
                    if (entry.isDirectory && !entry.isParentLink && node.childCount == 1) {
                        val firstChild = node.getChildAt(0) as? DefaultMutableTreeNode
                        if (firstChild?.userObject is String) {
                            // Loading placeholder — load real children asynchronously
                            fileOps.launch {
                                try {
                                    val children = withContext(Dispatchers.IO) {
                                        val vfs = currentVfs
                                        vfs?.listFiles(entry.path) ?: fileOps.listFiles(entry.path)
                                    }
                                    withContext(Dispatchers.EDT) {
                                        node.removeAllChildren()
                                        for (child in children) {
                                            if (child.isParentLink) continue
                                            val childNode = DefaultMutableTreeNode(child)
                                            if (child.isDirectory) {
                                                childNode.add(DefaultMutableTreeNode("Loading..."))
                                            }
                                            node.add(childNode)
                                        }
                                        treeModel.nodeStructureChanged(node)
                                    }
                                } catch (_: Exception) {
                                    // ignore
                                }
                            }
                        }
                    }
                }

                override fun treeWillCollapse(event: TreeExpansionEvent) {}
            })
        }

        // Register SPACE and INSERT as component-local IntelliJ actions on the tree.
        // This takes priority over both global IntelliJ actions and JTree's default Swing bindings.
        // SPACE toggles the mark in place; INSERT toggles the mark and moves the cursor down.
        val spaceTreeAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                toggleSelection()
            }
        }
        spaceTreeAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), null)),
            tree,
        )
        val insertTreeAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                toggleSelectionAndMoveDown()
            }
        }
        insertTreeAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), null)),
            tree,
        )
    }


    private fun registerQuickFilterShortcut() {
        // Register Ctrl-S as component-local action on all view components
        // to override IntelliJ's global "Save All" binding
        val filterShortcut = CustomShortcutSet(
            KeyboardShortcut(
                KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), null
            ),
        )
        val filterAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                showQuickFilter()
            }
        }
        filterAction.registerCustomShortcutSet(filterShortcut, table)
        filterAction.registerCustomShortcutSet(filterShortcut, list)
        filterAction.registerCustomShortcutSet(filterShortcut, thumbnailList)
        filterAction.registerCustomShortcutSet(filterShortcut, tree)
    }

    fun showQuickFilter() {
        if (viewMode == ViewMode.TREE && !showAllNestedFiles) {
            // The hierarchical tree can't be name-filtered (a flat glob doesn't map onto a
            // lazily-loaded hierarchy), so refuse instead of showing a bar that does nothing.
            statusLabel.text = "Quick filter is not available in the directory tree"
            return
        }
        quickFilterBar.activate()
        revalidate()
    }

    internal fun hideQuickFilterIfActive() {
        if (quickFilterBar.isVisible) hideQuickFilter()
    }

    internal fun isQuickFilterActive(): Boolean = quickFilterBar.isVisible

    /**
     * Drives the quick filter the way typing does: activates the bar through [showQuickFilter]
     * (so the full-hierarchy-tree refusal applies) and sets the pattern, firing the same
     * document event a user's keystrokes would. A no-op when activation was refused.
     */
    internal fun setQuickFilterText(text: String) {
        showQuickFilter()
        if (quickFilterBar.isVisible) quickFilterBar.text = text
    }

    private fun hideQuickFilter() {
        quickFilterBar.deactivate()
        // deactivate() fires no document event when the field was already empty, so
        // re-apply explicitly to guarantee the unfiltered listing is restored.
        applyFilter()
        revalidate()
        focusActiveView()
    }

    private fun focusActiveView() {
        when (viewMode) {
            ViewMode.TABLE -> table.requestFocusInWindow()
            ViewMode.LIST -> list.requestFocusInWindow()
            ViewMode.THUMBNAIL -> thumbnailList.requestFocusInWindow()
            ViewMode.TREE -> tree.requestFocusInWindow()
        }
    }

    fun reSort() {
        (table.rowSorter as? ParentPinningRowSorter)?.sort()
    }

    private fun applyFilter() {
        val pattern = quickFilterBar.text.trim()
        val filtered = if (pattern.isEmpty()) {
            cachedFilterGlob = null
            cachedFilterMatcher = null
            quickFilterBar.setError(false)
            allEntries
        } else {
            val glob = wrapAsSubstringGlobIfPlain(pattern)
            val cached = cachedFilterMatcher
            val matcher = if (glob == cachedFilterGlob && cached != null) {
                quickFilterBar.setError(false)
                cached
            } else {
                val m = try {
                    FileSystems.getDefault().getPathMatcher("glob:$glob")
                } catch (_: Exception) {
                    // Invalid glob: flag the field so the user knows something is wrong,
                    // drop the stale matcher cache, and fall back to showing everything
                    // instead of freezing the view on the previous filter's output.
                    cachedFilterGlob = null
                    cachedFilterMatcher = null
                    quickFilterBar.setError(true)
                    null
                }
                if (m != null) {
                    cachedFilterGlob = glob
                    cachedFilterMatcher = m
                    quickFilterBar.setError(false)
                }
                m
            }
            if (matcher == null) {
                allEntries
            } else {
                allEntries.filter { entry ->
                    entry.isParentLink || matcher.matches(Path.of(entry.name))
                }
            }
        }

        val selectedName = getSelectedEntry()?.name

        filteredEntries = filtered
        tableModel.setEntries(filtered)

        listModel.clear()
        listModel.addAll(filtered)

        thumbnailListModel.clear()
        thumbnailListModel.addAll(filtered)

        // The full-hierarchy tree (TREE view without show-all-nested-files) is the one view a
        // flat name filter can't map onto, so it is left alone — showQuickFilter() refuses to
        // open the bar there and setViewMode() drops an active filter on the way in. Every
        // other case shows the flat tree, which filters like the rest.
        if (viewMode != ViewMode.TREE || showAllNestedFiles) {
            populateFlatTree()
        }

        val selIdx = findPreservedSelectionIndex(filtered, selectedName)
        if (selIdx >= 0 && table.rowCount > 0) {
            val viewRow = table.convertRowIndexToView(selIdx.coerceAtMost(tableModel.rowCount - 1))
            table.setRowSelectionInterval(viewRow, viewRow)
        }
        if (selIdx >= 0 && listModel.size() > 0) list.selectedIndex = selIdx.coerceAtMost(listModel.size() - 1)
        if (selIdx >= 0 && thumbnailListModel.size() > 0) thumbnailList.selectedIndex = selIdx.coerceAtMost(thumbnailListModel.size() - 1)
        if (selIdx >= 0 && viewMode == ViewMode.TREE && showAllNestedFiles && tree.rowCount > 0) {
            val row = selIdx.coerceAtMost(tree.rowCount - 1)
            tree.setSelectionRow(row)
            tree.scrollRowToVisible(row)
        }

        updateStatusBar()
    }

    internal fun moveSelection(offset: Int) {
        when (viewMode) {
            ViewMode.TABLE -> {
                val current = table.selectedRow
                val next = (current + offset).coerceIn(0, table.rowCount - 1)
                if (next >= 0) {
                    table.setRowSelectionInterval(next, next)
                    table.scrollRectToVisible(table.getCellRect(next, 0, true))
                }
            }
            ViewMode.LIST -> {
                val current = list.selectedIndex
                val next = (current + offset).coerceIn(0, listModel.size() - 1)
                if (next >= 0) {
                    list.selectedIndex = next
                    list.ensureIndexIsVisible(next)
                }
            }
            ViewMode.THUMBNAIL -> {
                val current = thumbnailList.selectedIndex
                val next = (current + offset).coerceIn(0, thumbnailListModel.size() - 1)
                if (next >= 0) {
                    thumbnailList.selectedIndex = next
                    thumbnailList.ensureIndexIsVisible(next)
                }
            }
            ViewMode.TREE -> {
                val current = tree.leadSelectionRow
                val next = (current + offset).coerceIn(0, tree.rowCount - 1)
                if (next >= 0) {
                    tree.setSelectionRow(next)
                    tree.scrollRowToVisible(next)
                }
            }
        }
    }

    internal var tableSpeedSearch: TableSpeedSearch? = null
    internal var listSpeedSearch: ListSpeedSearch<FileEntry>? = null
    internal var thumbnailSpeedSearch: ListSpeedSearch<FileEntry>? = null
    internal var treeSpeedSearch: TreeSpeedSearch? = null

    private fun installSpeedSearch() {
        // IntelliJ-style speed search: typing in a focused view opens a small search
        // popup, matched substrings in filenames are highlighted, and Up/Down jump
        // between matches. Esc cancels. Converter returns the display name of each
        // entry so non-visible columns (size/date) don't pollute matches.
        tableSpeedSearch = TableSpeedSearch.installOn(table) { value ->
            (value as? FileEntry)?.name ?: value?.toString().orEmpty()
        }
        listSpeedSearch = ListSpeedSearch.installOn(list) { entry -> entry.name }
        thumbnailSpeedSearch = ListSpeedSearch.installOn(thumbnailList) { entry -> entry.name }
        treeSpeedSearch = TreeSpeedSearch.installOn(tree, true) { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode
            (node?.userObject as? FileEntry)?.name ?: node?.userObject?.toString().orEmpty()
        }
    }

    private fun setupViewPanel() {
        viewPanel.add(JBScrollPane(table), VIEW_TABLE)
        viewPanel.add(JBScrollPane(list), VIEW_LIST)
        viewPanel.add(JBScrollPane(thumbnailList), VIEW_THUMBNAIL)
        viewPanel.add(JBScrollPane(tree), VIEW_TREE)
        installSpeedSearch()
        val initialCard = when (viewMode) {
            ViewMode.TABLE -> VIEW_TABLE
            ViewMode.LIST -> VIEW_LIST
            ViewMode.THUMBNAIL -> VIEW_THUMBNAIL
            ViewMode.TREE -> VIEW_TREE
        }
        viewCardLayout.show(viewPanel, initialCard)

        add(viewPanel, BorderLayout.CENTER)

        statusLabel.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        freeSpaceLabel.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        freeSpaceLabel.cursor = Cursor(Cursor.HAND_CURSOR)
        freeSpaceLabel.toolTipText = "Click to view drive space"
        freeSpaceLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    val drives = collectDriveInfo()
                    ApplicationManager.getApplication().invokeLater {
                        DriveSpaceDialog(project, drives).show()
                    }
                }
            }
        })
        statusPanel.add(statusLabel, BorderLayout.WEST)
        statusPanel.add(freeSpaceLabel, BorderLayout.EAST)

        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(quickFilterBar, BorderLayout.NORTH)
        bottomPanel.add(statusPanel, BorderLayout.SOUTH)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    internal fun updateStatusBar() {
        val entries = tableModel.let { model ->
            (0 until model.rowCount).mapNotNull { model.getEntryAt(it) }
        }.filter { !it.isParentLink }

        val dirs = entries.count { it.isDirectory }
        val files = entries.count { !it.isDirectory }
        val totalSize = entries.filter { !it.isDirectory }.sumOf { it.size }

        val sb = StringBuilder()
        sb.append("$dirs dir(s), $files file(s), ${formatSize(totalSize)}")

        val selectedEntries = getSelectedEntries()
        if (selectedEntries.isNotEmpty()) {
            val selectedSize = selectedEntries.sumOf { entry ->
                if (entry.isDirectory) directorySizes[entry.path] ?: 0L else entry.size
            }
            sb.append("  |  ${selectedEntries.size} selected, ${formatSize(selectedSize)}")
        }

        statusLabel.text = sb.toString()

        val path = currentPath
        if (path != freeSpaceLastPath) {
            freeSpaceLastPath = path
            fileOps.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        val fileStore = Files.getFileStore(path)
                        val usableSpace = fileStore.usableSpace
                        val totalSpace = fileStore.totalSpace
                        val pct = if (totalSpace > 0) (usableSpace * 100 / totalSpace) else 0
                        "${formatSizeAuto(usableSpace)} of ${formatSizeAuto(totalSpace)} free ($pct%)"
                    }
                    withContext(Dispatchers.EDT) { freeSpaceLabel.text = text }
                } catch (_: Exception) {
                    withContext(Dispatchers.EDT) { freeSpaceLabel.text = "" }
                }
            }
        }
    }

    /**
     * Subscribe to the shared drive-roots poller in [FileOperationService]. Replaces the
     * historical per-tab Swing timer (and the synchronous `getRoots()` call at construction,
     * which did `File.listRoots()` on the EDT). Updates that arrive while the combo popup is
     * open or the tab is inside an archive are parked in [pendingDriveRoots] and applied when
     * the combo is safe to mutate again.
     */
    private fun startDriveUpdates() {
        if (unsubscribeDriveRoots != null) return
        unsubscribeDriveRoots = fileOps.subscribeToRoots { roots ->
            if (driveComboPopupOpen || currentVfs != null) {
                pendingDriveRoots = roots
            } else {
                applyDriveRoots(roots)
            }
        }
    }

    private fun stopDriveUpdates() {
        unsubscribeDriveRoots?.invoke()
        unsubscribeDriveRoots = null
        pendingDriveRoots = null
    }

    /**
     * Apply a parked roots update once the popup has closed. Deferred with invokeLater so
     * the popup-close handler finishes reading the user's selection before the combo's
     * item list is rebuilt underneath it.
     */
    private fun flushPendingDriveRootsLater() {
        if (pendingDriveRoots == null) return
        SwingUtilities.invokeLater {
            val pending = pendingDriveRoots ?: return@invokeLater
            if (driveComboPopupOpen || currentVfs != null) return@invokeLater
            pendingDriveRoots = null
            applyDriveRoots(pending)
        }
    }

    private fun applyDriveRoots(roots: List<String>) {
        val current = (0 until driveCombo.itemCount).map { driveCombo.getItemAt(it) }
        if (current == roots) return

        updatingDriveCombo = true
        try {
            driveCombo.removeAllItems()
            roots.forEach { driveCombo.addItem(it) }

            val bestMatch = roots
                .filter { currentPath.startsWith(it) }
                .maxByOrNull { it.length }
                ?: roots.firstOrNull()
            if (bestMatch != null) {
                driveCombo.selectedItem = bestMatch
            }

            val widest = roots.maxByOrNull { DriveLabels.getDisplayText(it).length } ?: ""
            driveCombo.setPrototypeDisplayValue(widest)
            driveCombo.revalidate()
        } finally {
            updatingDriveCombo = false
        }
    }

    suspend fun navigateTo(path: Path, selectName: String? = null, requestFocus: Boolean = true) {
        val vfs = currentVfs
        val entries = try {
            when {
                vfs != null -> vfs.listFiles(path)
                // Recursive files-only listing only applies on the real filesystem.
                showAllNestedFiles -> fileOps.listAllNestedFiles(path)
                else -> fileOps.listFiles(path)
            }
        } catch (e: Exception) {
            // On the real filesystem, if the target no longer exists (e.g. the other panel
            // deleted an ancestor), fall back to the nearest surviving ancestor instead of
            // leaving the user stranded on a dead path.
            if (vfs == null) {
                val ancestor = withContext(Dispatchers.IO) { nearestExistingAncestor(path) }
                // No ancestor survives when the path's root itself is gone (a tab restored
                // on an unplugged drive). Land on the home directory rather than leaving an
                // empty tab pointing at a dead path, and say why the tab moved.
                val fallback = ancestor ?: withContext(Dispatchers.IO) { homeDirectoryFallback(path) }
                if (fallback != null) {
                    if (ancestor == null) {
                        withContext(Dispatchers.EDT) {
                            fileErrorNotification("$path is not available — opened the home directory instead")
                        }
                    }
                    navigateTo(fallback, requestFocus = requestFocus)
                    return
                }
            }
            withContext(Dispatchers.EDT) {
                fileErrorNotification("Cannot list directory: ${fileErrorMessage(e)}")
            }
            return
        }
        withContext(Dispatchers.EDT) {
            // Save cursor position and column state for the current directory
            val selectedRow = table.selectedRow
            if (selectedRow >= 0) {
                cursorPositions[currentPath] = selectedRow
            }
            if (initialized && viewMode == ViewMode.TABLE) {
                // Only save when the user has been interacting with the table;
                // saving while in LIST/TREE/THUMBNAIL would capture stale defaults
                // and mask the "no state yet" signal used when switching back to TABLE.
                saveColumnState()
            }

            currentPath = path

            // Update path display
            if (vfs != null && vfsStack.isNotEmpty()) {
                val (separator, prefixes) = buildVfsStackPrefixes()
                val sb = StringBuilder(prefixes.last())
                if (!vfs.isRoot(path)) {
                    val relativePath = vfsRelativePath(vfs, path)
                    sb.append(separator).append(relativePath.removePrefix("/").replace("/", separator))
                }
                pathField.text = sb.toString()
            } else {
                pathField.text = path.toString()
            }

            allEntries = entries
            filteredEntries = entries
            directorySizes.clear()
            // Drop marks on directory change so they don't accumulate across navigations.
            // (Path-keyed marks would survive technically, but that's poor UX — users expect
            // a fresh slate when they cd somewhere new.)
            clearAllToggledMarks()
            // Hide filter on navigation
            if (quickFilterBar.isVisible) {
                quickFilterBar.deactivate()
            }

            tableModel.setEntries(entries)

            // Update list model. Use addAll so only one ListDataEvent is fired for the whole
            // batch — looping addElement fires one event per entry and becomes a significant
            // bottleneck for large directories like Downloads.
            listModel.clear()
            listModel.addAll(entries)

            // Update thumbnail model
            thumbnailListModel.clear()
            thumbnailListModel.addAll(entries)

            // Update tree model
            if (viewMode == ViewMode.TREE && !showAllNestedFiles) {
                // In full tree mode, rebuild the tree to reflect the new location. In
                // show-all-nested-files mode the entries are an already-flat file list, so it
                // renders as flat leaves like the other views instead of a hierarchy.
                rebuildFullTree()
            } else {
                populateFlatTree()
            }

            updateStatusBar()
            onDirectoryChanged(this@FileTab)

            // Restore column state for this path
            val pending = pendingTabState
            if (pending != null) {
                restoreTabState(pending)
                pendingTabState = null
            } else if (initialized) {
                restoreColumnState(path)
            }

            // Update drive combo (only for real FS)
            if (vfs == null) {
                // Apply any roots update parked while this tab was inside an archive.
                pendingDriveRoots?.let { pending ->
                    pendingDriveRoots = null
                    applyDriveRoots(pending)
                }
                updatingDriveCombo = true
                try {
                    var bestIndex = -1
                    var bestLength = -1
                    for (i in 0 until driveCombo.itemCount) {
                        val item = driveCombo.getItemAt(i)
                        if (path.startsWith(item) && item.length > bestLength) {
                            bestIndex = i
                            bestLength = item.length
                        }
                    }
                    if (bestIndex >= 0) {
                        driveCombo.selectedIndex = bestIndex
                    }
                } finally {
                    updatingDriveCombo = false
                }
            }

            // Select by name, restore saved position (clamped to last row when the saved
            // index now points past the end — e.g. user deleted the last entry), or default
            // to first row.
            val savedCursor = cursorPositions[path]
            val targetRow = if (selectName != null) {
                (0 until table.rowCount).firstOrNull { viewRow ->
                    val modelRow = table.convertRowIndexToModel(viewRow)
                    tableModel.getEntryAt(modelRow)?.name == selectName
                }
            } else if (savedCursor != null && table.rowCount > 0) {
                savedCursor.coerceAtMost(table.rowCount - 1)
            } else null

            if (targetRow != null && targetRow < table.rowCount) {
                table.setRowSelectionInterval(targetRow, targetRow)
                table.scrollRectToVisible(table.getCellRect(targetRow, 0, true))
            } else if (table.rowCount > 0) {
                table.setRowSelectionInterval(0, 0)
                table.scrollRectToVisible(table.getCellRect(0, 0, true))
            }

            // Select in list view too
            val listTarget = if (selectName != null) {
                (0 until listModel.size()).firstOrNull { listModel.getElementAt(it).name == selectName }
            } else if (savedCursor != null && listModel.size() > 0) {
                savedCursor.coerceAtMost(listModel.size() - 1)
            } else null
            if (listTarget != null) {
                list.selectedIndex = listTarget
                list.ensureIndexIsVisible(listTarget)
            } else if (listModel.size() > 0) {
                list.selectedIndex = 0
                // Match the table fallback below: scroll to the selection so it is
                // visible after navigation, even when the previous viewport was
                // scrolled further down.
                list.ensureIndexIsVisible(0)
            }

            // Select in thumbnail view too
            val thumbTarget = if (selectName != null) {
                (0 until thumbnailListModel.size()).firstOrNull { thumbnailListModel.getElementAt(it).name == selectName }
            } else if (savedCursor != null && thumbnailListModel.size() > 0) {
                savedCursor.coerceAtMost(thumbnailListModel.size() - 1)
            } else null
            if (thumbTarget != null) {
                thumbnailList.selectedIndex = thumbTarget
                thumbnailList.ensureIndexIsVisible(thumbTarget)
            } else if (thumbnailListModel.size() > 0) {
                thumbnailList.selectedIndex = 0
                thumbnailList.ensureIndexIsVisible(0)
            }

            initialized = true

            if (requestFocus) {
                when (viewMode) {
                    ViewMode.TABLE -> table.requestFocusInWindow()
                    ViewMode.LIST -> list.requestFocusInWindow()
                    ViewMode.THUMBNAIL -> thumbnailList.requestFocusInWindow()
                    ViewMode.TREE -> tree.requestFocusInWindow()
                }
            }
        }
    }

    fun refresh(requestFocus: Boolean = true) {
        invalidateFreeSpaceCache()
        ThumbnailCache.getInstance().evictDirectory(currentPath)
        val vfs = currentVfs
        if (vfs != null) {
            val relativePath = vfsRelativePath(vfs, currentPath)
            fileOps.launch {
                withContext(Dispatchers.IO) {
                    vfsWriteMutex.withLock {
                        vfs.flush()
                        writeBackNestedArchivesLocked()
                    }
                }
                val newPath = if (relativePath.isEmpty()) vfs.root else vfs.root.resolve(relativePath)
                navigateTo(newPath, requestFocus = requestFocus)
            }
        } else {
            fileOps.invalidateListingCache(currentPath)
            fileOps.launch { navigateTo(currentPath, requestFocus = requestFocus) }
        }
    }

    /**
     * Re-list [currentPath] only if the listing cache is no longer fresh for it. Called when
     * the tab becomes active, so externally-modified directories stop showing ghost rows
     * without forcing a re-render on every tab switch.
     *
     * Inside an archive (VFS) we skip the check: the VFS state is authoritative, and the
     * real-filesystem mtime of the archive file doesn't reflect the virtual directory listing.
     */
    fun revalidateIfStale() {
        if (currentVfs != null) return
        if (fileOps.isListingCacheFresh(currentPath)) return
        fileOps.launch { navigateTo(currentPath, requestFocus = false) }
    }

    fun showDriveSelector() {
        driveCombo.requestFocusInWindow()
        driveCombo.showPopup()
    }

    // Persistent marks (Insert/Space) keyed by absolute path so they survive sort changes
    // and view-mode switches. Each renderer/apply helper translates back to view rows on the fly.
    internal val markedPaths = mutableSetOf<Path>()
    // Snapshot of markedPaths captured by "Save Selection". `null` means nothing has been saved yet.
    // Restoring with no snapshot is a no-op so the Restore action can stay enabled-checked cheaply.
    internal var savedMarkedPaths: MutableSet<Path>? = null
    internal var insideToggle = false
    // Set while a selection listener is restoring the marked rows after Swing's default
    // arrow / click handler clobbered them. Prevents the restore from triggering itself.
    internal var insideRestore = false
    // Set while setViewMode / rebuildFullTree is rebuilding selection in the new view.
    // Without this, each addRowSelectionInterval call inside selectEntriesByName fires the
    // listener and runs a full restoreTableMarks pass, giving O(N*rowCount) work on view
    // switch. With the flag, the listener no-ops and one explicit applyMarksForCurrentView
    // / restoreTreeMarks call after the rebuild does the work once.
    internal var insideViewSwitch = false
    internal val directorySizes = ConcurrentHashMap<Path, Long>()

    fun openSelectedEntry() {
        val entry = getSelectedEntry() ?: return
        if (entry.isParentLink) {
            goUp()
            return
        }
        if (entry.isDirectory) {
            fileOps.launch { navigateTo(entry.path) }
            return
        }
        // A symbolic link can point to a directory without having been classified as one
        // (e.g. the listing's follow-read failed, which has been reported on macOS for
        // redirected home folders like Desktop/Documents that are links to a cloud-storage
        // location). Resolve the target off the EDT and switch into it when it really is a
        // directory; otherwise fall back to the normal file/archive handling.
        if (entry.isSymbolicLink) {
            fileOps.launch {
                val targetIsDir = withContext(Dispatchers.IO) { Files.isDirectory(entry.path) }
                if (targetIsDir) {
                    navigateTo(entry.path)
                } else {
                    withContext(Dispatchers.EDT) { openNonDirectoryEntry(entry) }
                }
            }
            return
        }
        openNonDirectoryEntry(entry)
    }

    private fun openNonDirectoryEntry(entry: FileEntry) {
        if (!entry.isNamedPipe && isEntryBrowsableArchive(entry)) {
            enterVfs(entry)
        } else {
            openFile(entry)
        }
    }

    private fun isEntryBrowsableArchive(entry: FileEntry): Boolean {
        return !(entry.isDirectory || entry.isParentLink) && if (currentVfs != null) {
            VirtualFileSystemRegistry.supportsByExtension(entry.name)
        } else {
            VirtualFileSystemRegistry.supports(entry.path)
        }
    }

    fun goUp() {
        val vfs = currentVfs
        if (vfs != null && vfs.isRoot(currentPath)) {
            exitVfs()
            return
        }
        val parent = currentPath.parent
        if (parent != null) {
            fileOps.launch { navigateTo(parent) }
        }
    }

    fun goToRoot() {
        val vfs = currentVfs
        val root = vfs?.root ?: currentPath.root
        if (root != null && root != currentPath) {
            fileOps.launch { navigateTo(root) }
        }
    }

    override fun dispose() {
        renameClickTimer?.stop()
        stopDriveUpdates()
        closeVfsStack()
    }

    /**
     * Close every open VFS in the stack and delete their temp files **synchronously**. Used from
     * [dispose]: the Disposable contract requires cleanup to be finished when dispose returns, so
     * this can't be deferred to a coroutine (whose scope may be cancelling at teardown). For the
     * mid-life "user left the archive" paths use [closeVfsStackAsync] instead.
     */
    internal fun closeVfsStack() {
        for (entry in vfsStack.asReversed()) {
            try {
                entry.vfs.close()
            } catch (e: Exception) {
                thisLogger().warn("Failed to close VFS ${entry.vfs.archivePath}: ${e.message}")
            }
            entry.cleanupTempFile()
        }
        vfsStack.clear()
    }

    /**
     * Leave the archive mid-life (drive selection, breadcrumb click): empty the stack synchronously
     * so the tab immediately reflects "left the archive", then close the handles off the EDT via
     * [scheduleVfsClose]. Unlike [dispose], the tab keeps living.
     */
    internal fun closeVfsStackAsync() {
        if (vfsStack.isEmpty()) return
        val entries = vfsStack.toList()
        vfsStack.clear()
        scheduleVfsClose(entries)
    }

    /**
     * Close [entries]' VFS handles and delete their extracted temp dirs off the EDT, holding
     * [vfsWriteMutex]. `close()` recursively deletes a potentially large temp tree — freezing the
     * EDT if done inline — and must not run while an in-flight write-back is flushing/copying
     * through the same stack, which would delete a temp dir mid-copy and raise
     * `ClosedFileSystemException`/`NoSuchFileException`. [NonCancellable] keeps a scope shutdown
     * from skipping the cleanup; anything still missed is swept by `VfsTempCleanup` on next start.
     */
    internal fun scheduleVfsClose(entries: List<VfsStackEntry>) {
        if (entries.isEmpty()) return
        fileOps.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                vfsWriteMutex.withLock {
                    for (entry in entries) {
                        try {
                            entry.vfs.close()
                        } catch (e: Exception) {
                            thisLogger().warn("Failed to close VFS ${entry.vfs.archivePath}: ${e.message}")
                        }
                        entry.cleanupTempFile()
                    }
                }
            }
        }
    }

    /** True when keyboard focus is inside any of the four view components owned by this tab. */
    fun hasAnyViewFocus(): Boolean =
        table.hasFocus() || list.hasFocus() || thumbnailList.hasFocus() || tree.hasFocus()

    internal fun getOtherPanelDisplayPath(): String? {
        return getOtherPanelTab()?.getDisplayPath()
    }

    internal fun getOtherPanelTab(): FileTab? {
        val svc = stateService ?: return null
        val activePanel = svc.getActivePanel() ?: return null
        val otherPanel = if (activePanel == svc.leftPanel) svc.rightPanel else svc.leftPanel
        return otherPanel?.getActiveTab()
    }

    fun setStateService(stateService: FileManagerStateService) {
        this.stateService = stateService
    }

    fun updatePanelCallbacks(
        otherPanelPathProvider: () -> Path?,
        onDirectoryChanged: (FileTab) -> Unit,
        onRefreshOtherPanel: () -> Unit,
    ) {
        this.otherPanelPathProvider = otherPanelPathProvider
        this.onDirectoryChanged = onDirectoryChanged
        this.onRefreshOtherPanel = onRefreshOtherPanel
    }

    fun applyColumnState(widths: List<Int>, order: List<Int>) {
        val cm = table.columnModel
        if (order.size == cm.columnCount) {
            for ((targetIdx, element) in order.withIndex()) {
                val modelIdx = element
                var currentViewIdx = -1
                for (viewIdx in 0 until cm.columnCount) {
                    if (cm.getColumn(viewIdx).modelIndex == modelIdx) {
                        currentViewIdx = viewIdx
                        break
                    }
                }
                if (currentViewIdx >= 0 && currentViewIdx != targetIdx) {
                    cm.moveColumn(currentViewIdx, targetIdx)
                }
            }
        }
        if (widths.size == cm.columnCount) {
            for (i in 0 until cm.columnCount) {
                val col = cm.getColumn(i)
                col.preferredWidth = widths[i]
                col.width = widths[i]
            }
        }
    }

    fun saveColumnState() {
        val cm = table.columnModel
        val widths = mutableListOf<Int>()
        val order = mutableListOf<Int>()
        for (i in 0 until cm.columnCount) {
            val col = cm.getColumn(i)
            widths.add(col.width)
            order.add(col.modelIndex)
            col.preferredWidth = col.width
        }
        tabColumnStates[currentPath.toString()] = TabColumnState(widths, order)
    }

    internal fun hasTabColumnState(path: String): Boolean = tabColumnStates.containsKey(path)

    /**
     * Test seam: drops the in-memory per-path column state. A directory navigation/refresh
     * calls [saveColumnState], so by the time a test has a settled tab the "no saved column
     * state" branch of [setViewMode] is no longer reachable; clearing makes it deterministic.
     */
    internal fun clearColumnState() = tabColumnStates.clear()

    /**
     * Toggles "show all nested files" and re-lists [currentPath] with the new mode. The recursive
     * listing isn't cached, so this always reflects the current subtree.
     */
    fun setShowAllNestedFiles(enabled: Boolean) {
        if (showAllNestedFiles == enabled) return
        showAllNestedFiles = enabled
        fileOps.launch { navigateTo(currentPath) }
    }

    /**
     * Rebuilds the tree as a flat list of [filteredEntries] — used outside full-tree mode.
     * Reading the filtered list (== [allEntries] when no filter is active) keeps the flat
     * tree consistent with the other views under an active quick filter.
     */
    internal fun populateFlatTree() {
        treeRootNode.removeAllChildren()
        for (entry in filteredEntries) {
            val node = DefaultMutableTreeNode(entry)
            if (entry.isDirectory && !entry.isParentLink) {
                node.add(DefaultMutableTreeNode("Loading..."))
            }
            treeRootNode.add(node)
        }
        treeModel.nodeStructureChanged(treeRootNode)
    }

    fun saveTabState(): FileManagerStateService.TabState {
        val cm = table.columnModel
        val widths = mutableListOf<Int>()
        val order = mutableListOf<Int>()
        for (i in 0 until cm.columnCount) {
            val col = cm.getColumn(i)
            widths.add(col.width)
            order.add(col.modelIndex)
        }
        val sortKeys = table.rowSorter?.sortKeys
        val sortCol = sortKeys?.firstOrNull()?.column ?: -1
        val sortAsc = sortKeys?.firstOrNull()?.sortOrder != SortOrder.DESCENDING

        val persistPath = if (vfsStack.isNotEmpty()) {
            val archivePath = vfsStack.first().parentPath
            (archivePath.parent ?: archivePath).toString()
        } else {
            currentPath.toString()
        }

        return FileManagerStateService.TabState(
            path = persistPath,
            viewMode = viewMode.name,
            columnWidths = widths.joinToString(","),
            columnOrder = order.joinToString(","),
            sortColumn = sortCol,
            sortAscending = sortAsc,
            showAllNestedFiles = showAllNestedFiles,
        )
    }

    fun restoreTabState(state: FileManagerStateService.TabState) {
        val widths = state.columnWidths.split(",").mapNotNull { it.trim().toIntOrNull() }
        val order = state.columnOrder.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (widths.isNotEmpty() && order.isNotEmpty()) {
            applyColumnState(widths, order)
        }
        if (state.sortColumn >= 0) {
            val sortOrder = if (state.sortAscending) SortOrder.ASCENDING else SortOrder.DESCENDING
            table.rowSorter?.sortKeys = listOf(RowSorter.SortKey(state.sortColumn, sortOrder))
        }
    }

    private fun restoreColumnState(path: Path) {
        val state = tabColumnStates[path.toString()] ?: return
        applyColumnState(state.widths, state.order)
    }

    /**
     * Error balloon in the "Turtle Commander" group. When [error] (or anything in its cause
     * chain) is a [System7zUnavailableException] — the system 7-Zip is missing or too old for
     * the format being opened — the balloon gets a "Download 7-Zip" action opening the official
     * download page, so the fix is one click away instead of buried in the message text.
     */
    internal fun fileErrorNotification(content: String, error: Throwable? = null) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Turtle Commander")
            .createNotification(content, NotificationType.ERROR)
        if (generateSequence(error) { it.cause }.any { it is System7zUnavailableException }) {
            notification.addAction(NotificationAction.createSimpleExpiring("Download 7-Zip") {
                BrowserUtil.browse(System7z.DOWNLOAD_URL)
            })
        }
        notification.notify(project)
    }

    /**
     * Handle a `cd` typed in the command bar. [rawArg] is the already-unquoted target ("" navigates
     * home). Resolves it against the current directory (absolute paths and a leading `~` are
     * honored) and navigates there when it's an existing directory; otherwise shows an error.
     * [onSuccess] runs on the EDT after a successful move so the caller can refresh its prompt.
     * No-op inside an archive — the command bar blocks commands there already.
     */
    fun changeDirectoryFromCommand(rawArg: String, onSuccess: () -> Unit = {}) {
        if (currentVfs != null) return
        val home = Path.of(System.getProperty("user.home"))
        val target = CdCommand.resolveTarget(rawArg, currentPath, home, SystemInfo.isWindows)
        if (target == null) {
            fileErrorNotification("cd: invalid path: $rawArg")
            return
        }
        fileOps.launch {
            val isDir = withContext(Dispatchers.IO) { Files.isDirectory(target) }
            if (isDir) {
                navigateTo(target)
                withContext(Dispatchers.EDT) { onSuccess() }
            } else {
                withContext(Dispatchers.EDT) {
                    fileErrorNotification("cd: no such directory: ${target}")
                }
            }
        }
    }

    private fun nearestExistingAncestor(path: Path): Path? =
        nearestExistingAncestor(path) { candidate ->
            try {
                Files.isDirectory(candidate)
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Last-resort navigation target when not even the root of a failed path exists. Returns
     * the user home directory, or null when it equals the failed path (so a failing
     * navigateTo(home) can't recurse into itself) or is itself unavailable.
     */
    private fun homeDirectoryFallback(failed: Path): Path? {
        val home = try {
            Path.of(System.getProperty("user.home"))
        } catch (_: Exception) {
            return null
        }
        if (home == failed) return null
        return try {
            if (Files.isDirectory(home)) home else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        val FILE_ENTRY_FLAVOR = DataFlavor(
            DataFlavor.javaJVMLocalObjectMimeType + ";class=java.util.List",
            "FileEntry List",
        )
        internal const val VIEW_TABLE = "table"
        internal const val VIEW_LIST = "list"
        internal const val VIEW_THUMBNAIL = "thumbnail"
        internal const val VIEW_TREE = "tree"

        /**
         * Resolves the target path for a drive selector selection.
         * If the opposite panel is under the selected drive, returns the opposite panel's path instead.
         */
        internal fun resolveDriveSelectionTarget(
            selectedDrive: Path,
            otherPanelPath: Path?,
            availableRoots: List<Path> = emptyList(),
        ): Path {
            if (otherPanelPath != null && otherPanelPath.startsWith(selectedDrive) && otherPanelPath != selectedDrive) {
                // Only swap to the opposite panel's path if `selectedDrive` is the deepest
                // root that covers it. Otherwise (e.g. selecting "/" while the other panel
                // is under "/home/user") the user is asking for the broader location and we
                // must honor that selection instead of bouncing to the other panel.
                val deepestRoot = availableRoots
                    .filter { otherPanelPath.startsWith(it) }
                    .maxByOrNull { it.toString().length }
                if (deepestRoot == null || deepestRoot == selectedDrive) {
                    return otherPanelPath
                }
            }
            return selectedDrive
        }

        /**
         * Walks the parent chain of [path] and returns the first ancestor for which
         * [exists] reports true, or `null` if none do. The starting path itself is
         * not tested — callers invoke this only after a listing for [path] already
         * failed, so the walk begins at [path]'s parent.
         */
        internal fun nearestExistingAncestor(path: Path, exists: (Path) -> Boolean): Path? {
            var current: Path? = path.parent
            while (current != null) {
                if (exists(current)) return current
                current = current.parent
            }
            return null
        }
    }
}

fun isArchiveFile(entry: FileEntry): Boolean {
    return !entry.isDirectory && VirtualFileSystemRegistry.supportsByExtension(entry.name)
}

fun fileEntryIcon(
    entry: FileEntry,
    enableFileNameHighlighting: Boolean,
    resolved: ResolvedStyle = ResolvedStyle.EMPTY,
): Icon {
    // A rule may override the type/folder icon outright (the "..", non-highlighted, and
    // unknown-key cases fall through to the defaults below).
    val overrideIcon = if (enableFileNameHighlighting && !entry.isParentLink) {
        RuleIcons.resolve(resolved.iconId)
    } else null
    var base: Icon = when {
        entry.isParentLink -> AllIcons.Nodes.UpLevel
        overrideIcon != null -> overrideIcon
        entry.isDirectory -> AllIcons.Nodes.Folder
        isArchiveFile(entry) -> AllIcons.FileTypes.Archive
        else -> FileTypeManager.getInstance().getFileTypeByFileName(entry.name).icon
            ?: AllIcons.FileTypes.Any_type
    }
    // Overlay the rule's colored dot on whatever icon was chosen — file or folder alike. The
    // ".." parent link is exempt; it never carries rule styling.
    if (enableFileNameHighlighting && !entry.isParentLink) {
        resolved.iconDotJBColor()?.let { base = DirectoryIcons.iconWithDot(base, it) }
    }
    // Badge symbolic links with the platform's link-arrow overlay so they read as links in
    // every view. Color (the valid/broken default rules) conveys whether the target resolves.
    if (entry.isSymbolicLink) {
        return LayeredIcon.layeredIcon(arrayOf(base, AllIcons.Nodes.Symlink))
    }
    return base
}

enum class ViewMode { TABLE, LIST, THUMBNAIL, TREE }
