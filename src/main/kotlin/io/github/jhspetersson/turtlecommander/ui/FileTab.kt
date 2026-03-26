package io.github.jhspetersson.turtlecommander.ui
import io.github.jhspetersson.turtlecommander.service.ThumbnailCache
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.action.FileCopyBuffer
import io.github.jhspetersson.turtlecommander.action.FileContextMenuState
import io.github.jhspetersson.turtlecommander.util.fileErrorMessage
import io.github.jhspetersson.turtlecommander.util.formatSize
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.service.FileOperationService
import io.github.jhspetersson.turtlecommander.settings.ComponentStyle
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import io.github.jhspetersson.turtlecommander.model.FileEntry

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.TransferHandler
import javax.swing.event.CellEditorListener
import javax.swing.event.ChangeEvent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class FileTab(
    internal val project: Project,
    initialPath: Path,
    internal var otherPanelPathProvider: () -> Path?,
    private var onDirectoryChanged: (FileTab) -> Unit,
    private var onSwitchToOtherPanel: () -> Unit = {},
    internal var onRefreshOtherPanel: () -> Unit = {},
) : JPanel(BorderLayout()) {

    internal val fileOps = project.service<FileOperationService>()
    internal val tableModel = FileTableModel()
    val table = JBTable(tableModel)
    private val defaultTableFont by lazy { table.font }

    private val listModel = DefaultListModel<FileEntry>()
    val list = JBList(listModel)
    private val thumbnailListModel = DefaultListModel<FileEntry>()
    val thumbnailList = JBList(thumbnailListModel)
    private val treeRootNode = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(treeRootNode)
    val tree = Tree(treeModel)
    private val viewCardLayout = CardLayout()
    private val viewPanel = JPanel(viewCardLayout)
    var viewMode: ViewMode = try {
        ViewMode.valueOf(TurtleCommanderSettings.getInstance().state.defaultViewMode)
    } catch (_: Exception) {
        ViewMode.TABLE
    }
        private set

    private val driveCombo = ComboBox<String>()
    private val pathField = BreadcrumbPathField()
    private val statusLabel = JLabel(" ")
    private val freeSpaceLabel = JLabel(" ")
    private val statusPanel = JPanel(BorderLayout())
    private var allEntries: List<FileEntry> = emptyList()
    private val filterField = JTextField()
    private val filterPanel = JPanel(BorderLayout(4, 0))
    private var updatingDriveCombo = false
    private var driveComboPopupOpen = false
    private var driveRefreshTimer: javax.swing.Timer? = null
    private val cursorPositions = mutableMapOf<Path, Int>()
    internal var stateService: FileManagerStateService? = null
    private var initialized = false
    internal var enableFileNameHighlighting = TurtleCommanderSettings.getInstance().state.enableFileNameHighlighting

    var currentPath: Path = initialPath
        private set

    internal val vfsStack = mutableListOf<VfsStackEntry>()

    var currentVfs: VirtualFileSystem?
        get() = vfsStack.lastOrNull()?.vfs
        private set(_) {}  // managed via stack

    val isInsideArchive: Boolean
        get() = vfsStack.isNotEmpty()



    init {
        tableModel.directorySizeProvider = { path -> directorySizes[path] }
        setupHeader()
        setupTable()
        setupList()
        setupThumbnailList()
        setupTree()
        setupFilterPanel()
        setupViewPanel()
        loadDrives()
        if (!TurtleCommanderSettings.getInstance().state.hideDriveSelector) {
            startDriveRefreshTimer()
        }
        applyPanelFont()
        applyVisibilitySettings()
    }

    fun applyVisibilitySettings() {
        val settings = TurtleCommanderSettings.getInstance().state
        driveCombo.isVisible = !settings.hideDriveSelector
        statusPanel.isVisible = !settings.hideStatusBar
        enableFileNameHighlighting = settings.enableFileNameHighlighting
        if (settings.hideDriveSelector) {
            driveRefreshTimer?.stop()
        } else {
            if (driveRefreshTimer?.isRunning != true) startDriveRefreshTimer()
        }
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

        val panelStyle = settings.state.panelStyle
        val fg = panelStyle.getFontColor()
        if (fg != null) {
            table.foreground = fg
            list.foreground = fg
            thumbnailList.foreground = fg
            tree.foreground = fg
        }

        applyDriveSelectorStyle()
        applyColumnHeaderStyle()
        applyStatusBarStyle()
        applyPathBarStyle()
    }

    private fun applyDriveSelectorStyle() {
        val style = TurtleCommanderSettings.getInstance().state.driveSelectorStyle
        val font = style.getFont(driveCombo.font)
        if (font != null) driveCombo.font = font
        val fg = style.getFontColor()
        if (fg != null) driveCombo.foreground = fg
    }

    private fun applyColumnHeaderStyle() {
        val style = TurtleCommanderSettings.getInstance().state.columnHeaderStyle
        val header = table.tableHeader ?: return
        val font = style.getFont(header.font)
        if (font != null) header.font = font
        val fg = style.getFontColor()
        if (fg != null) header.foreground = fg
    }

    private fun applyStatusBarStyle() {
        val style = TurtleCommanderSettings.getInstance().state.statusBarStyle
        val font = style.getFont(statusLabel.font)
        if (font != null) {
            statusLabel.font = font
            freeSpaceLabel.font = font
        }
        val fg = style.getFontColor()
        if (fg != null) {
            statusLabel.foreground = fg
            freeSpaceLabel.foreground = fg
        }
    }

    private fun applyPathBarStyle() {
        val style = TurtleCommanderSettings.getInstance().state.pathBarStyle
        pathField.applyStyle(style)
    }

    private fun setupHeader() {
        val headerPanel = JPanel(BorderLayout())

        driveCombo.apply {
            addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                    driveComboPopupOpen = true
                }
                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                    driveComboPopupOpen = false
                    if (updatingDriveCombo) return
                    val selected = selectedItem as? String ?: return
                    val drivePath = Path.of(selected)
                    // Exit VFS if active
                    if (vfsStack.isNotEmpty()) {
                        dispose()
                    }
                    if (drivePath != currentPath) {
                        fileOps.launch {
                            navigateTo(drivePath)
                        }
                    }
                    table.requestFocusInWindow()
                }
                override fun popupMenuCanceled(e: PopupMenuEvent) {
                    driveComboPopupOpen = false
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
                    if (path != null && path.toFile().isDirectory) {
                        fileOps.launch { navigateTo(path) }
                    }
                }
            }
            addActionListener {
                if (currentVfs != null) {
                    // In VFS mode, ignore path field edits
                    table.requestFocusInWindow()
                    return@addActionListener
                }
                var path = try { Path.of(text) } catch (_: Exception) { currentPath }
                while (!path.toFile().isDirectory) {
                    path = path.parent ?: break
                }
                if (path.toFile().isDirectory) {
                    fileOps.launch {
                        navigateTo(path)
                    }
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

            columnModel.getColumn(0).cellRenderer = FileNameCellRenderer(this@FileTab)
            columnModel.getColumn(0).preferredWidth = 200
            columnModel.getColumn(1).preferredWidth = 50
            columnModel.getColumn(1).cellRenderer = DisplayValueRenderer(this@FileTab)
            columnModel.getColumn(2).preferredWidth = 80
            columnModel.getColumn(2).cellRenderer = DisplayValueRenderer(this@FileTab)
            columnModel.getColumn(3).preferredWidth = 130
            columnModel.getColumn(3).cellRenderer = DisplayValueRenderer(this@FileTab)
            columnModel.getColumn(4).preferredWidth = 80
            columnModel.getColumn(4).cellRenderer = DisplayValueRenderer(this@FileTab)

            rowSorter = ParentPinningRowSorter(tableModel)

            var editingEntry: FileEntry? = null
            val nameEditor = object : DefaultCellEditor(JTextField()) {
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
            nameEditor.addCellEditorListener(object : CellEditorListener {
                override fun editingStopped(e: ChangeEvent) {
                    val entry = editingEntry ?: return
                    editingEntry = null
                    val newName = (nameEditor.cellEditorValue as? String)?.trim() ?: return
                    if (newName.isNotEmpty() && newName != entry.name) {
                        performRename(entry, newName)
                    }
                }

                override fun editingCanceled(e: ChangeEvent) {
                    editingEntry = null
                }
            })
            columnModel.getColumn(0).cellEditor = nameEditor

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        openSelectedEntry()
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    handleContextMenu(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    handleContextMenu(e)
                }

                private fun handleContextMenu(e: MouseEvent) {
                    if (!e.isPopupTrigger) return
                    val row = table.rowAtPoint(e.point)
                    if (row >= 0 && !table.isRowSelected(row)) {
                        table.setRowSelectionInterval(row, row)
                    }
                    val entry = if (row >= 0) {
                        val modelRow = table.convertRowIndexToModel(row)
                        tableModel.getEntryAt(modelRow)
                    } else null
                    FileContextMenuState.clickedEntry = entry
                    FileContextMenuState.clickedTab = this@FileTab

                    val am = ActionManager.getInstance()
                    val group = am.getAction("TurtleCommander.FileContextMenu") as? ActionGroup ?: return
                    val popupMenu = am.createActionPopupMenu("TurtleCommander.FileContextMenu", group)
                    popupMenu.component.show(table, e.x, e.y)
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
                if (!insideToggle) toggledRows.clear()
                updateStatusBar()
            }
        }
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
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    handleListContextMenu(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    handleListContextMenu(e)
                }
            })

            addListSelectionListener { updateStatusBar() }
        }
    }

    private fun handleListContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val index = list.locationToIndex(e.point)
        if (index >= 0 && !list.isSelectedIndex(index)) {
            list.selectedIndex = index
        }
        val entry = if (index >= 0) listModel.getElementAt(index) else null
        FileContextMenuState.clickedEntry = entry
        FileContextMenuState.clickedTab = this

        val am = ActionManager.getInstance()
        val group = am.getAction("TurtleCommander.FileContextMenu") as? ActionGroup ?: return
        val popupMenu = am.createActionPopupMenu("TurtleCommander.FileContextMenu", group)
        popupMenu.component.show(list, e.x, e.y)
    }

    private fun setupThumbnailList() {
        thumbnailList.apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            layoutOrientation = JList.HORIZONTAL_WRAP
            visibleRowCount = 0
            fixedCellWidth = 120
            fixedCellHeight = 90
            cellRenderer = FileThumbnailCellRenderer(this@FileTab)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        openSelectedEntry()
                    }
                }

                override fun mousePressed(e: MouseEvent) {
                    handleThumbnailContextMenu(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    handleThumbnailContextMenu(e)
                }
            })

            addListSelectionListener { updateStatusBar() }
        }
    }

    private fun handleThumbnailContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val index = thumbnailList.locationToIndex(e.point)
        if (index >= 0 && !thumbnailList.isSelectedIndex(index)) {
            thumbnailList.selectedIndex = index
        }
        val entry = if (index >= 0) thumbnailListModel.getElementAt(index) else null
        FileContextMenuState.clickedEntry = entry
        FileContextMenuState.clickedTab = this

        val am = ActionManager.getInstance()
        val group = am.getAction("TurtleCommander.FileContextMenu") as? ActionGroup ?: return
        val popupMenu = am.createActionPopupMenu("TurtleCommander.FileContextMenu", group)
        popupMenu.component.show(thumbnailList, e.x, e.y)
    }

    private fun setupTree() {
        tree.apply {
            isRootVisible = false
            showsRootHandles = true
            background = table.background

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
                            enterVfs(entry.path)
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
                if (!insideToggle) toggledTreeRows.clear()
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
        val toggleTreeAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                toggleSelectionAndMoveDown()
            }
        }
        toggleTreeAction.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), null),
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), null),
            ),
            tree,
        )
    }

    private fun handleTreeContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val treePath = tree.getPathForLocation(e.x, e.y)
        if (treePath != null && !tree.isPathSelected(treePath)) {
            tree.selectionPath = treePath
        }
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val entry = node?.userObject as? FileEntry
        FileContextMenuState.clickedEntry = entry
        FileContextMenuState.clickedTab = this

        val am = ActionManager.getInstance()
        val group = am.getAction("TurtleCommander.FileContextMenu") as? ActionGroup ?: return
        val popupMenu = am.createActionPopupMenu("TurtleCommander.FileContextMenu", group)
        popupMenu.component.show(tree, e.x, e.y)
    }

    private fun setupFilterPanel() {
        filterPanel.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        filterPanel.isVisible = false

        val iconLabel = JLabel(AllIcons.Actions.Find)
        filterPanel.add(iconLabel, BorderLayout.WEST)

        filterPanel.add(filterField, BorderLayout.CENTER)

        val cancelButton = JButton(AllIcons.Actions.Close)
        cancelButton.isFocusable = false
        cancelButton.toolTipText = "Close filter"
        cancelButton.preferredSize = Dimension(24, 24)
        cancelButton.isContentAreaFilled = false
        cancelButton.addActionListener { hideQuickFilter() }
        filterPanel.add(cancelButton, BorderLayout.EAST)

        filterField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ESCAPE -> {
                        hideQuickFilter()
                        e.consume()
                    }
                    KeyEvent.VK_UP, KeyEvent.VK_DOWN -> {
                        // Delegate UP/DOWN to the active view
                        val offset = if (e.keyCode == KeyEvent.VK_DOWN) 1 else -1
                        moveSelection(offset)
                        e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        // Focus the active view component
                        when (viewMode) {
                            ViewMode.TABLE -> table.requestFocusInWindow()
                            ViewMode.LIST -> list.requestFocusInWindow()
                            ViewMode.THUMBNAIL -> thumbnailList.requestFocusInWindow()
                            ViewMode.TREE -> tree.requestFocusInWindow()
                        }
                        e.consume()
                    }
                }
            }
        })

        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })

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
        if (filterPanel.isVisible) {
            filterField.requestFocusInWindow()
            return
        }
        filterPanel.isVisible = true
        filterField.text = ""
        filterField.requestFocusInWindow()
        revalidate()
    }

    private fun hideQuickFilter() {
        filterField.text = ""
        filterPanel.isVisible = false
        applyFilter()
        revalidate()
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
        val pattern = filterField.text.trim()
        val filtered = if (pattern.isEmpty()) {
            allEntries
        } else {
            val glob = if (pattern.contains('*') || pattern.contains('?') || pattern.contains('[')) pattern else "*$pattern*"
            val matcher = try {
                FileSystems.getDefault().getPathMatcher("glob:$glob")
            } catch (_: Exception) {
                return
            }
            allEntries.filter { entry ->
                entry.isParentLink || matcher.matches(Path.of(entry.name))
            }
        }

        tableModel.setEntries(filtered)

        listModel.clear()
        filtered.forEach { listModel.addElement(it) }

        thumbnailListModel.clear()
        filtered.forEach { thumbnailListModel.addElement(it) }

        if (viewMode != ViewMode.TREE) {
            treeRootNode.removeAllChildren()
            for (entry in filtered) {
                val node = DefaultMutableTreeNode(entry)
                if (entry.isDirectory && !entry.isParentLink) {
                    node.add(DefaultMutableTreeNode("Loading..."))
                }
                treeRootNode.add(node)
            }
            treeModel.nodeStructureChanged(treeRootNode)
        }

        if (table.rowCount > 0) table.setRowSelectionInterval(0, 0)
        if (listModel.size() > 0) list.selectedIndex = 0
        if (thumbnailListModel.size() > 0) thumbnailList.selectedIndex = 0

        updateStatusBar()
    }

    private fun moveSelection(offset: Int) {
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

    private fun setupViewPanel() {
        viewPanel.add(JBScrollPane(table), VIEW_TABLE)
        viewPanel.add(JBScrollPane(list), VIEW_LIST)
        viewPanel.add(JBScrollPane(thumbnailList), VIEW_THUMBNAIL)
        viewPanel.add(JBScrollPane(tree), VIEW_TREE)
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
        statusPanel.add(statusLabel, BorderLayout.WEST)
        statusPanel.add(freeSpaceLabel, BorderLayout.EAST)

        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(filterPanel, BorderLayout.NORTH)
        bottomPanel.add(statusPanel, BorderLayout.SOUTH)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    fun setViewMode(mode: ViewMode) {
        val previousMode = viewMode
        if (previousMode == mode) return

        // Capture selection from the current (old) view before switching
        val selectedEntries = getSelectedEntries()
        val selectedNames = selectedEntries.map { it.name }.toSet()

        // For tree -> flat: navigate to the parent directory of the selected entries and select them
        val treeNavigation = if (previousMode == ViewMode.TREE && selectedEntries.isNotEmpty()) {
            val lastEntry = selectedEntries.last()
            val targetDir = lastEntry.path.parent ?: currentPath
            val namesInDir = selectedEntries
                .filter { (it.path.parent ?: currentPath) == targetDir }
                .map { it.name }
                .toSet()
            Pair(targetDir, namesInDir)
        } else null

        viewMode = mode
        val card = when (mode) {
            ViewMode.TABLE -> VIEW_TABLE
            ViewMode.LIST -> VIEW_LIST
            ViewMode.THUMBNAIL -> VIEW_THUMBNAIL
            ViewMode.TREE -> VIEW_TREE
        }
        viewCardLayout.show(viewPanel, card)

        when (mode) {
            ViewMode.TABLE -> {
                if (treeNavigation != null) {
                    val (targetDir, namesInDir) = treeNavigation
                    if (targetDir != currentPath) {
                        fileOps.launch {
                            navigateTo(targetDir, selectName = namesInDir.firstOrNull())
                            if (namesInDir.size > 1) {
                                withContext(Dispatchers.EDT) { selectEntriesByName(namesInDir) }
                            }
                        }
                    } else {
                        selectEntriesByName(namesInDir)
                    }
                } else {
                    selectEntriesByName(selectedNames)
                }
                table.requestFocusInWindow()
            }
            ViewMode.LIST, ViewMode.THUMBNAIL -> {
                if (treeNavigation != null) {
                    val (targetDir, namesInDir) = treeNavigation
                    if (targetDir != currentPath) {
                        fileOps.launch {
                            navigateTo(targetDir, selectName = namesInDir.firstOrNull())
                            if (namesInDir.size > 1) {
                                withContext(Dispatchers.EDT) { selectEntriesByName(namesInDir) }
                            }
                        }
                    } else {
                        selectEntriesByName(namesInDir)
                    }
                } else {
                    selectEntriesByName(selectedNames)
                }
                if (mode == ViewMode.THUMBNAIL) thumbnailList.requestFocusInWindow()
                else list.requestFocusInWindow()
            }
            ViewMode.TREE -> {
                rebuildFullTree(selectedNames)
                tree.requestFocusInWindow()
            }
        }
    }

    private fun rebuildFullTree(selectNames: Set<String> = emptySet()) {
        fileOps.launch {
            val vfs = currentVfs

            // Collect ancestor paths from root to currentPath (inclusive)
            val ancestors = mutableListOf<Path>()
            if (vfs != null) {
                var p: Path? = currentPath
                while (p != null && !vfs.isRoot(p)) {
                    ancestors.add(0, p)
                    p = p.parent
                }
                ancestors.add(0, vfs.root)
            } else {
                var p: Path? = currentPath
                while (p != null) {
                    ancestors.add(0, p)
                    p = p.parent
                }
            }

            // For each ancestor (except the root), load its parent's children (siblings).
            // For the last ancestor (currentPath), load its own children too.
            // Structure: ancestorSiblings[i] = siblings of ancestors[i] (children of ancestors[i]'s parent)
            // For ancestors[0] (root), we load its children directly since it has no parent to list siblings from.
            data class LevelData(
                val ancestorPath: Path,
                val entries: List<FileEntry>,  // entries at this level (siblings of the ancestor)
            )

            val levels = mutableListOf<LevelData>()

            // Level 0: children of the root
            val rootEntries = try {
                vfs?.listFiles(ancestors[0]) ?: fileOps.listFiles(ancestors[0])
            } catch (_: Exception) {
                emptyList()
            }.filter { !it.isParentLink }
            levels.add(LevelData(ancestors[0], rootEntries))

            // Levels 1..n-1: for each non-root ancestor, list its parent's children
            // But we already have ancestors[i-1]'s children from the previous level if ancestors[i-1] == parent.
            // Actually, ancestors[i] is a child of ancestors[i-1], so we need children of ancestors[i-1].
            // We already loaded children of ancestors[0] above. For ancestors[1], its parent is ancestors[0],
            // and we already have those entries. So we only need to load children of ancestors[i] for i >= 1.
            for (i in 1 until ancestors.size) {
                val entries = try {
                    vfs?.listFiles(ancestors[i]) ?: fileOps.listFiles(ancestors[i])
                } catch (_: Exception) {
                    emptyList()
                }.filter { !it.isParentLink }
                levels.add(LevelData(ancestors[i], entries))
            }

            withContext(Dispatchers.EDT) {
                treeRootNode.removeAllChildren()

                val ancestorNodes = mutableListOf<DefaultMutableTreeNode>()
                var currentParentNode = treeRootNode

                for (levelIdx in levels.indices) {
                    val level = levels[levelIdx]
                    val entries = level.entries
                    val nextAncestorPath = ancestors.getOrNull(levelIdx + 1)
                    val ancestorPathNormalized = nextAncestorPath?.normalize()

                    // First pass: find the ancestor node for the next level (if any)
                    var nextParentNode: DefaultMutableTreeNode? = null

                    for (entry in entries) {
                        val node = DefaultMutableTreeNode(entry)
                        if (entry.isDirectory && nextParentNode == null && nextAncestorPath != null &&
                                (entry.path == nextAncestorPath ||
                                 entry.path.normalize() == ancestorPathNormalized ||
                                 entry.path.toAbsolutePath().normalize() == nextAncestorPath.toAbsolutePath().normalize())) {
                            // This directory is on the ancestor path — its children come from the next level
                            nextParentNode = node
                            ancestorNodes.add(node)
                            currentParentNode.add(node)
                        } else if (entry.isDirectory) {
                            // Sibling directory — add lazy placeholder
                            node.add(DefaultMutableTreeNode("Loading..."))
                            currentParentNode.add(node)
                        } else {
                            currentParentNode.add(node)
                        }
                    }

                    // If we didn't find the ancestor entry in the listing (e.g., hidden dir, permission issue),
                    // create an explicit node for it so the tree structure stays correct
                    if (nextParentNode == null && nextAncestorPath != null) {
                        val syntheticEntry = FileEntry(
                            name = nextAncestorPath.fileName?.toString() ?: nextAncestorPath.toString(),
                            path = nextAncestorPath,
                            isDirectory = true,
                            size = 0,
                            lastModified = null,
                            permissions = "",
                        )
                        nextParentNode = DefaultMutableTreeNode(syntheticEntry)
                        ancestorNodes.add(nextParentNode)
                        currentParentNode.add(nextParentNode)
                    }

                    // Advance to the next level's parent AFTER all siblings have been added
                    if (nextParentNode != null) {
                        currentParentNode = nextParentNode
                    }
                }

                treeModel.nodeStructureChanged(treeRootNode)

                // Expand all ancestor nodes so the path to current directory is visible
                for (node in ancestorNodes) {
                    tree.expandPath(TreePath(node.path))
                }

                // Select entries by name in the current directory node
                if (selectNames.isNotEmpty()) {
                    val currentDirNode = if (ancestorNodes.isNotEmpty()) ancestorNodes.last() else treeRootNode
                    val treePaths = (0 until currentDirNode.childCount).mapNotNull { i ->
                        val child = currentDirNode.getChildAt(i) as DefaultMutableTreeNode
                        val entry = child.userObject as? FileEntry
                        if (entry != null && entry.name in selectNames) TreePath(child.path) else null
                    }.toTypedArray()
                    if (treePaths.isNotEmpty()) {
                        tree.selectionPaths = treePaths
                        tree.scrollPathToVisible(treePaths.first())
                    }
                }
            }
        }
    }

    private fun selectEntriesByName(names: Set<String>) {
        if (viewMode == ViewMode.TABLE) {
            table.clearSelection()
            var firstSelectedRow = -1
            for (viewRow in 0 until table.rowCount) {
                val modelRow = table.convertRowIndexToModel(viewRow)
                val entry = tableModel.getEntryAt(modelRow) ?: continue
                if (entry.name in names) {
                    table.addRowSelectionInterval(viewRow, viewRow)
                    if (firstSelectedRow == -1) firstSelectedRow = viewRow
                }
            }
            if (firstSelectedRow >= 0) {
                table.scrollRectToVisible(table.getCellRect(firstSelectedRow, 0, true))
            }
        } else if (viewMode == ViewMode.LIST) {
            list.clearSelection()
            val indices = (0 until listModel.size()).filter { listModel.getElementAt(it).name in names }.toIntArray()
            if (indices.isNotEmpty()) {
                list.selectedIndices = indices
                list.ensureIndexIsVisible(indices.first())
            }
        } else if (viewMode == ViewMode.THUMBNAIL) {
            thumbnailList.clearSelection()
            val indices = (0 until thumbnailListModel.size()).filter { thumbnailListModel.getElementAt(it).name in names }.toIntArray()
            if (indices.isNotEmpty()) {
                thumbnailList.selectedIndices = indices
                thumbnailList.ensureIndexIsVisible(indices.first())
            }
        }
    }


    private fun updateStatusBar() {
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
        fileOps.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val fileStore = Files.getFileStore(path)
                    val usableSpace = fileStore.usableSpace
                    val totalSpace = fileStore.totalSpace
                    val pct = if (totalSpace > 0) (usableSpace * 100 / totalSpace) else 0
                    "${formatSize(usableSpace)} of ${formatSize(totalSpace)} free ($pct%)"
                }
                withContext(Dispatchers.EDT) { freeSpaceLabel.text = text }
            } catch (_: Exception) {
                withContext(Dispatchers.EDT) { freeSpaceLabel.text = "" }
            }
        }
    }

    internal fun getSelectedEntry(): FileEntry? {
        if (viewMode == ViewMode.LIST) {
            return list.selectedValue
        }
        if (viewMode == ViewMode.THUMBNAIL) {
            return thumbnailList.selectedValue
        }
        if (viewMode == ViewMode.TREE) {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
            return node.userObject as? FileEntry
        }
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        val modelRow = table.convertRowIndexToModel(viewRow)
        return tableModel.getEntryAt(modelRow)
    }

    private fun loadDrives() {
        val roots = fileOps.getRoots()
        applyDriveRoots(roots)
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

            val widest = roots.maxByOrNull { it.length } ?: ""
            driveCombo.setPrototypeDisplayValue(widest)
            driveCombo.revalidate()
        } finally {
            updatingDriveCombo = false
        }
    }

    private fun startDriveRefreshTimer() {
        driveRefreshTimer = javax.swing.Timer(5000) {
            if (driveComboPopupOpen || currentVfs != null) return@Timer
            fileOps.launch {
                val roots = withContext(Dispatchers.IO) { fileOps.getRoots() }
                withContext(Dispatchers.EDT) { applyDriveRoots(roots) }
            }
        }.apply { start() }
    }

    suspend fun navigateTo(path: Path, selectName: String? = null) {
        val vfs = currentVfs
        val entries = if (vfs != null) {
            vfs.listFiles(path)
        } else {
            fileOps.listFiles(path)
        }
        withContext(Dispatchers.EDT) {
            // Save cursor position and column state for the current directory
            val selectedRow = table.selectedRow
            if (selectedRow >= 0) {
                cursorPositions[currentPath] = selectedRow
            }
            if (initialized) {
                saveColumnState()
            }

            currentPath = path

            // Update path display
            if (vfs != null) {
                val separator = if (vfsStack.first().parentPath.toString().contains("\\")) "\\" else "/"
                val sb = StringBuilder()
                // Build path showing entire VFS stack chain
                for (stackEntry in vfsStack) {
                    if (sb.isEmpty()) {
                        sb.append(stackEntry.parentPath.toString())
                    } else {
                        // For nested archives, show the path within the parent VFS
                        val nestedPath = stackEntry.parentPath.toString().removePrefix("/").replace("/", separator)
                        sb.append(separator).append(nestedPath)
                    }
                }
                val relativePath = path.toString()
                if (!vfs.isRoot(path)) {
                    sb.append(separator).append(relativePath.removePrefix("/").replace("/", separator))
                }
                pathField.text = sb.toString()
            } else {
                pathField.text = path.toString()
            }

            allEntries = entries
            directorySizes.clear()
            // Hide filter on navigation
            if (filterPanel.isVisible) {
                filterField.text = ""
                filterPanel.isVisible = false
            }

            tableModel.setEntries(entries)

            // Update list model
            listModel.clear()
            entries.forEach { listModel.addElement(it) }

            // Update thumbnail model
            thumbnailListModel.clear()
            entries.forEach { thumbnailListModel.addElement(it) }

            // Update tree model (only when not in full tree mode, which manages its own structure)
            if (viewMode != ViewMode.TREE) {
                treeRootNode.removeAllChildren()
                for (entry in entries) {
                    val node = DefaultMutableTreeNode(entry)
                    if (entry.isDirectory && !entry.isParentLink) {
                        node.add(DefaultMutableTreeNode("Loading..."))
                    }
                    treeRootNode.add(node)
                }
                treeModel.nodeStructureChanged(treeRootNode)
            }

            updateStatusBar()
            onDirectoryChanged(this@FileTab)

            // Restore column state for this path
            if (initialized) {
                restoreColumnState(path)
            }

            // Update drive combo (only for real FS)
            if (vfs == null) {
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

            // Select by name, restore saved position, or default to first row
            val targetRow = if (selectName != null) {
                (0 until table.rowCount).firstOrNull { viewRow ->
                    val modelRow = table.convertRowIndexToModel(viewRow)
                    tableModel.getEntryAt(modelRow)?.name == selectName
                }
            } else {
                cursorPositions[path]?.takeIf { it < table.rowCount }
            }

            if (targetRow != null && targetRow < table.rowCount) {
                table.setRowSelectionInterval(targetRow, targetRow)
                table.scrollRectToVisible(table.getCellRect(targetRow, 0, true))
            } else if (table.rowCount > 0) {
                table.setRowSelectionInterval(0, 0)
            }

            // Select in list view too
            val listTarget = if (selectName != null) {
                (0 until listModel.size()).firstOrNull { listModel.getElementAt(it).name == selectName }
            } else {
                null
            }
            if (listTarget != null) {
                list.selectedIndex = listTarget
                list.ensureIndexIsVisible(listTarget)
            } else if (listModel.size() > 0) {
                list.selectedIndex = 0
            }

            // Select in thumbnail view too
            val thumbTarget = if (selectName != null) {
                (0 until thumbnailListModel.size()).firstOrNull { thumbnailListModel.getElementAt(it).name == selectName }
            } else {
                null
            }
            if (thumbTarget != null) {
                thumbnailList.selectedIndex = thumbTarget
                thumbnailList.ensureIndexIsVisible(thumbTarget)
            } else if (thumbnailListModel.size() > 0) {
                thumbnailList.selectedIndex = 0
            }

            initialized = true
        }
    }

    fun refresh() {
        ThumbnailCache.evictDirectory(currentPath)
        val vfs = currentVfs
        if (vfs != null) {
            val relativePath = vfsRelativePath(vfs, currentPath)
            fileOps.launch {
                withContext(Dispatchers.IO) { vfs.flush() }
                writeBackNestedArchives()
                val newPath = if (relativePath.isEmpty()) vfs.root else vfs.root.resolve(relativePath)
                navigateTo(newPath)
            }
        } else {
            fileOps.launch { navigateTo(currentPath) }
        }
    }

    internal suspend fun refreshAfterVfsChange(selectName: String? = null) {
        val vfs = currentVfs
        if (vfs != null) {
            val relativePath = vfsRelativePath(vfs, currentPath)
            vfs.flush()
            writeBackNestedArchives()
            val newPath = if (relativePath.isEmpty()) vfs.root else vfs.root.resolve(relativePath)
            navigateTo(newPath, selectName = selectName)
        } else {
            navigateTo(currentPath, selectName = selectName)
        }
    }

    private suspend fun writeBackNestedArchives() = withContext(Dispatchers.IO) {
        for (i in vfsStack.indices.reversed()) {
            val entry = vfsStack[i]
            if (entry.tempFile == null || i == 0) continue
            // Copy the modified temp file back into the parent VFS
            val parentEntry = vfsStack[i - 1]
            Files.copy(entry.tempFile.toPath(), entry.parentPath, StandardCopyOption.REPLACE_EXISTING)
            // Remember the relative path before flush invalidates the old FileSystem paths
            val relPath = vfsRelativePath(parentEntry.vfs, entry.parentPath)
            parentEntry.vfs.flush()
            // Reconstruct parentPath as a valid path in the reopened FileSystem
            val newParentPath = if (relPath.isEmpty()) parentEntry.vfs.root else parentEntry.vfs.root.resolve(relPath)
            entry.parentPath = newParentPath
        }
    }

    internal fun vfsRelativePath(vfs: VirtualFileSystem, path: Path): String {
        val rootStr = vfs.root.toString().trimEnd('/').trimEnd('\\')
        val pathStr = path.toString()
        return if (pathStr.startsWith(rootStr)) {
            pathStr.removePrefix(rootStr).removePrefix("/").removePrefix("\\")
        } else {
            pathStr
        }
    }

    fun showDriveSelector() {
        driveCombo.requestFocusInWindow()
        driveCombo.showPopup()
    }

    private val toggledRows = mutableSetOf<Int>()
    private val toggledTreeRows = mutableSetOf<Int>()
    private var insideToggle = false
    private val directorySizes = mutableMapOf<Path, Long>()

    private fun applyToggledSelection(cursorRow: Int) {
        val rowsToSelect = toggledRows.toMutableSet()
        rowsToSelect.add(cursorRow)
        table.clearSelection()
        for (row in rowsToSelect) {
            if (row in 0 until table.rowCount) {
                table.addRowSelectionInterval(row, row)
            }
        }
    }

    private fun applyToggledTreeSelection(cursorRow: Int) {
        val rowsToSelect = toggledTreeRows.toMutableSet()
        rowsToSelect.add(cursorRow)
        tree.clearSelection()
        for (row in rowsToSelect) {
            if (row in 0 until tree.rowCount) {
                tree.addSelectionRow(row)
            }
        }
    }

    fun toggleSelectionAndMoveDown() {
        insideToggle = true
        try {
        when (viewMode) {
            ViewMode.TABLE -> {
                val row = table.selectionModel.leadSelectionIndex
                if (row < 0) return
                val modelRow = table.convertRowIndexToModel(row)
                val entry = tableModel.getEntryAt(modelRow)
                if (entry != null && !entry.isParentLink) {
                    if (row in toggledRows) {
                        toggledRows.remove(row)
                    } else {
                        toggledRows.add(row)
                        if (entry.isDirectory) calculateDirectorySize(entry)
                    }
                }
                val nextRow = if (row + 1 < table.rowCount) row + 1 else row
                val nextModelRow = table.convertRowIndexToModel(nextRow)
                val nextEntry = tableModel.getEntryAt(nextModelRow)
                if (nextEntry != null && nextEntry.isDirectory && !nextEntry.isParentLink) {
                    calculateDirectorySize(nextEntry)
                }
                applyToggledSelection(nextRow)
                table.selectionModel.leadSelectionIndex = nextRow
                table.scrollRectToVisible(table.getCellRect(nextRow, 0, true))
            }
            ViewMode.LIST -> {
                val index = list.selectionModel.leadSelectionIndex
                if (index < 0) return
                val entry = listModel.getElementAt(index)
                val selectedSet = list.selectedIndices.toMutableSet()
                if (entry != null && !entry.isParentLink) {
                    if (index in selectedSet) {
                        selectedSet.remove(index)
                    } else {
                        selectedSet.add(index)
                        if (entry.isDirectory) calculateDirectorySize(entry)
                    }
                }
                val nextIndex = if (index + 1 < listModel.size()) index + 1 else index
                val nextEntry = listModel.getElementAt(nextIndex)
                if (nextEntry != null && nextEntry.isDirectory && !nextEntry.isParentLink) {
                    calculateDirectorySize(nextEntry)
                }
                selectedSet.add(nextIndex)
                list.clearSelection()
                for (i in selectedSet) {
                    list.addSelectionInterval(i, i)
                }
                list.ensureIndexIsVisible(nextIndex)
            }
            ViewMode.THUMBNAIL -> {
                val index = thumbnailList.selectionModel.leadSelectionIndex
                if (index < 0) return
                val entry = thumbnailListModel.getElementAt(index)
                val selectedSet = thumbnailList.selectedIndices.toMutableSet()
                if (entry != null && !entry.isParentLink) {
                    if (index in selectedSet) {
                        selectedSet.remove(index)
                    } else {
                        selectedSet.add(index)
                        if (entry.isDirectory) calculateDirectorySize(entry)
                    }
                }
                val nextIndex = if (index + 1 < thumbnailListModel.size()) index + 1 else index
                val nextEntry = thumbnailListModel.getElementAt(nextIndex)
                if (nextEntry != null && nextEntry.isDirectory && !nextEntry.isParentLink) {
                    calculateDirectorySize(nextEntry)
                }
                selectedSet.add(nextIndex)
                thumbnailList.clearSelection()
                for (i in selectedSet) {
                    thumbnailList.addSelectionInterval(i, i)
                }
                thumbnailList.ensureIndexIsVisible(nextIndex)
            }
            ViewMode.TREE -> {
                val leadRow = tree.leadSelectionRow
                if (leadRow < 0) return
                val node = (tree.getPathForRow(leadRow)?.lastPathComponent) as? DefaultMutableTreeNode
                val entry = node?.userObject as? FileEntry
                if (entry != null && !entry.isParentLink) {
                    if (leadRow in toggledTreeRows) {
                        toggledTreeRows.remove(leadRow)
                    } else {
                        toggledTreeRows.add(leadRow)
                        if (entry.isDirectory) calculateDirectorySize(entry)
                    }
                }
                val nextRow = if (leadRow + 1 < tree.rowCount) leadRow + 1 else leadRow
                val nextNode = (tree.getPathForRow(nextRow)?.lastPathComponent) as? DefaultMutableTreeNode
                val nextEntry = nextNode?.userObject as? FileEntry
                if (nextEntry != null && nextEntry.isDirectory && !nextEntry.isParentLink) {
                    calculateDirectorySize(nextEntry)
                }
                applyToggledTreeSelection(nextRow)
                tree.scrollRowToVisible(nextRow)
            }
        }
        } finally {
            insideToggle = false
        }
    }

    private fun calculateDirectorySize(entry: FileEntry) {
        if (!entry.isDirectory || entry.isParentLink) return
        if (!TurtleCommanderSettings.getInstance().state.calculateDirectorySize) return
        if (directorySizes.containsKey(entry.path)) return
        fileOps.launch {
            val size = withContext(Dispatchers.IO) {
                var total = 0L
                try {
                    Files.walkFileTree(entry.path, object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            total += attrs.size()
                            return FileVisitResult.CONTINUE
                        }
                        override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                            return FileVisitResult.CONTINUE
                        }
                    })
                } catch (_: Exception) {}
                total
            }
            directorySizes[entry.path] = size
            withContext(Dispatchers.EDT) {
                val modelRow = (0 until tableModel.rowCount).firstOrNull {
                    tableModel.getEntryAt(it)?.path == entry.path
                }
                if (modelRow != null) {
                    tableModel.fireTableCellUpdated(modelRow, FileTableModel.COL_SIZE)
                }
                updateStatusBar()
            }
        }
    }

    fun getSelectedEntries(): List<FileEntry> {
        if (viewMode == ViewMode.LIST) {
            return list.selectedValuesList.filter { !it.isParentLink }
        }
        if (viewMode == ViewMode.THUMBNAIL) {
            return thumbnailList.selectedValuesList.filter { !it.isParentLink }
        }
        if (viewMode == ViewMode.TREE) {
            return (tree.selectionPaths ?: emptyArray()).mapNotNull { path ->
                val node = path.lastPathComponent as? DefaultMutableTreeNode
                node?.userObject as? FileEntry
            }.filter { !it.isParentLink }
        }
        return table.selectedRows.toList()
            .map { table.convertRowIndexToModel(it) }
            .mapNotNull { tableModel.getEntryAt(it) }
            .filter { !it.isParentLink }
    }

    fun showContextMenu() {
        val entry = getSelectedEntry()
        FileContextMenuState.clickedEntry = entry
        FileContextMenuState.clickedTab = this

        val am = ActionManager.getInstance()
        val group = am.getAction("TurtleCommander.FileContextMenu") as? ActionGroup ?: return
        val popupMenu = am.createActionPopupMenu("TurtleCommander.FileContextMenu", group)

        when (viewMode) {
            ViewMode.TABLE -> {
                val row = table.selectedRow
                if (row >= 0) {
                    val rect = table.getCellRect(row, 0, true)
                    popupMenu.component.show(table, rect.x, rect.y + rect.height)
                } else {
                    popupMenu.component.show(table, 0, 0)
                }
            }
            ViewMode.LIST -> {
                val index = list.selectedIndex
                if (index >= 0) {
                    val rect = list.getCellBounds(index, index)
                    popupMenu.component.show(list, rect.x, rect.y + rect.height)
                } else {
                    popupMenu.component.show(list, 0, 0)
                }
            }
            ViewMode.THUMBNAIL -> {
                val index = thumbnailList.selectedIndex
                if (index >= 0) {
                    val rect = thumbnailList.getCellBounds(index, index)
                    popupMenu.component.show(thumbnailList, rect.x, rect.y + rect.height)
                } else {
                    popupMenu.component.show(thumbnailList, 0, 0)
                }
            }
            ViewMode.TREE -> {
                val row = tree.leadSelectionRow
                if (row >= 0) {
                    val rect = tree.getRowBounds(row)
                    popupMenu.component.show(tree, rect.x, rect.y + rect.height)
                } else {
                    popupMenu.component.show(tree, 0, 0)
                }
            }
        }
    }

    fun openSelectedEntry() {
        val entry = getSelectedEntry() ?: return
        if (entry.isParentLink) {
            goUp()
            return
        }
        if (entry.isDirectory) {
            fileOps.launch { navigateTo(entry.path) }
        } else if (isEntryBrowsableArchive(entry)) {
            enterVfs(entry.path)
        } else {
            openFile(entry)
        }
    }

    private fun isEntryBrowsableArchive(entry: FileEntry): Boolean {
        if (entry.isDirectory || entry.isParentLink) return false
        return if (currentVfs != null) {
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

    private fun handleVfsBreadcrumbClick(segmentPath: String) {
        // Build the path prefix for each VFS level to determine which level was clicked
        val separator = if (vfsStack.first().parentPath.toString().contains("\\")) "\\" else "/"
        val outerArchiveStr = vfsStack.first().parentPath.toString()

        // Check if the click is outside of all VFS levels (on the real filesystem)
        if (segmentPath.length < outerArchiveStr.length) {
            // Exit all VFS levels and navigate to real filesystem
            dispose()
            val path = try { Path.of(segmentPath) } catch (_: Exception) { null }
            if (path != null && path.toFile().isDirectory) {
                fileOps.launch { navigateTo(path) }
            }
            return
        }

        // Build cumulative path prefixes for each VFS level
        val prefixes = mutableListOf<String>()
        val sb = StringBuilder()
        for (stackEntry in vfsStack) {
            if (sb.isEmpty()) {
                sb.append(stackEntry.parentPath.toString())
            } else {
                val nestedPath = stackEntry.parentPath.toString().removePrefix("/").replace("/", separator)
                sb.append(separator).append(nestedPath)
            }
            prefixes.add(sb.toString())
        }

        // Find which VFS level the click falls into
        var targetLevel = -1
        for (i in prefixes.indices.reversed()) {
            if (segmentPath.length >= prefixes[i].length) {
                targetLevel = i
                break
            }
        }

        if (targetLevel < 0) return

        // Pop VFS levels above the target
        while (vfsStack.size > targetLevel + 1) {
            val entry = vfsStack.removeLast()
            entry.vfs.close()
            if (entry.tempFile != null) {
                try {
                    entry.tempFile.delete()
                    entry.tempFile.parentFile?.delete()
                } catch (_: Exception) {}
            }
        }

        val vfs = vfsStack.last().vfs
        val archivePrefix = prefixes[targetLevel]
        val relativePath = segmentPath.removePrefix(archivePrefix)
            .removePrefix("\\").removePrefix("/")
            .replace("\\", "/")
        val vfsPath = if (relativePath.isEmpty()) vfs.root else vfs.getPath("/$relativePath")
        fileOps.launch { navigateTo(vfsPath) }
    }

    private fun enterVfs(archivePath: Path) {
        try {
            if (vfsStack.isEmpty()) {
                // Entering archive from real filesystem
                val vfs = VirtualFileSystemRegistry.create(archivePath)
                vfsStack.add(VfsStackEntry(vfs, archivePath))
                fileOps.launch { navigateTo(vfs.root) }
            } else {
                // Entering archive inside another archive — extract to temp
                fileOps.launch {
                    try {
                        val tempFile = withContext(Dispatchers.IO) {
                            val tempDir = Files.createTempDirectory("turtle-vfs-")
                            val fileName = archivePath.fileName.toString()
                            val tempPath = tempDir.resolve(fileName)
                            Files.copy(archivePath, tempPath)
                            tempPath.toFile()
                        }
                        val vfs = VirtualFileSystemRegistry.create(tempFile.toPath())
                        vfsStack.add(VfsStackEntry(vfs, archivePath, tempFile))
                        navigateTo(vfs.root)
                    } catch (e: Exception) {
                        fileErrorNotification("Cannot open nested archive: ${fileErrorMessage(e)}")
                    }
                }
            }
        } catch (e: Exception) {
            fileErrorNotification("Cannot open archive: ${fileErrorMessage(e)}")
        }
    }

    private fun exitVfs() {
        if (vfsStack.isEmpty()) return
        val entry = vfsStack.removeLast()
        entry.vfs.close()
        if (entry.tempFile != null) {
            try {
                entry.tempFile.delete()
                entry.tempFile.parentFile?.delete()
            } catch (_: Exception) {}
        }
        val parentPath = entry.parentPath
        if (vfsStack.isNotEmpty()) {
            // Return to parent VFS — navigate to directory containing the inner archive
            val parentVfsPath = parentPath.parent ?: vfsStack.last().vfs.root
            fileOps.launch { navigateTo(parentVfsPath, selectName = parentPath.fileName.toString()) }
        } else {
            // Return to real filesystem
            fileOps.launch { navigateTo(parentPath.parent ?: parentPath, selectName = parentPath.fileName.toString()) }
        }
    }

    fun dispose() {
        driveRefreshTimer?.stop()
        driveRefreshTimer = null
        for (entry in vfsStack.asReversed()) {
            entry.vfs.close()
            if (entry.tempFile != null) {
                try {
                    entry.tempFile.delete()
                    entry.tempFile.parentFile?.delete()
                } catch (_: Exception) {}
            }
        }
        vfsStack.clear()
    }

    fun getDisplayPath(): String {
        val vfs = currentVfs ?: return currentPath.toString()
        val separator = if (vfsStack.first().parentPath.toString().contains("\\")) "\\" else "/"
        val sb = StringBuilder()
        for (stackEntry in vfsStack) {
            if (sb.isEmpty()) {
                sb.append(stackEntry.parentPath.toString())
            } else {
                val nestedPath = stackEntry.parentPath.toString().removePrefix("/").replace("/", separator)
                sb.append(separator).append(nestedPath)
            }
        }
        if (!vfs.isRoot(currentPath)) {
            val relativePath = currentPath.toString()
            sb.append(separator).append(relativePath.removePrefix("/").replace("/", separator))
        }
        return sb.toString()
    }

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
        onSwitchToOtherPanel: () -> Unit,
        onRefreshOtherPanel: () -> Unit,
    ) {
        this.otherPanelPathProvider = otherPanelPathProvider
        this.onDirectoryChanged = onDirectoryChanged
        this.onSwitchToOtherPanel = onSwitchToOtherPanel
        this.onRefreshOtherPanel = onRefreshOtherPanel
    }

    fun applyColumnState(widths: List<Int>, order: List<Int>) {
        val cm = table.columnModel
        if (order.size == cm.columnCount) {
            for (targetIdx in 0 until order.size) {
                val modelIdx = order[targetIdx]
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
        val svc = stateService ?: return
        val cm = table.columnModel
        val widths = mutableListOf<Int>()
        val order = mutableListOf<Int>()
        for (i in 0 until cm.columnCount) {
            val col = cm.getColumn(i)
            widths.add(col.width)
            order.add(col.modelIndex)
            col.preferredWidth = col.width
        }
        svc.putColumnState(currentPath.toString(), widths, order)
    }

    private fun restoreColumnState(path: Path) {
        val svc = stateService ?: return
        val entry = svc.getColumnState(path.toString()) ?: return
        val widths = svc.parseWidths(entry)
        val order = svc.parseOrder(entry)
        applyColumnState(widths, order)
    }

    internal fun fileErrorNotification(content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Turtle Commander")
            .createNotification(content, NotificationType.ERROR)
            .notify(project)
    }

    companion object {
        val FILE_ENTRY_FLAVOR = DataFlavor(
            DataFlavor.javaJVMLocalObjectMimeType + ";class=java.util.List",
            "FileEntry List",
        )
        private const val VIEW_TABLE = "table"
        private const val VIEW_LIST = "list"
        private const val VIEW_THUMBNAIL = "thumbnail"
        private const val VIEW_TREE = "tree"
    }
}

fun isArchiveFile(entry: FileEntry): Boolean {
    if (entry.isDirectory) return false
    return VirtualFileSystemRegistry.supportsByExtension(entry.name)
}

fun fileEntryIcon(entry: FileEntry, enableFileNameHighlighting: Boolean): javax.swing.Icon? {
    return when {
        entry.isParentLink -> AllIcons.Nodes.UpLevel
        entry.isDirectory -> if (enableFileNameHighlighting) {
            DirectoryIcons.getIcon(entry.directoryType)
        } else {
            AllIcons.Nodes.Folder
        }
        isArchiveFile(entry) -> AllIcons.FileTypes.Archive
        else -> FileTypeManager.getInstance().getFileTypeByFileName(entry.name).icon
            ?: AllIcons.FileTypes.Any_type
    }
}

enum class ViewMode { TABLE, LIST, THUMBNAIL, TREE }
