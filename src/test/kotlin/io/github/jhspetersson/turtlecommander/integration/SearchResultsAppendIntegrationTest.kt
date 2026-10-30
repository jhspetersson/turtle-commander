package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.dialog.NamePatternMode
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.ui.FileTableModel
import io.github.jhspetersson.turtlecommander.ui.SearchResultsPanel
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.event.TableModelEvent

class SearchResultsAppendIntegrationTest : BasePlatformTestCase() {

    private lateinit var panel: SearchResultsPanel
    private val root = Path.of("search-root").toAbsolutePath()

    override fun setUp() {
        super.setUp()
        panel = SearchResultsPanel(
            project,
            FileSearchCriteria(
                rootPath = root,
                namePattern = "*",
                namePatternMode = NamePatternMode.GLOB,
                sizeFilter = null,
                creationDateFilter = null,
                modificationDateFilter = null,
            ),
        )
    }

    override fun tearDown() {
        try {
            Disposer.dispose(panel)
        } finally {
            super.tearDown()
        }
    }

    private fun entries(vararg names: String): List<FileEntry> = names.map { name ->
        val stamp = FileTime.fromMillis(0)
        FileEntry(name, root.resolve(name), false, 1, stamp, stamp, permissions = "")
    }

    private fun selectedName(): String? =
        panel.table.selectedRow.takeIf { it >= 0 }
            ?.let { panel.tableModel.getEntryAt(panel.table.convertRowIndexToModel(it))?.name }

    fun testLaterBatchesAreInsertedNotReloaded() {
        panel.appendResults(entries("a", "b", "c"))
        val events = mutableListOf<TableModelEvent>()
        panel.tableModel.addTableModelListener { events.add(it) }

        panel.appendResults(entries("d", "e"))

        assertEquals(5, panel.tableModel.rowCount)
        assertEquals(1, events.size)
        assertEquals(TableModelEvent.INSERT, events[0].type)
        assertEquals(3, events[0].firstRow)
        assertEquals(4, events[0].lastRow)
    }

    fun testSelectionStaysOnSameEntryInSortedTable() {
        panel.table.rowSorter.sortKeys = listOf(RowSorter.SortKey(FileTableModel.COL_NAME, SortOrder.ASCENDING))
        panel.appendResults(entries("m", "p"))
        val pRow = (0 until panel.table.rowCount).first {
            panel.tableModel.getEntryAt(panel.table.convertRowIndexToModel(it))?.name == "p"
        }
        panel.table.setRowSelectionInterval(pRow, pRow)

        panel.appendResults(entries("a", "b"))

        assertEquals("p", selectedName())
        assertEquals(1, panel.table.selectedRowCount)
    }

    fun testFirstBatchSelectsFirstRow() {
        panel.appendResults(entries("x", "y"))

        assertEquals(2, panel.tableModel.rowCount)
        assertEquals(0, panel.table.selectedRow)
    }
}
