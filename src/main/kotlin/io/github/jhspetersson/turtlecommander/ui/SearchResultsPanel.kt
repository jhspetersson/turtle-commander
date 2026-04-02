package io.github.jhspetersson.turtlecommander.ui
import io.github.jhspetersson.turtlecommander.dialog.FileSearchDialog
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.service.FileSearchService
import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.model.FileEntry

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.openapi.fileTypes.FileTypeManager
import io.github.jhspetersson.turtlecommander.action.SearchContextMenuState
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableCellRenderer

class SearchResultsPanel(
    private val project: Project,
    private var criteria: FileSearchCriteria,
) : JPanel(BorderLayout()) {

    private val tableModel = FileTableModel()
    private val table = JBTable(tableModel)
    private val resultEntries = mutableListOf<FileEntry>()
    private val statusLabel = JLabel("Ready")

    private var searchService: FileSearchService? = null
    @Volatile
    private var currentIndicator: ProgressIndicator? = null
    @Volatile
    private var disposed = false

    private val editButton = JButton("Edit Search", AllIcons.Actions.Edit)
    private val pauseResumeButton = JButton("Pause", AllIcons.Actions.Pause)
    private val stopButton = JButton("Stop", AllIcons.Actions.Cancel)

    init {
        setupTable()
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(createControlPanel(), BorderLayout.SOUTH)
    }

    private fun setupTable() {
        table.apply {
            setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            setCellSelectionEnabled(false)
            setRowSelectionAllowed(true)
            autoResizeMode = JBTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            rowHeight = 20

            // Disable cell editing — search results are read-only
            setDefaultEditor(Any::class.java, null)

            rowSorter = javax.swing.table.TableRowSorter(tableModel).apply {
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
                        table.setRowSelectionInterval(row, row)
                        val modelRow = table.convertRowIndexToModel(row)
                        SearchContextMenuState.clickedEntry = tableModel.getEntryAt(modelRow)
                    } else {
                        SearchContextMenuState.clickedEntry = null
                    }
                    val am = ActionManager.getInstance()
                    val group = am.getAction("TurtleCommander.SearchContextMenu") as? ActionGroup ?: return
                    val popupMenu = am.createActionPopupMenu("TurtleCommander.SearchContextMenu", group)
                    popupMenu.component.show(table, e.x, e.y)
                }
            })

            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openSelected"
            )
            actionMap.put("openSelected", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    navigateToSelected()
                }
            })
        }
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

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Searching files...", true) {
            override fun run(indicator: ProgressIndicator) {
                currentIndicator = indicator
                indicator.isIndeterminate = true

                val pendingResults = mutableListOf<FileEntry>()
                var lastFlush = System.currentTimeMillis()

                service.search(
                    onResult = { entry ->
                        synchronized(pendingResults) {
                            pendingResults.add(entry)
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastFlush > 200 || pendingResults.size >= 50) {
                            flushResults(pendingResults)
                            lastFlush = now
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
                currentIndicator = null
            }
        })
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
                val snapshot = resultEntries.toList()
                val selectedRow = table.selectedRow
                tableModel.setEntries(snapshot)
                if (wasEmpty && table.rowCount > 0) {
                    table.setRowSelectionInterval(0, 0)
                    if (isShowing) {
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
        currentIndicator?.cancel()
        searchService?.paused = false
    }

    private fun editSearch() {
        val dialog = FileSearchDialog(project, criteria.rootPath, criteria)
        if (!dialog.showAndGet()) return
        criteria = dialog.getCriteria()
        startSearch()
    }

    fun dispose() {
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
