package io.github.jhspetersson.turtlecommander

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultCellEditor
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.TransferHandler
import javax.swing.event.CellEditorListener
import javax.swing.event.ChangeEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class FileTab(
    private val project: Project,
    initialPath: Path,
    private var otherPanelPathProvider: () -> Path?,
    private var onDirectoryChanged: (FileTab) -> Unit,
    private var onSwitchToOtherPanel: () -> Unit = {},
    private var onRefreshOtherPanel: () -> Unit = {},
) : JPanel(BorderLayout()) {

    private val fileOps = project.service<FileOperationService>()
    private val tableModel = FileTableModel()
    val table = JBTable(tableModel)
    private val defaultTableFont by lazy { table.font }

    private val listModel = DefaultListModel<FileEntry>()
    val list = JBList(listModel)
    private val treeRootNode = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(treeRootNode)
    val tree = JTree(treeModel)
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
    private var updatingDriveCombo = false
    private val cursorPositions = mutableMapOf<Path, Int>()
    private var stateService: FileManagerStateService? = null
    private var initialized = false
    private var enableFileNameHighlighting = TurtleCommanderSettings.getInstance().state.enableFileNameHighlighting

    var currentPath: Path = initialPath
        private set

    private data class VfsStackEntry(
        val vfs: VirtualFileSystem,
        val parentPath: Path,
        val tempFile: java.io.File? = null,
    )

    private val vfsStack = mutableListOf<VfsStackEntry>()

    var currentVfs: VirtualFileSystem?
        get() = vfsStack.lastOrNull()?.vfs
        private set(_) {}  // managed via stack

    init {
        setupHeader()
        setupTable()
        setupList()
        setupTree()
        setupViewPanel()
        loadDrives()
        applyPanelFont()
        applyVisibilitySettings()
    }

    fun applyVisibilitySettings() {
        val settings = TurtleCommanderSettings.getInstance().state
        driveCombo.isVisible = !settings.hideDriveSelector
        statusPanel.isVisible = !settings.hideStatusBar
        enableFileNameHighlighting = settings.enableFileNameHighlighting
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
            tree.font = font
            tree.rowHeight = font.size + 6
        } else if (size > 0) {
            table.font = defaultTableFont.deriveFont(size.toFloat())
            table.rowHeight = size + 6
            list.font = defaultTableFont.deriveFont(size.toFloat())
            list.fixedCellHeight = size + 6
            tree.font = defaultTableFont.deriveFont(size.toFloat())
            tree.rowHeight = size + 6
        } else {
            table.font = defaultTableFont
            table.rowHeight = 20
            list.font = defaultTableFont
            list.fixedCellHeight = 20
            tree.font = defaultTableFont
            tree.rowHeight = 20
        }
    }

    private fun setupHeader() {
        val headerPanel = JPanel(BorderLayout())

        driveCombo.apply {
            addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {}
                override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) {
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
                override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) {
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
            autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            rowHeight = 20

            columnModel.getColumn(0).cellRenderer = FileNameCellRenderer()
            columnModel.getColumn(0).preferredWidth = 200
            columnModel.getColumn(1).preferredWidth = 50
            columnModel.getColumn(1).cellRenderer = DisplayValueRenderer()
            columnModel.getColumn(2).preferredWidth = 80
            columnModel.getColumn(2).cellRenderer = DisplayValueRenderer()
            columnModel.getColumn(3).preferredWidth = 130
            columnModel.getColumn(3).cellRenderer = DisplayValueRenderer()
            columnModel.getColumn(4).preferredWidth = 80
            columnModel.getColumn(4).cellRenderer = DisplayValueRenderer()

            rowSorter = ParentPinningRowSorter(tableModel)

            val nameEditor = DefaultCellEditor(JTextField())
            nameEditor.clickCountToStart = Int.MAX_VALUE // only start editing via F2
            nameEditor.addCellEditorListener(object : CellEditorListener {
                override fun editingStopped(e: ChangeEvent) {
                    val viewRow = table.editingRow
                    if (viewRow < 0) return
                    val modelRow = table.convertRowIndexToModel(viewRow)
                    val entry = tableModel.getEntryAt(modelRow) ?: return
                    val newName = (nameEditor.cellEditorValue as? String)?.trim() ?: return
                    if (newName.isNotEmpty() && newName != entry.name) {
                        performRename(entry, newName)
                    }
                }

                override fun editingCanceled(e: ChangeEvent) {}
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

                    val am = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    val group = am.getAction("TurtleCommander.FileContextMenu") as? com.intellij.openapi.actionSystem.ActionGroup ?: return
                    val popupMenu = am.createActionPopupMenu("TurtleCommander.FileContextMenu", group)
                    popupMenu.component.show(table, e.x, e.y)
                }
            })

            addFocusListener(object : java.awt.event.FocusListener {
                override fun focusGained(e: java.awt.event.FocusEvent) {
                    repaint()
                }
                override fun focusLost(e: java.awt.event.FocusEvent) {
                    repaint()
                }
            })

            dragEnabled = true
            transferHandler = FileEntryTransferHandler()

            selectionModel.addListSelectionListener {
                if (!insideToggle) toggledRows.clear()
                updateStatusBar()
            }
        }
    }

    private fun setupList() {
        list.apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            layoutOrientation = javax.swing.JList.VERTICAL_WRAP
            visibleRowCount = 0
            cellRenderer = FileListCellRenderer()

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

        val am = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val group = am.getAction("TurtleCommander.FileContextMenu") as? com.intellij.openapi.actionSystem.ActionGroup ?: return
        val popupMenu = am.createActionPopupMenu("TurtleCommander.FileContextMenu", group)
        popupMenu.component.show(list, e.x, e.y)
    }

    private fun setupTree() {
        tree.apply {
            isRootVisible = false
            showsRootHandles = true
            background = table.background
            selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
            cellRenderer = FileTreeCellRenderer()

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

            addTreeSelectionListener { updateStatusBar() }

            addTreeWillExpandListener(object : javax.swing.event.TreeWillExpandListener {
                override fun treeWillExpand(event: javax.swing.event.TreeExpansionEvent) {
                    val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val entry = node.userObject as? FileEntry ?: return
                    if (entry.isDirectory && !entry.isParentLink && node.childCount == 1) {
                        val firstChild = node.getChildAt(0) as? DefaultMutableTreeNode
                        if (firstChild?.userObject is String) {
                            // Loading placeholder — load real children
                            node.removeAllChildren()
                            try {
                                val children = kotlinx.coroutines.runBlocking {
                                    val vfs = currentVfs
                                    if (vfs != null) vfs.listFiles(entry.path) else fileOps.listFiles(entry.path)
                                }
                                for (child in children) {
                                    if (child.isParentLink) continue
                                    val childNode = DefaultMutableTreeNode(child)
                                    if (child.isDirectory) {
                                        childNode.add(DefaultMutableTreeNode("Loading..."))
                                    }
                                    node.add(childNode)
                                }
                            } catch (_: Exception) {
                                // ignore
                            }
                            treeModel.nodeStructureChanged(node)
                        }
                    }
                }

                override fun treeWillCollapse(event: javax.swing.event.TreeExpansionEvent) {}
            })
        }
    }

    private fun handleTreeContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val treePath = tree.getPathForLocation(e.x, e.y)
        if (treePath != null) {
            tree.selectionPath = treePath
        }
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val entry = node?.userObject as? FileEntry
        FileContextMenuState.clickedEntry = entry
        FileContextMenuState.clickedTab = this

        val am = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val group = am.getAction("TurtleCommander.FileContextMenu") as? com.intellij.openapi.actionSystem.ActionGroup ?: return
        val popupMenu = am.createActionPopupMenu("TurtleCommander.FileContextMenu", group)
        popupMenu.component.show(tree, e.x, e.y)
    }

    private fun setupViewPanel() {
        viewPanel.add(JBScrollPane(table), VIEW_TABLE)
        viewPanel.add(JBScrollPane(list), VIEW_LIST)
        viewPanel.add(JBScrollPane(tree), VIEW_TREE)
        val initialCard = when (viewMode) {
            ViewMode.TABLE -> VIEW_TABLE
            ViewMode.LIST -> VIEW_LIST
            ViewMode.TREE -> VIEW_TREE
        }
        viewCardLayout.show(viewPanel, initialCard)

        add(viewPanel, BorderLayout.CENTER)

        statusLabel.border = javax.swing.BorderFactory.createEmptyBorder(2, 4, 2, 4)
        freeSpaceLabel.border = javax.swing.BorderFactory.createEmptyBorder(2, 4, 2, 4)
        statusPanel.add(statusLabel, BorderLayout.WEST)
        statusPanel.add(freeSpaceLabel, BorderLayout.EAST)
        add(statusPanel, BorderLayout.SOUTH)
    }

    fun setViewMode(mode: ViewMode) {
        val previousMode = viewMode
        if (previousMode == mode) return

        // Capture selection from the current (old) view before switching
        val selectedEntries = getSelectedEntries()
        val selectedNames = selectedEntries.map { it.name }.toSet()

        // For tree -> flat: navigate to the parent directory of the selected entries and select them
        val treeNavigation = if (previousMode == ViewMode.TREE && mode != ViewMode.TREE && selectedEntries.isNotEmpty()) {
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
            ViewMode.LIST -> {
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
                list.requestFocusInWindow()
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
                if (vfs != null) vfs.listFiles(ancestors[0]) else fileOps.listFiles(ancestors[0])
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
                    if (vfs != null) vfs.listFiles(ancestors[i]) else fileOps.listFiles(ancestors[i])
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

    private fun getTreeSelectedEntries(): List<FileEntry> {
        return (tree.selectionPaths ?: emptyArray()).mapNotNull { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode
            node?.userObject as? FileEntry
        }.filter { !it.isParentLink }
    }

    private fun selectEntriesByName(names: Set<String>) {
        if (viewMode == ViewMode.TABLE) {
            table.clearSelection()
            for (viewRow in 0 until table.rowCount) {
                val modelRow = table.convertRowIndexToModel(viewRow)
                val entry = tableModel.getEntryAt(modelRow) ?: continue
                if (entry.name in names) {
                    table.addRowSelectionInterval(viewRow, viewRow)
                }
            }
        } else if (viewMode == ViewMode.LIST) {
            list.clearSelection()
            val indices = (0 until listModel.size()).filter { listModel.getElementAt(it).name in names }.toIntArray()
            if (indices.isNotEmpty()) {
                list.selectedIndices = indices
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
        sb.append("$dirs dir(s), $files file(s), ${tableModel.formatSize(totalSize)}")

        val selectedEntries = getSelectedEntries()
        if (selectedEntries.isNotEmpty()) {
            val selectedFiles = selectedEntries.filter { !it.isDirectory }
            val selectedSize = selectedFiles.sumOf { it.size }
            sb.append("  |  ${selectedEntries.size} selected, ${tableModel.formatSize(selectedSize)}")
        }

        statusLabel.text = sb.toString()

        try {
            val fileStore = Files.getFileStore(currentPath)
            val usableSpace = fileStore.usableSpace
            val totalSpace = fileStore.totalSpace
            val pct = if (totalSpace > 0) (usableSpace * 100 / totalSpace) else 0
            freeSpaceLabel.text = "${tableModel.formatSize(usableSpace)} of ${tableModel.formatSize(totalSpace)} free ($pct%)"
        } catch (_: Exception) {
            freeSpaceLabel.text = ""
        }
    }

    private fun getSelectedEntry(): FileEntry? {
        if (viewMode == ViewMode.LIST) {
            return list.selectedValue
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

            tableModel.setEntries(entries)

            // Update list model
            listModel.clear()
            entries.forEach { listModel.addElement(it) }

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

            initialized = true
        }
    }

    fun refresh() {
        val vfs = currentVfs
        if (vfs != null) {
            val relativePath = currentPath.toString()
            vfs.flush()
            fileOps.launch { navigateTo(vfs.getPath(relativePath)) }
        } else {
            fileOps.launch { navigateTo(currentPath) }
        }
    }

    private suspend fun refreshAfterVfsChange(selectName: String? = null) {
        val vfs = currentVfs
        if (vfs != null) {
            val relativePath = currentPath.toString()
            vfs.flush()
            navigateTo(vfs.getPath(relativePath), selectName = selectName)
        } else {
            navigateTo(currentPath, selectName = selectName)
        }
    }

    fun showDriveSelector() {
        driveCombo.requestFocusInWindow()
        driveCombo.showPopup()
    }

    private val toggledRows = mutableSetOf<Int>()
    private var insideToggle = false

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
                    }
                }
                val nextRow = if (row + 1 < table.rowCount) row + 1 else row
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
                    }
                }
                val nextIndex = if (index + 1 < listModel.size()) index + 1 else index
                selectedSet.add(nextIndex)
                list.clearSelection()
                for (i in selectedSet) {
                    list.addSelectionInterval(i, i)
                }
                list.ensureIndexIsVisible(nextIndex)
            }
            ViewMode.TREE -> {
                val leadRow = tree.leadSelectionRow
                if (leadRow < 0) return
                val path = tree.getPathForRow(leadRow) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode
                val entry = node?.userObject as? FileEntry
                val selectedPaths = (tree.selectionPaths ?: emptyArray()).toMutableSet()
                if (entry != null && !entry.isParentLink) {
                    if (path in selectedPaths) {
                        selectedPaths.remove(path)
                    } else {
                        selectedPaths.add(path)
                    }
                }
                val nextRow = if (leadRow + 1 < tree.rowCount) leadRow + 1 else leadRow
                val nextPath = tree.getPathForRow(nextRow)
                if (nextPath != null) selectedPaths.add(nextPath)
                tree.selectionPaths = selectedPaths.toTypedArray()
                tree.scrollPathToVisible(nextPath)
            }
        }
        } finally {
            insideToggle = false
        }
    }

    fun clearToggledRows() {
        toggledRows.clear()
    }

    fun getSelectedEntries(): List<FileEntry> {
        if (viewMode == ViewMode.LIST) {
            return list.selectedValuesList.filter { !it.isParentLink }
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

        val am = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val group = am.getAction("TurtleCommander.FileContextMenu") as? com.intellij.openapi.actionSystem.ActionGroup ?: return
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

    fun viewSelectedFile() {
        val entry = getSelectedEntry() ?: return
        if (!entry.isParentLink && !entry.isDirectory) {
            openFile(entry)
        }
    }

    fun openSelectedInAssociatedApp() {
        val entry = getSelectedEntry() ?: return
        if (!entry.isParentLink && !entry.isDirectory) {
            try {
                java.awt.Desktop.getDesktop().open(entry.path.toFile())
            } catch (e: Exception) {
                fileErrorNotification("Failed to open file: ${e.message}")
            }
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
                            val tempDir = java.nio.file.Files.createTempDirectory("turtle-vfs-")
                            val fileName = archivePath.fileName.toString()
                            val tempPath = tempDir.resolve(fileName)
                            java.nio.file.Files.copy(archivePath, tempPath)
                            tempPath.toFile()
                        }
                        val vfs = VirtualFileSystemRegistry.create(tempFile.toPath())
                        vfsStack.add(VfsStackEntry(vfs, archivePath, tempFile))
                        navigateTo(vfs.root)
                    } catch (e: Exception) {
                        fileErrorNotification("Cannot open nested archive: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            fileErrorNotification("Cannot open archive: ${e.message}")
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

    fun performCopy() {
        performCopyEntries(getSelectedEntries(), otherPanelPathProvider() ?: return)
    }

    fun performCopyEntries(selected: List<FileEntry>, destination: Path) {
        if (selected.isEmpty()) return

        val copyDialog = CopyDialog(project, selected, destination)
        if (!copyDialog.showAndGet()) return
        val overwriteAll = copyDialog.overwriteExisting
        val sourcePaths = selected.map { it.path }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying files", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Counting files..."

                runBlocking {
                    val totalFiles = fileOps.countFiles(sourcePaths)
                    indicator.isIndeterminate = false

                    fileOps.copyFilesWithProgress(
                        sources = sourcePaths,
                        destination = destination,
                        overwriteAll = overwriteAll,
                        onProgress = { count, name ->
                            indicator.fraction = count.toDouble() / totalFiles
                            indicator.text = "Copying $count / $totalFiles"
                            indicator.text2 = name
                        },
                        onOverwriteConfirm = { path ->
                            withContext(Dispatchers.EDT) {
                                val result = Messages.showDialog(
                                    project,
                                    "File already exists:\n${path.fileName}\n\nOverwrite?",
                                    "File Exists",
                                    arrayOf("Yes", "No", "Yes to All", "No to All"),
                                    0,
                                    Messages.getQuestionIcon(),
                                )
                                when (result) {
                                    0 -> FileOperationService.OverwriteResponse.YES
                                    1 -> FileOperationService.OverwriteResponse.NO
                                    2 -> FileOperationService.OverwriteResponse.YES_TO_ALL
                                    3 -> FileOperationService.OverwriteResponse.NO_TO_ALL
                                    else -> FileOperationService.OverwriteResponse.NO
                                }
                            }
                        },
                        onError = { path, error ->
                            fileErrorNotification("Failed to copy ${path.fileName}: ${error.message}")
                        },
                        isCancelled = { indicator.isCanceled },
                    )

                    refreshAfterVfsChange()
                    withContext(Dispatchers.EDT) {
                        onRefreshOtherPanel()
                    }
                }
            }
        })
    }

    fun performMove() {
        val selected = getSelectedEntries()
        if (selected.isEmpty()) return
        val destination = otherPanelPathProvider() ?: return

        val moveDialog = MoveDialog(project, selected, destination)
        if (!moveDialog.showAndGet()) return
        val overwriteAll = moveDialog.overwriteExisting
        val sourcePaths = selected.map { it.path }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Moving files", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Counting files..."

                runBlocking {
                    val totalFiles = fileOps.countFiles(sourcePaths)
                    indicator.isIndeterminate = false

                    fileOps.moveFilesWithProgress(
                        sources = sourcePaths,
                        destination = destination,
                        overwriteAll = overwriteAll,
                        onProgress = { count, name ->
                            indicator.fraction = count.toDouble() / totalFiles
                            indicator.text = "Moving $count / $totalFiles"
                            indicator.text2 = name
                        },
                        onOverwriteConfirm = { path ->
                            withContext(Dispatchers.EDT) {
                                val result = Messages.showDialog(
                                    project,
                                    "File already exists:\n${path.fileName}\n\nOverwrite?",
                                    "File Exists",
                                    arrayOf("Yes", "No", "Yes to All", "No to All"),
                                    0,
                                    Messages.getQuestionIcon(),
                                )
                                when (result) {
                                    0 -> FileOperationService.OverwriteResponse.YES
                                    1 -> FileOperationService.OverwriteResponse.NO
                                    2 -> FileOperationService.OverwriteResponse.YES_TO_ALL
                                    3 -> FileOperationService.OverwriteResponse.NO_TO_ALL
                                    else -> FileOperationService.OverwriteResponse.NO
                                }
                            }
                        },
                        onError = { path, error ->
                            fileErrorNotification("Failed to move ${path.fileName}: ${error.message}")
                        },
                        isCancelled = { indicator.isCanceled },
                    )

                    refreshAfterVfsChange()
                    withContext(Dispatchers.EDT) {
                        onRefreshOtherPanel()
                    }
                }
            }
        })
    }

    fun performMoveEntries(selected: List<FileEntry>, destination: Path) {
        if (selected.isEmpty()) return

        val moveDialog = MoveDialog(project, selected, destination)
        if (!moveDialog.showAndGet()) return
        val overwriteAll = moveDialog.overwriteExisting
        val sourcePaths = selected.map { it.path }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Moving files", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Counting files..."

                runBlocking {
                    val totalFiles = fileOps.countFiles(sourcePaths)
                    indicator.isIndeterminate = false

                    fileOps.moveFilesWithProgress(
                        sources = sourcePaths,
                        destination = destination,
                        overwriteAll = overwriteAll,
                        onProgress = { count, name ->
                            indicator.fraction = count.toDouble() / totalFiles
                            indicator.text = "Moving $count / $totalFiles"
                            indicator.text2 = name
                        },
                        onOverwriteConfirm = { path ->
                            withContext(Dispatchers.EDT) {
                                val result = Messages.showDialog(
                                    project,
                                    "File already exists:\n${path.fileName}\n\nOverwrite?",
                                    "File Exists",
                                    arrayOf("Yes", "No", "Yes to All", "No to All"),
                                    0,
                                    Messages.getQuestionIcon(),
                                )
                                when (result) {
                                    0 -> FileOperationService.OverwriteResponse.YES
                                    1 -> FileOperationService.OverwriteResponse.NO
                                    2 -> FileOperationService.OverwriteResponse.YES_TO_ALL
                                    3 -> FileOperationService.OverwriteResponse.NO_TO_ALL
                                    else -> FileOperationService.OverwriteResponse.NO
                                }
                            }
                        },
                        onError = { path, error ->
                            fileErrorNotification("Failed to move ${path.fileName}: ${error.message}")
                        },
                        isCancelled = { indicator.isCanceled },
                    )

                    refreshAfterVfsChange()
                    withContext(Dispatchers.EDT) {
                        onRefreshOtherPanel()
                    }
                }
            }
        })
    }

    fun performDelete() {
        val selected = getSelectedEntries()
        if (selected.isEmpty()) return

        val deleteDialog = DeleteDialog(project, selected)
        if (!deleteDialog.showAndGet()) return
        val sourcePaths = selected.map { it.path }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Deleting files", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Counting files..."

                runBlocking {
                    val totalFiles = fileOps.countFiles(sourcePaths)
                    indicator.isIndeterminate = false

                    fileOps.deleteFilesWithProgress(
                        paths = sourcePaths,
                        onProgress = { count, name ->
                            indicator.fraction = count.toDouble() / totalFiles
                            indicator.text = "Deleting $count / $totalFiles"
                            indicator.text2 = name
                        },
                        onError = { path, error ->
                            fileErrorNotification("Failed to delete ${path.fileName}: ${error.message}")
                        },
                        isCancelled = { indicator.isCanceled },
                    )

                    refreshAfterVfsChange()
                }
            }
        })
    }

    fun performCreateDirectory() {
        val name = Messages.showInputDialog(
            project,
            "Enter directory name:",
            "New Directory",
            Messages.getQuestionIcon(),
        )
        if (name.isNullOrBlank()) return

        fileOps.launch {
            try {
                fileOps.createDirectory(currentPath, name)
                navigateTo(currentPath, selectName = name)
            } catch (e: Exception) {
                fileErrorNotification("Create directory failed: ${e.message}")
            }
        }
    }

    fun performCreateFile() {
        val name = Messages.showInputDialog(
            project,
            "Enter file name:",
            "New File",
            Messages.getQuestionIcon(),
        )
        if (name.isNullOrBlank()) return

        fileOps.launch {
            try {
                val filePath = withContext(Dispatchers.IO) {
                    java.nio.file.Files.createFile(currentPath.resolve(name))
                }
                navigateTo(currentPath, selectName = name)
                withContext(Dispatchers.EDT) {
                    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath)
                    if (virtualFile != null) {
                        OpenFileDescriptor(project, virtualFile).navigate(true)
                    }
                }
            } catch (e: Exception) {
                fileErrorNotification("Create file failed: ${e.message}")
            }
        }
    }

    fun performPack() {
        val selected = getSelectedEntries()
        if (selected.isEmpty()) return
        val destination = otherPanelPathProvider() ?: currentPath

        val archiveName = if (selected.size == 1) {
            selected[0].name + ".zip"
        } else {
            "archive.zip"
        }
        val defaultArchivePath = destination.resolve(archiveName).toString()

        val packDialog = PackDialog(project, selected, defaultArchivePath)
        if (!packDialog.showAndGet()) return

        var archivePath = Path.of(packDialog.archivePath)
        if (archivePath.parent == null) {
            archivePath = currentPath.resolve(archivePath)
        }
        val deleteAfterPacking = packDialog.deleteAfterPacking
        val sourcePaths = selected.map { it.path }

        val archiveExists = java.nio.file.Files.exists(archivePath)
        var appendToExisting = false

        if (archiveExists) {
            val result = com.intellij.openapi.ui.Messages.showDialog(
                project,
                "Archive already exists:\n${archivePath.fileName}\n\nWhat would you like to do?",
                "Archive Exists",
                arrayOf("Overwrite", "Add to Existing", "Cancel"),
                0,
                com.intellij.openapi.ui.Messages.getQuestionIcon(),
            )
            when (result) {
                0 -> {} // overwrite - will delete and recreate
                1 -> appendToExisting = true
                else -> return
            }
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Packing files", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Counting files..."

                runBlocking {
                    val totalFiles = fileOps.countFiles(sourcePaths)
                    indicator.isIndeterminate = false
                    var packedCount = 0

                    try {
                        val env = mutableMapOf<String, String>()
                        if (!appendToExisting && !archiveExists) {
                            env["create"] = "true"
                        }
                        if (!appendToExisting && archiveExists) {
                            withContext(Dispatchers.IO) {
                                java.nio.file.Files.delete(archivePath)
                            }
                            env["create"] = "true"
                        }

                        val uri = java.net.URI.create("jar:" + archivePath.toUri())
                        withContext(Dispatchers.IO) {
                            java.nio.file.FileSystems.newFileSystem(uri, env).use { zipFs ->
                                for (source in sourcePaths) {
                                    if (indicator.isCanceled) break
                                    if (source.toFile().isDirectory) {
                                        java.nio.file.Files.walkFileTree(source, object : java.nio.file.SimpleFileVisitor<Path>() {
                                            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                                                if (indicator.isCanceled) return java.nio.file.FileVisitResult.TERMINATE
                                                val relativePath = source.parent.relativize(dir).toString().replace("\\", "/")
                                                val zipDir = zipFs.getPath(relativePath)
                                                try {
                                                    java.nio.file.Files.createDirectories(zipDir)
                                                } catch (_: java.nio.file.FileAlreadyExistsException) {}
                                                packedCount++
                                                indicator.fraction = packedCount.toDouble() / totalFiles
                                                indicator.text = "Packing $packedCount / $totalFiles"
                                                indicator.text2 = dir.fileName.toString()
                                                return java.nio.file.FileVisitResult.CONTINUE
                                            }

                                            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                                                if (indicator.isCanceled) return java.nio.file.FileVisitResult.TERMINATE
                                                val relativePath = source.parent.relativize(file).toString().replace("\\", "/")
                                                val zipEntry = zipFs.getPath(relativePath)
                                                java.nio.file.Files.copy(file, zipEntry, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                                packedCount++
                                                indicator.fraction = packedCount.toDouble() / totalFiles
                                                indicator.text = "Packing $packedCount / $totalFiles"
                                                indicator.text2 = file.fileName.toString()
                                                return java.nio.file.FileVisitResult.CONTINUE
                                            }
                                        })
                                    } else {
                                        val zipEntry = zipFs.getPath(source.fileName.toString())
                                        java.nio.file.Files.copy(source, zipEntry, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                        packedCount++
                                        indicator.fraction = packedCount.toDouble() / totalFiles
                                        indicator.text = "Packing $packedCount / $totalFiles"
                                        indicator.text2 = source.fileName.toString()
                                    }
                                }
                            }
                        }

                        if (!indicator.isCanceled && deleteAfterPacking) {
                            indicator.isIndeterminate = false
                            indicator.fraction = 0.0
                            indicator.text = "Deleting source files..."
                            var deletedCount = 0

                            fileOps.deleteFilesWithProgress(
                                paths = sourcePaths,
                                onProgress = { count, name ->
                                    deletedCount = count
                                    indicator.fraction = count.toDouble() / totalFiles
                                    indicator.text = "Deleting $count / $totalFiles"
                                    indicator.text2 = name
                                },
                                onError = { path, error ->
                                    fileErrorNotification("Failed to delete ${path.fileName}: ${error.message}")
                                },
                                isCancelled = { indicator.isCanceled },
                            )
                        }
                    } catch (e: Exception) {
                        fileErrorNotification("Packing failed: ${e.message}")
                    }

                    val archiveFileName = archivePath.fileName.toString()
                    val archiveParent = archivePath.parent
                    if (archiveParent != null && archiveParent == currentPath) {
                        refreshAfterVfsChange(selectName = archiveFileName)
                        withContext(Dispatchers.EDT) {
                            onRefreshOtherPanel()
                        }
                    } else {
                        refreshAfterVfsChange()
                        val otherPath = otherPanelPathProvider()
                        if (archiveParent != null && otherPath != null && archiveParent == otherPath) {
                            withContext(Dispatchers.EDT) {
                                val svc = stateService ?: return@withContext
                                val activePanel = svc.getActivePanel()
                                val otherPanel = if (activePanel == svc.leftPanel) svc.rightPanel else svc.leftPanel
                                otherPanel?.refreshActiveTab(archiveFileName)
                            }
                        } else {
                            withContext(Dispatchers.EDT) {
                                onRefreshOtherPanel()
                            }
                        }
                    }
                }
            }
        })
    }

    fun performExtract() {
        val selected = getSelectedEntries().filter { isArchiveFile(it) }
        if (selected.isEmpty()) return
        val destination = otherPanelPathProvider() ?: currentPath

        val dialog = ExtractDialog(project, selected, destination.toString())
        if (!dialog.showAndGet()) return

        var destPath = Path.of(dialog.destinationPath)
        if (!destPath.isAbsolute) {
            destPath = currentPath.resolve(destPath)
        }
        val overwriteAll = dialog.overwriteExisting

        extractArchives(selected.map { it.path }, destPath, overwriteAll)
    }

    fun performExtractHere() {
        val selected = getSelectedEntries().filter { isArchiveFile(it) }
        if (selected.isEmpty()) return
        extractArchives(selected.map { it.path }, currentPath, overwriteAll = false)
    }

    private fun extractArchives(archivePaths: List<Path>, destination: Path, overwriteAll: Boolean) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Extracting files", true) {
            override fun run(indicator: ProgressIndicator) {
                runBlocking {
                    for (archivePath in archivePaths) {
                        if (indicator.isCanceled) break

                        indicator.isIndeterminate = true
                        indicator.text = "Counting entries in ${archivePath.fileName}..."

                        val totalEntries = fileOps.countArchiveEntries(archivePath)
                        indicator.isIndeterminate = false

                        fileOps.extractArchiveWithProgress(
                            archivePath = archivePath,
                            destination = destination,
                            overwriteAll = overwriteAll,
                            onProgress = { count, name ->
                                indicator.fraction = count.toDouble() / totalEntries
                                indicator.text = "Extracting $count / $totalEntries"
                                indicator.text2 = name
                            },
                            onOverwriteConfirm = { path ->
                                withContext(Dispatchers.EDT) {
                                    val result = Messages.showDialog(
                                        project,
                                        "File already exists:\n${path.fileName}\n\nOverwrite?",
                                        "File Exists",
                                        arrayOf("Yes", "No", "Yes to All", "No to All"),
                                        0,
                                        Messages.getQuestionIcon(),
                                    )
                                    when (result) {
                                        0 -> FileOperationService.OverwriteResponse.YES
                                        1 -> FileOperationService.OverwriteResponse.NO
                                        2 -> FileOperationService.OverwriteResponse.YES_TO_ALL
                                        3 -> FileOperationService.OverwriteResponse.NO_TO_ALL
                                        else -> FileOperationService.OverwriteResponse.NO
                                    }
                                }
                            },
                            onError = { path, error ->
                                fileErrorNotification("Failed to extract ${path.fileName}: ${error.message}")
                            },
                            isCancelled = { indicator.isCanceled },
                        )
                    }

                    refreshAfterVfsChange()
                    withContext(Dispatchers.EDT) {
                        onRefreshOtherPanel()
                    }
                }
            }
        })
    }

    private fun inactiveSelectionBackground(): java.awt.Color {
        val active = table.selectionBackground
        val bg = table.background
        return java.awt.Color(
            (active.red + bg.red) / 2,
            (active.green + bg.green) / 2,
            (active.blue + bg.blue) / 2,
        )
    }

    private fun inactiveSelectionForeground(): java.awt.Color {
        return table.foreground
    }

    private inner class FileNameCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
            if (isSelected && !table.hasFocus()) {
                background = inactiveSelectionBackground()
                foreground = inactiveSelectionForeground()
            }
            val modelRow = table.convertRowIndexToModel(row)
            val entry = tableModel.getEntryAt(modelRow)
            icon = when {
                entry == null -> null
                entry.isParentLink -> AllIcons.Nodes.UpLevel
                entry.isDirectory -> if (enableFileNameHighlighting) {
                    DirectoryIcons.getIcon(entry.directoryType)
                } else {
                    AllIcons.Nodes.Folder
                }
                else -> FileTypeManager.getInstance().getFileTypeByFileName(entry.name).icon
                    ?: AllIcons.FileTypes.Any_type
            }
            if (isSelected && !table.hasFocus()) {
                foreground = inactiveSelectionForeground()
            } else if (!isSelected) {
                background = table.background
                foreground = if (enableFileNameHighlighting && entry != null && entry.isDirectory && entry.directoryType != DirectoryType.NONE) {
                    DirectoryIcons.getColor(entry.directoryType)
                } else {
                    table.foreground
                }
            }
            return this
        }
    }

    private inner class DisplayValueRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val modelRow = table.convertRowIndexToModel(row)
            val modelCol = table.convertColumnIndexToModel(column)
            val displayValue = tableModel.getDisplayValue(modelRow, modelCol)
            val comp = super.getTableCellRendererComponent(table, displayValue, isSelected, false, row, column)
            if (isSelected && !table.hasFocus()) {
                background = inactiveSelectionBackground()
                foreground = inactiveSelectionForeground()
            } else if (!isSelected) {
                background = table.background
                foreground = table.foreground
            }
            return comp
        }
    }

    private class ParentPinningRowSorter(
        private val fileModel: FileTableModel,
    ) : javax.swing.table.TableRowSorter<FileTableModel>(fileModel) {
        init {
            for (col in 0 until fileModel.columnCount) {
                setComparator(col, ParentFirstComparator(this, col))
            }
        }

        override fun setSortKeys(keys: MutableList<out javax.swing.RowSorter.SortKey>?) {
            if (keys.isNullOrEmpty() || keys[0].column == FileTableModel.COL_NAME) {
                super.setSortKeys(keys)
                return
            }
            // Add name as secondary sort key in the same order
            val primary = keys[0]
            val combined = mutableListOf(primary, javax.swing.RowSorter.SortKey(FileTableModel.COL_NAME, primary.sortOrder))
            super.setSortKeys(combined)
        }
    }

    private class ParentFirstComparator(
        private val sorter: javax.swing.table.TableRowSorter<*>,
        private val column: Int,
    ) : Comparator<Any> {
        override fun compare(o1: Any?, o2: Any?): Int {
            val isParent1 = isParentMarker(o1)
            val isParent2 = isParentMarker(o2)

            if (isParent1 || isParent2) {
                if (isParent1 && isParent2) return 0
                val descending = sorter.sortKeys.firstOrNull()?.sortOrder == javax.swing.SortOrder.DESCENDING
                val parentFirst = if (isParent1) -1 else 1
                return if (descending) -parentFirst else parentFirst
            }

            if (column == FileTableModel.COL_SIZE || column == FileTableModel.COL_DATE) {
                val l1 = (o1 as? Long) ?: 0L
                val l2 = (o2 as? Long) ?: 0L
                return l1.compareTo(l2)
            }

            val s1 = o1?.toString() ?: ""
            val s2 = o2?.toString() ?: ""
            return s1.compareTo(s2, ignoreCase = true)
        }

        private fun isParentMarker(value: Any?): Boolean {
            if (value is String && value == FileTableModel.PARENT_MARKER) return true
            if (value is Long && value == FileTableModel.PARENT_NUMERIC) return true
            return false
        }
    }

    fun openInSystemExplorer(entry: FileEntry) {
        val path = if (entry.isDirectory) entry.path else entry.path.parent ?: return
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("win") -> arrayOf("explorer.exe", "/select,", entry.path.toString())
            os.contains("mac") -> arrayOf("open", "-R", entry.path.toString())
            else -> arrayOf("xdg-open", path.toString())
        }
        try {
            Runtime.getRuntime().exec(command)
        } catch (e: Exception) {
            fileErrorNotification("Failed to open in explorer: ${e.message}")
        }
    }

    private fun openFile(entry: FileEntry) {
        val vfs = currentVfs
        if (vfs != null) {
            val isTempDirVfs = vfs !is ZipVirtualFileSystem
            val useTempFileOpen = vfsStack.size > 1 || isTempDirVfs
            if (useTempFileOpen) {
                // Temp-dir-based VFS files are already on the real filesystem,
                // nested VFS files need extraction to temp
                fileOps.launch {
                    try {
                        val filePath = if (isTempDirVfs) {
                            entry.path
                        } else {
                            val tempDir = withContext(Dispatchers.IO) {
                                java.nio.file.Files.createTempDirectory("turtle-vfs-view-")
                            }
                            val tempPath = tempDir.resolve(entry.path.fileName.toString())
                            withContext(Dispatchers.IO) {
                                java.nio.file.Files.copy(entry.path, tempPath)
                            }
                            tempPath
                        }
                        val virtualFile = withContext(Dispatchers.IO) {
                            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath)
                        } ?: return@launch
                        withContext(Dispatchers.EDT) {
                            OpenFileDescriptor(project, virtualFile).navigate(true)
                        }
                    } catch (e: Exception) {
                        fileErrorNotification("Failed to open file: ${e.message}")
                    }
                }
                return
            }
            fileOps.launch {
                try {
                    val relativePath = entry.path.toString().replace("\\", "/").removePrefix("/")
                    val jarUrl = vfs.archivePath.toString() + "!/" + relativePath
                    val jarVfs = withContext(Dispatchers.IO) {
                        com.intellij.openapi.vfs.JarFileSystem.getInstance()
                    }
                    val virtualFile = withContext(Dispatchers.IO) {
                        jarVfs.findFileByPath(jarUrl)
                    } ?: return@launch
                    withContext(Dispatchers.EDT) {
                        OpenFileDescriptor(project, virtualFile).navigate(true)
                    }
                } catch (e: Exception) {
                    fileErrorNotification("Failed to open file: ${e.message}")
                }
            }
            return
        }
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(entry.path) ?: return
        OpenFileDescriptor(project, virtualFile).navigate(true)
    }

    private fun performRename(entry: FileEntry, newName: String) {
        fileOps.launch {
            try {
                fileOps.renameFile(entry.path, newName)
                refreshAfterVfsChange(selectName = newName)
            } catch (e: Exception) {
                fileErrorNotification("Rename failed: ${e.message}")
            }
        }
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

    private fun fileErrorNotification(content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Turtle Commander")
            .createNotification(content, NotificationType.ERROR)
            .notify(project)
    }

    private inner class FileEntryTransferHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent): Int = COPY

        override fun createTransferable(c: JComponent): Transferable? {
            val entries = getSelectedEntries()
            if (entries.isEmpty()) return null
            return FileEntryTransferable(entries)
        }

        override fun canImport(support: TransferSupport): Boolean {
            if (!support.isDrop) return false
            return support.isDataFlavorSupported(FILE_ENTRY_FLAVOR)
                || support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            try {
                val entries = when {
                    support.isDataFlavorSupported(FILE_ENTRY_FLAVOR) -> {
                        @Suppress("UNCHECKED_CAST")
                        support.transferable.getTransferData(FILE_ENTRY_FLAVOR) as List<FileEntry>
                    }
                    support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                        @Suppress("UNCHECKED_CAST")
                        val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File>
                        files.map { file ->
                            val path = file.toPath()
                            FileEntry(
                                name = file.name,
                                path = path,
                                isDirectory = file.isDirectory,
                                size = if (file.isFile) file.length() else 0,
                                lastModified = null,
                                permissions = "",
                            )
                        }
                    }
                    else -> return false
                }
                performCopyEntries(entries, currentPath)
                return true
            } catch (e: Exception) {
                thisLogger().warn("Drop failed: ${e.message}")
                return false
            }
        }
    }

    private inner class FileListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val entry = value as? FileEntry ?: return this
            text = entry.name
            icon = when {
                entry.isParentLink -> AllIcons.Nodes.UpLevel
                entry.isDirectory -> if (enableFileNameHighlighting) {
                    DirectoryIcons.getIcon(entry.directoryType)
                } else {
                    AllIcons.Nodes.Folder
                }
                else -> FileTypeManager.getInstance().getFileTypeByFileName(entry.name).icon
                    ?: AllIcons.FileTypes.Any_type
            }
            if (!isSelected && enableFileNameHighlighting && entry.isDirectory && entry.directoryType != DirectoryType.NONE) {
                foreground = DirectoryIcons.getColor(entry.directoryType)
            }
            return this
        }
    }

    private inner class FileTreeCellRenderer : DefaultTreeCellRenderer() {
        init {
            backgroundNonSelectionColor = table.background
        }

        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ): Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode ?: return this
            val entry = node.userObject as? FileEntry
            if (entry != null) {
                text = entry.name
                icon = when {
                    entry.isParentLink -> AllIcons.Nodes.UpLevel
                    entry.isDirectory -> if (enableFileNameHighlighting) {
                        DirectoryIcons.getIcon(entry.directoryType)
                    } else {
                        AllIcons.Nodes.Folder
                    }
                    else -> FileTypeManager.getInstance().getFileTypeByFileName(entry.name).icon
                        ?: AllIcons.FileTypes.Any_type
                }
                if (!sel && enableFileNameHighlighting && entry.isDirectory && entry.directoryType != DirectoryType.NONE) {
                    foreground = DirectoryIcons.getColor(entry.directoryType)
                }
            }
            return this
        }
    }

    companion object {
        val FILE_ENTRY_FLAVOR = DataFlavor(
            DataFlavor.javaJVMLocalObjectMimeType + ";class=java.util.List",
            "FileEntry List",
        )
        private const val VIEW_TABLE = "table"
        private const val VIEW_LIST = "list"
        private const val VIEW_TREE = "tree"
    }
}

fun isArchiveFile(entry: FileEntry): Boolean {
    if (entry.isDirectory) return false
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return ext in ZipFileSystemProvider.ARCHIVE_EXTENSIONS
}

enum class ViewMode { TABLE, LIST, TREE }

private class FileEntryTransferable(private val entries: List<FileEntry>) : Transferable {
    private val supportedFlavors = arrayOf(FileTab.FILE_ENTRY_FLAVOR, DataFlavor.javaFileListFlavor)

    override fun getTransferDataFlavors(): Array<DataFlavor> = supportedFlavors
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in supportedFlavors
    override fun getTransferData(flavor: DataFlavor): Any {
        return when (flavor) {
            FileTab.FILE_ENTRY_FLAVOR -> entries
            DataFlavor.javaFileListFlavor -> entries.map { it.path.toFile() }
            else -> throw UnsupportedFlavorException(flavor)
        }
    }
}
