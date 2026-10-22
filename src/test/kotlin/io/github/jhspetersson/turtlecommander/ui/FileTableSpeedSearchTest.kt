package io.github.jhspetersson.turtlecommander.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.Cell
import com.intellij.ui.TableSpeedSearch
import io.github.jhspetersson.turtlecommander.model.FileEntry
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.swing.JTable
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.table.TableRowSorter

class FileTableSpeedSearchTest : BasePlatformTestCase() {

    @Suppress("DEPRECATION")
    private class ProbeSpeedSearch(table: JTable, model: FileTableModel) :
        TableSpeedSearch(table, fileTableSpeedSearchConvertor(table, model)) {
        fun rowFor(query: String): Int? = (findElement(query) as? Int)?.let { it / myComponent.columnCount }
    }

    private val modified = FileTime.fromMillis(1_761_100_000_000L)

    private lateinit var model: FileTableModel
    private lateinit var table: JTable
    private lateinit var search: ProbeSpeedSearch

    private fun entry(name: String, size: Long, owner: String = "", permissions: String = "") = FileEntry(
        name = name,
        path = Path.of("C:/tmp", name),
        isDirectory = false,
        size = size,
        creationTime = modified,
        lastModified = modified,
        owner = owner,
        permissions = permissions,
    )

    override fun setUp() {
        super.setUp()
        model = FileTableModel().apply {
            setEntries(listOf(entry("report.txt", 1234, owner = "alice", permissions = "rwxr-xr-x"), entry("notes.md", 56)))
        }
        table = JTable(model)
        search = ProbeSpeedSearch(table, model)
    }

    fun testADigitDoesNotMatchRowsThroughSizeOrDateCells() {
        assertNull(search.rowFor("1"))
        assertNull(search.rowFor("5"))
    }

    fun testAQueryMatchesTheFullFileNameIncludingTheExtension() {
        assertEquals(0, search.rowFor("txt"))
        assertEquals(1, search.rowFor("notes.md"))
    }

    fun testOwnerAndPermissionCellsDoNotMatch() {
        assertNull(search.rowFor("alice"))
        assertNull(search.rowFor("rwx"))
    }

    fun testTheConvertorReturnsTheEntryNameOnlyForTheNameColumn() {
        val convertor = fileTableSpeedSearchConvertor(table, model)
        assertEquals("report.txt", convertor.`fun`(table.getValueAt(0, FileTableModel.COL_NAME), Cell(0, FileTableModel.COL_NAME)))
        assertEquals("", convertor.`fun`(table.getValueAt(0, FileTableModel.COL_SIZE), Cell(0, FileTableModel.COL_SIZE)))
        assertEquals("", convertor.`fun`(table.getValueAt(0, FileTableModel.COL_DATE), Cell(0, FileTableModel.COL_DATE)))
    }

    fun testAReorderedNameColumnIsStillTheOneSearched() {
        table.moveColumn(FileTableModel.COL_NAME, 2)
        assertEquals(0, search.rowFor("txt"))
        assertNull(search.rowFor("1"))
    }

    fun testSortedRowsAreMatchedByTheirViewPosition() {
        val sorter = TableRowSorter(model)
        sorter.sortKeys = listOf(RowSorter.SortKey(FileTableModel.COL_SIZE, SortOrder.ASCENDING))
        table.rowSorter = sorter
        assertEquals(1, search.rowFor("txt"))
        assertEquals(0, search.rowFor("notes"))
    }
}
