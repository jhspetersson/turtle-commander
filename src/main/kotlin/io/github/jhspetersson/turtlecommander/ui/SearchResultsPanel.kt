package io.github.jhspetersson.turtlecommander.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import io.github.jhspetersson.turtlecommander.action.SearchContextMenuState
import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.dialog.FileSearchDialog
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.service.FileOperationService
import io.github.jhspetersson.turtlecommander.service.FileSearchService
import io.github.jhspetersson.turtlecommander.util.withIndicatorProgress
import io.github.jhspetersson.turtlecommander.util.FileNameGlobMatcher
import io.github.jhspetersson.turtlecommander.util.wrapAsSubstringGlobIfPlain
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.*
import java.nio.file.Path
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class SearchResultsPanel(
    private val project: Project,
    private var criteria: FileSearchCriteria,
) : JPanel(BorderLayout()), Disposable {

    private val tableModel = FileTableModel()
    private val table = JBTable(tableModel)
    private val resultEntries = mutableListOf<FileEntry>()
    private val statusLabel = JLabel("Ready")

    private var searchService: FileSearchService? = null
    @Volatile
    private var currentSearchJob: Job? = null
    @Volatile
    private var disposed = false

    private val editButton = JButton("Edit Search", AllIcons.Actions.Edit)
    private val pauseResumeButton = JButton("Pause", AllIcons.Actions.Pause)
    private val stopButton = JButton("Stop", AllIcons.Actions.Cancel)

    private val quickFilterBar = QuickFilterBar(
        onFilterChanged = { applyFilter() },
        onHideRequested = { hideQuickFilter() },
        onMoveSelection = { offset -> moveTableSelection(offset) },
        onCommit = { table.requestFocusInWindow() },
    )
    private var cachedFilterGlob: String? = null
    private var cachedFilterMatcher: FileNameGlobMatcher? = null

    init {
        setupTable()
        add(JBScrollPane(table), BorderLayout.CENTER)
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(quickFilterBar, BorderLayout.NORTH)
        bottomPanel.add(createControlPanel(), BorderLayout.SOUTH)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun setupTable() {
        table.apply {
            setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            setCellSelectionEnabled(false)
            setRowSelectionAllowed(true)
            autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            rowHeight = 20

            // Disable cell editing — search results are read-only
            setDefaultEditor(Any::class.java, null)

            rowSorter = TableRowSorter(tableModel).apply {
                setComparator(FileTableModel.COL_SIZE) { o1: Any?, o2: Any? ->
                    ((o1 as? Long) ?: 0L).compareTo((o2 as? Long) ?: 0L)
                }
                setComparator(FileTableModel.COL_CREATED) { o1: Any?, o2: Any? ->
                    ((o1 as? Long) ?: 0L).compareTo((o2 as? Long) ?: 0L)
                }
                setComparator(FileTableModel.COL_DATE) { o1: Any?, o2: Any? ->
                    ((o1 as? Long) ?: 0L).compareTo((o2 as? Long) ?: 0L)
                }
            }

            columnModel.getColumn(0).cellRenderer = SearchFileNameRenderer()
            columnModel.getColumn(0).preferredWidth = 300
            columnModel.getColumn(1).preferredWidth = 50
            columnModel.getColumn(1).cellRenderer = SearchDisplayValueRenderer()
            columnModel.getColumn(2).preferredWidth = 80
            columnModel.getColumn(2).cellRenderer = SearchDisplayValueRenderer()
            columnModel.getColumn(3).preferredWidth = 130
            columnModel.getColumn(3).cellRenderer = SearchDisplayValueRenderer()
            columnModel.getColumn(4).preferredWidth = 130
            columnModel.getColumn(4).cellRenderer = SearchDisplayValueRenderer()
            columnModel.getColumn(5).preferredWidth = 80
            columnModel.getColumn(5).cellRenderer = SearchDisplayValueRenderer()

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        navigateToSelected()
                    }
                }

                override fun mousePressed(e: MouseEvent) { handleContextMenu(e) }
                override fun mouseReleased(e: MouseEvent) { handleContextMenu(e) }

                private fun handleContextMenu(e: MouseEvent) {
                    if (!e.isPopupTrigger) return
                    val row = table.rowAtPoint(e.point)
                    if (row >= 0) {
                        // Keep an existing multi-row selection when the right-click
                        // lands inside it; only replace the selection when the user
                        // right-clicked an unselected row.
                        if (!table.isRowSelected(row)) {
                            table.setRowSelectionInterval(row, row)
                        }
                        val modelRow = table.convertRowIndexToModel(row)
                        SearchContextMenuState.clickedEntry = tableModel.getEntryAt(modelRow)
                    } else {
                        SearchContextMenuState.clickedEntry = null
                    }
                    SearchContextMenuState.selectedEntries = table.selectedRows
                        .map { table.convertRowIndexToModel(it) }
                        .mapNotNull { tableModel.getEntryAt(it) }
                    val am = ActionManager.getInstance()
                    val group = am.getAction("TurtleCommander.SearchContextMenu") as? ActionGroup ?: return
                    val popupMenu = am.createActionPopupMenu("TurtleCommander.SearchContextMenu", group)
                    popupMenu.component.clearStateOnClose { SearchContextMenuState.clear() }
                    popupMenu.component.show(table, e.x, e.y)
                }
            })

            TableSpeedSearch.installOn(this) { value ->
                (value as? FileEntry)?.name ?: value?.toString().orEmpty()
            }

            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openSelected"
            )
            actionMap.put("openSelected", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    navigateToSelected()
                }
            })
        }

        // Register Ctrl-S as a component-local action on the table to override
        // IntelliJ's global "Save All" binding, same as the FileTab quick filter.
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
    }

    private fun navigateToSelected() {
        val row = table.selectedRow
        if (row < 0) return
        val modelRow = table.convertRowIndexToModel(row)
        val entry = tableModel.getEntryAt(modelRow) ?: return
        val targetDir = entry.path.parent ?: return

        val stateService = project.service<FileManagerStateService>()
        val activePanel = stateService.getActivePanel()
        val otherPanel = if (activePanel === stateService.leftPanel) {
            stateService.rightPanel
        } else {
            stateService.leftPanel
        } ?: return

        otherPanel.openDirectoryInNewTab(targetDir, selectName = entry.name)
    }

    private fun moveTableSelection(offset: Int) {
        val next = (table.selectedRow + offset).coerceIn(0, table.rowCount - 1)
        if (next >= 0) {
            table.setRowSelectionInterval(next, next)
            table.scrollRectToVisible(table.getCellRect(next, 0, true))
        }
    }

    fun showQuickFilter() {
        quickFilterBar.activate()
        revalidate()
    }

    private fun hideQuickFilter() {
        quickFilterBar.deactivate()
        // deactivate() fires no document event when the field was already empty, so
        // re-apply explicitly to guarantee the unfiltered results are restored.
        applyFilter()
        revalidate()
        table.requestFocusInWindow()
    }

    private fun applyFilter() {
        val pattern = quickFilterBar.text.trim()
        if (pattern.isEmpty()) {
            cachedFilterGlob = null
            cachedFilterMatcher = null
            quickFilterBar.setError(false)
        } else {
            val glob = wrapAsSubstringGlobIfPlain(pattern)
            if (glob != cachedFilterGlob || cachedFilterMatcher == null) {
                val m = try {
                    FileNameGlobMatcher(glob)
                } catch (_: Exception) {
                    // Invalid glob: flag the field so the user knows something is wrong,
                    // drop the stale matcher cache, and fall back to showing everything
                    // instead of freezing the view on the previous filter's output.
                    null
                }
                if (m != null) {
                    cachedFilterGlob = glob
                    cachedFilterMatcher = m
                    quickFilterBar.setError(false)
                } else {
                    cachedFilterGlob = null
                    cachedFilterMatcher = null
                    quickFilterBar.setError(true)
                }
            } else {
                quickFilterBar.setError(false)
            }
        }

        val selectedPath = table.selectedRow
            .takeIf { it >= 0 }
            ?.let { tableModel.getEntryAt(table.convertRowIndexToModel(it)) }
            ?.path

        val filtered = filteredEntries()
        tableModel.setEntries(filtered)

        if (selectedPath != null) {
            val modelIdx = filtered.indexOfFirst { it.path == selectedPath }
            if (modelIdx >= 0) {
                val viewIdx = table.convertRowIndexToView(modelIdx)
                if (viewIdx >= 0) {
                    table.setRowSelectionInterval(viewIdx, viewIdx)
                    table.scrollRectToVisible(table.getCellRect(viewIdx, 0, true))
                }
            }
        }
        if (table.selectedRow < 0 && table.rowCount > 0) {
            table.setRowSelectionInterval(0, 0)
        }
    }

    private fun filteredEntries(): List<FileEntry> {
        val matcher = cachedFilterMatcher ?: return resultEntries.toList()
        return resultEntries.filter { matcher.matches(it.name) }
    }

    private fun createControlPanel(): JPanel {
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))

        editButton.addActionListener { editSearch() }
        pauseResumeButton.addActionListener { togglePause() }
        stopButton.addActionListener { stopSearch() }

        stopButton.isEnabled = false
        pauseResumeButton.isEnabled = false

        buttonPanel.add(editButton)
        buttonPanel.add(pauseResumeButton)
        buttonPanel.add(stopButton)

        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(buttonPanel, BorderLayout.WEST)
        statusLabel.border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        bottomPanel.add(statusLabel, BorderLayout.CENTER)
        return bottomPanel
    }

    fun startSearch() {
        if (disposed) return
        resultEntries.clear()
        // Hide the quick filter on a new/edited search — stale criteria would
        // silently hide fresh results
        if (quickFilterBar.isVisible) {
            quickFilterBar.deactivate()
            revalidate()
        }
        SwingUtilities.invokeLater {
            tableModel.setEntries(emptyList())
        }

        val service = FileSearchService(criteria)
        searchService = service

        stopButton.isEnabled = true
        pauseResumeButton.isEnabled = true
        pauseResumeButton.text = "Pause"
        pauseResumeButton.icon = AllIcons.Actions.Pause
        editButton.isEnabled = false
        statusLabel.text = "Searching..."

        val fileOps = project.service<FileOperationService>()
        currentSearchJob = fileOps.launch {
            withIndicatorProgress(project, "Searching files...") { indicator ->
                // Stop requests (the Stop button cancels currentSearchJob, the progress
                // bar's cancel cancels the indicator) are observed through the polled
                // isCancelled lambda so the final flush and status update still run;
                // NonCancellable keeps the CancellationException at the next suspension
                // point from skipping them.
                withContext(NonCancellable) {
                    val pendingResults = mutableListOf<FileEntry>()
                    var lastFlush = System.currentTimeMillis()

                    try {
                        service.search(
                            onResult = { entry ->
                                val shouldFlush: Boolean
                                synchronized(pendingResults) {
                                    pendingResults.add(entry)
                                    val now = System.currentTimeMillis()
                                    shouldFlush = now - lastFlush > 200 || pendingResults.size >= 50
                                    if (shouldFlush) lastFlush = now
                                }
                                if (shouldFlush) {
                                    flushResults(pendingResults)
                                }
                            },
                            isCancelled = { indicator.isCanceled || disposed },
                            onProgress = { scannedCount, currentDir ->
                                indicator.text = "Scanned $scannedCount entries..."
                                indicator.text2 = currentDir
                                if (!disposed) {
                                    SwingUtilities.invokeLater {
                                        statusLabel.text = "Searching... ${resultEntries.size} files found, scanned $scannedCount entries"
                                    }
                                }
                            },
                        )
                    } catch (e: FileSearchService.InvalidPatternException) {
                        if (!disposed) {
                            SwingUtilities.invokeLater {
                                statusLabel.text = e.message ?: "Invalid search pattern"
                                stopButton.isEnabled = false
                                pauseResumeButton.isEnabled = false
                                editButton.isEnabled = true
                            }
                        }
                        return@withContext
                    }

                    // Flush remaining
                    flushResults(pendingResults)

                    if (!disposed) {
                        SwingUtilities.invokeLater {
                            val msg = if (indicator.isCanceled) "Search stopped." else "Search complete."
                            statusLabel.text = "$msg ${resultEntries.size} files found."
                            stopButton.isEnabled = false
                            pauseResumeButton.isEnabled = false
                            editButton.isEnabled = true
                        }
                    }
                }
            }
        }
    }

    private fun flushResults(pendingResults: MutableList<FileEntry>) {
        val batch: List<FileEntry>
        synchronized(pendingResults) {
            if (pendingResults.isEmpty()) return
            batch = pendingResults.toList()
            pendingResults.clear()
        }
        if (!disposed) {
            SwingUtilities.invokeLater {
                val wasEmpty = resultEntries.isEmpty()
                resultEntries.addAll(batch)
                val selectedRow = table.selectedRow
                tableModel.setEntries(filteredEntries())
                if (wasEmpty && table.rowCount > 0) {
                    table.setRowSelectionInterval(0, 0)
                    // Don't steal focus while the user is typing in the quick filter
                    if (isShowing && !quickFilterBar.hasFieldFocus()) {
                        table.requestFocusInWindow()
                    }
                } else if (selectedRow in 0 until table.rowCount) {
                    table.setRowSelectionInterval(selectedRow, selectedRow)
                }
            }
        }
    }

    private fun togglePause() {
        val service = searchService ?: return
        if (service.paused) {
            service.paused = false
            pauseResumeButton.text = "Pause"
            pauseResumeButton.icon = AllIcons.Actions.Pause
            statusLabel.text = "Searching... ${resultEntries.size} files found"
        } else {
            service.paused = true
            pauseResumeButton.text = "Resume"
            pauseResumeButton.icon = AllIcons.RunConfigurations.TestState.Run
            statusLabel.text = "Search paused. ${resultEntries.size} files found."
        }
    }

    private fun stopSearch() {
        currentSearchJob?.cancel()
        searchService?.paused = false
    }

    private fun editSearch() {
        val dialog = FileSearchDialog(project, criteria.rootPath, criteria)
        if (!dialog.showAndGet()) return
        criteria = dialog.getCriteria()
        startSearch()
    }

    override fun dispose() {
        disposed = true
        stopSearch()
    }

    fun requestTableFocus() {
        table.requestFocusInWindow()
    }

    fun hasTableFocus(): Boolean {
        return table.hasFocus()
    }

    private inner class SearchFileNameRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
            val modelRow = table.convertRowIndexToModel(row)
            val entry = tableModel.getEntryAt(modelRow)
            icon = when {
                entry == null -> null
                entry.isDirectory -> AllIcons.Nodes.Folder
                else -> FileTypeManager.getInstance().getFileTypeByFileName(entry.name).icon
                    ?: AllIcons.FileTypes.Any_type
            }
            toolTipText = entry?.path?.toString()
            text = highlightSpeedSearchMatches(table, value?.toString().orEmpty())
            return this
        }
    }

    private inner class SearchDisplayValueRenderer : DefaultTableCellRenderer() {
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
            return super.getTableCellRendererComponent(table, displayValue, isSelected, false, row, column)
        }
    }
}
