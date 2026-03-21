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
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.TransferHandler
import javax.swing.event.CellEditorListener
import javax.swing.event.ChangeEvent
import javax.swing.table.DefaultTableCellRenderer

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

    private val driveCombo = ComboBox<String>()
    private val pathField = JTextField()
    private val statusLabel = JLabel(" ")
    private var updatingDriveCombo = false
    private val cursorPositions = mutableMapOf<Path, Int>()
    private var stateService: FileManagerStateService? = null
    private var initialized = false

    var currentPath: Path = initialPath
        private set

    init {
        setupHeader()
        setupTable()
        loadDrives()
        applyPanelFont()
        applyVisibilitySettings()
    }

    fun applyVisibilitySettings() {
        val settings = TurtleCommanderSettings.getInstance().state
        driveCombo.isVisible = !settings.hideDriveSelector
        statusLabel.isVisible = !settings.hideStatusBar
    }

    fun applyPanelFont() {
        val settings = TurtleCommanderSettings.getInstance()
        val font = settings.getPanelFont()
        val size = settings.getPanelFontSize()
        if (font != null) {
            table.font = font
            table.rowHeight = font.size + 6
        } else if (size > 0) {
            table.font = defaultTableFont.deriveFont(size.toFloat())
            table.rowHeight = size + 6
        } else {
            table.font = defaultTableFont
            table.rowHeight = 20
        }
    }

    private fun setupHeader() {
        val headerPanel = JPanel(BorderLayout())

        driveCombo.apply {
            preferredSize = Dimension(100, preferredSize.height)
            addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {}
                override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) {
                    if (updatingDriveCombo) return
                    val selected = selectedItem as? String ?: return
                    val drivePath = Path.of(selected)
                    if (drivePath != currentPath) {
                        val otherPath = otherPanelPathProvider()
                        val targetPath = if (otherPath != null && otherPath.root == drivePath.root) {
                            otherPath
                        } else {
                            drivePath
                        }
                        fileOps.launch {
                            navigateTo(targetPath)
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
            addActionListener {
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
                        val entry = getSelectedEntry() ?: return
                        if (entry.isDirectory || entry.isParentLink) {
                            fileOps.launch {
                                navigateTo(entry.path)
                            }
                        } else {
                            openFile(entry)
                        }
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

            selectionModel.addListSelectionListener { updateStatusBar() }
        }

        add(JBScrollPane(table), BorderLayout.CENTER)

        statusLabel.border = javax.swing.BorderFactory.createEmptyBorder(2, 4, 2, 4)
        add(statusLabel, BorderLayout.SOUTH)
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
    }

    private fun getSelectedEntry(): FileEntry? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        val modelRow = table.convertRowIndexToModel(viewRow)
        return tableModel.getEntryAt(modelRow)
    }

    private fun loadDrives() {
        val roots = fileOps.getRoots()
        driveCombo.removeAllItems()
        roots.forEach { driveCombo.addItem(it) }

        val currentRoot = currentPath.root?.toString() ?: roots.firstOrNull()
        if (currentRoot != null) {
            driveCombo.selectedItem = roots.find { it.equals(currentRoot, ignoreCase = true) } ?: roots.firstOrNull()
        }
    }

    suspend fun navigateTo(path: Path, selectName: String? = null) {
        val entries = fileOps.listFiles(path)
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
            pathField.text = path.toString()
            tableModel.setEntries(entries)
            updateStatusBar()
            onDirectoryChanged(this@FileTab)

            // Restore column state for this path
            if (initialized) {
                restoreColumnState(path)
            }

            val currentRoot = path.root?.toString() ?: ""
            updatingDriveCombo = true
            try {
                for (i in 0 until driveCombo.itemCount) {
                    if (driveCombo.getItemAt(i).equals(currentRoot, ignoreCase = true)) {
                        driveCombo.selectedIndex = i
                        break
                    }
                }
            } finally {
                updatingDriveCombo = false
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

            initialized = true
        }
    }

    fun refresh() {
        fileOps.launch { navigateTo(currentPath) }
    }

    fun showDriveSelector() {
        driveCombo.requestFocusInWindow()
        driveCombo.showPopup()
    }

    fun getSelectedEntries(): List<FileEntry> {
        return table.selectedRows.toList()
            .map { table.convertRowIndexToModel(it) }
            .mapNotNull { tableModel.getEntryAt(it) }
            .filter { !it.isParentLink }
    }

    fun openSelectedEntry() {
        val entry = getSelectedEntry() ?: return
        if (entry.isDirectory || entry.isParentLink) {
            fileOps.launch { navigateTo(entry.path) }
        } else {
            openFile(entry)
        }
    }

    fun goUp() {
        val parent = currentPath.parent
        if (parent != null) {
            fileOps.launch { navigateTo(parent) }
        }
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

                    navigateTo(currentPath)
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

                    navigateTo(currentPath)
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

                    navigateTo(currentPath)
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

                    navigateTo(currentPath)
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
            val highlighting = TurtleCommanderSettings.getInstance().state.enableFileNameHighlighting
            icon = when {
                entry == null -> null
                entry.isParentLink -> AllIcons.Nodes.UpLevel
                entry.isDirectory -> if (highlighting) {
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
                foreground = if (highlighting && entry != null && entry.isDirectory && entry.directoryType != DirectoryType.NONE) {
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
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(entry.path) ?: return
        OpenFileDescriptor(project, virtualFile).navigate(true)
    }

    private fun performRename(entry: FileEntry, newName: String) {
        fileOps.launch {
            try {
                fileOps.renameFile(entry.path, newName)
                navigateTo(currentPath)
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

    companion object {
        val FILE_ENTRY_FLAVOR = DataFlavor(
            DataFlavor.javaJVMLocalObjectMimeType + ";class=java.util.List",
            "FileEntry List",
        )
    }
}

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
