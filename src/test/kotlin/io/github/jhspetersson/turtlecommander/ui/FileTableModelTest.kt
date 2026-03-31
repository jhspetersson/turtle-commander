package io.github.jhspetersson.turtlecommander.ui
import io.github.jhspetersson.turtlecommander.model.FileEntry

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class FileTableModelTest {

    private lateinit var model: FileTableModel
    private val now = FileTime.fromMillis(1700000000000L)

    @Before
    fun setUp() {
        model = FileTableModel()
    }

    private fun fileEntry(
        name: String,
        size: Long = 0,
        isDirectory: Boolean = false,
        isParentLink: Boolean = false,
        permissions: String = "rwxr-xr-x",
        lastModified: FileTime? = now,
    ) = FileEntry(
        name = name,
        path = Path.of("/test/$name"),
        isDirectory = isDirectory,
        size = size,
        lastModified = lastModified,
        permissions = permissions,
        isParentLink = isParentLink,
    )

    @Test
    fun testEmptyModel() {
        assertEquals(0, model.rowCount)
        assertEquals(6, model.columnCount)
    }

    @Test
    fun testColumnNames() {
        assertEquals("Name", model.getColumnName(0))
        assertEquals("Ext", model.getColumnName(1))
        assertEquals("Size", model.getColumnName(2))
        assertEquals("Date Created", model.getColumnName(3))
        assertEquals("Date Modified", model.getColumnName(4))
        assertEquals("Permissions", model.getColumnName(5))
    }

    @Test
    fun testColumnClasses() {
        assertEquals(Long::class.javaObjectType, model.getColumnClass(FileTableModel.COL_SIZE))
        assertEquals(Long::class.javaObjectType, model.getColumnClass(FileTableModel.COL_CREATED))
        assertEquals(Long::class.javaObjectType, model.getColumnClass(FileTableModel.COL_DATE))
        assertEquals(String::class.java, model.getColumnClass(FileTableModel.COL_NAME))
        assertEquals(String::class.java, model.getColumnClass(FileTableModel.COL_EXT))
        assertEquals(String::class.java, model.getColumnClass(FileTableModel.COL_PERMS))
    }

    @Test
    fun testFileNameSplitting() {
        model.setEntries(listOf(fileEntry("document.txt", size = 100)))
        assertEquals("document", model.getValueAt(0, FileTableModel.COL_NAME))
        assertEquals("txt", model.getValueAt(0, FileTableModel.COL_EXT))
    }

    @Test
    fun testFileNoExtension() {
        model.setEntries(listOf(fileEntry("Makefile", size = 50)))
        assertEquals("Makefile", model.getValueAt(0, FileTableModel.COL_NAME))
        assertEquals("", model.getValueAt(0, FileTableModel.COL_EXT))
    }

    @Test
    fun testDotFile() {
        model.setEntries(listOf(fileEntry(".gitignore", size = 30)))
        assertEquals(".gitignore", model.getValueAt(0, FileTableModel.COL_NAME))
        assertEquals("", model.getValueAt(0, FileTableModel.COL_EXT))
    }

    @Test
    fun testMultipleDotsInFileName() {
        model.setEntries(listOf(fileEntry("archive.tar.gz", size = 1000)))
        assertEquals("archive.tar", model.getValueAt(0, FileTableModel.COL_NAME))
        assertEquals("gz", model.getValueAt(0, FileTableModel.COL_EXT))
    }

    @Test
    fun testDirectoryName() {
        model.setEntries(listOf(fileEntry("src", isDirectory = true)))
        assertEquals("src", model.getValueAt(0, FileTableModel.COL_NAME))
        assertEquals("", model.getValueAt(0, FileTableModel.COL_EXT))
    }

    @Test
    fun testDirectoryWithDotInName() {
        model.setEntries(listOf(fileEntry("my.folder", isDirectory = true)))
        assertEquals("my.folder", model.getValueAt(0, FileTableModel.COL_NAME))
        assertEquals("", model.getValueAt(0, FileTableModel.COL_EXT))
    }

    @Test
    fun testParentLink() {
        model.setEntries(listOf(fileEntry("..", isDirectory = true, isParentLink = true)))
        assertEquals("..", model.getValueAt(0, FileTableModel.COL_NAME))
        assertEquals(FileTableModel.PARENT_MARKER, model.getValueAt(0, FileTableModel.COL_EXT))
        assertEquals(FileTableModel.PARENT_NUMERIC, model.getValueAt(0, FileTableModel.COL_SIZE))
        assertEquals(FileTableModel.PARENT_NUMERIC, model.getValueAt(0, FileTableModel.COL_DATE))
        assertEquals(FileTableModel.PARENT_MARKER, model.getValueAt(0, FileTableModel.COL_PERMS))
    }

    @Test
    fun testFileSizeValue() {
        model.setEntries(listOf(fileEntry("file.txt", size = 4096)))
        assertEquals(4096L, model.getValueAt(0, FileTableModel.COL_SIZE))
    }

    @Test
    fun testDirectorySizeValue() {
        model.setEntries(listOf(fileEntry("dir", isDirectory = true)))
        assertEquals(-1L, model.getValueAt(0, FileTableModel.COL_SIZE))
    }

    @Test
    fun testDateValue() {
        model.setEntries(listOf(fileEntry("file.txt", size = 100)))
        assertEquals(now.toMillis(), model.getValueAt(0, FileTableModel.COL_DATE))
    }

    @Test
    fun testNullDate() {
        model.setEntries(listOf(fileEntry("file.txt", size = 100, lastModified = null)))
        assertEquals(0L, model.getValueAt(0, FileTableModel.COL_DATE))
    }

    @Test
    fun testPermissions() {
        model.setEntries(listOf(fileEntry("file.txt", size = 100, permissions = "rw-r--r--")))
        assertEquals("rw-r--r--", model.getValueAt(0, FileTableModel.COL_PERMS))
    }

    // --- Display values ---

    @Test
    fun testDisplaySizeBytes() {
        model.setEntries(listOf(fileEntry("file.txt", size = 512)))
        assertEquals("512 B", model.getDisplayValue(0, FileTableModel.COL_SIZE))
    }

    @Test
    fun testDisplaySizeKB() {
        model.setEntries(listOf(fileEntry("file.txt", size = 2048)))
        assertEquals("2.0 KB", model.getDisplayValue(0, FileTableModel.COL_SIZE))
    }

    @Test
    fun testDisplaySizeMB() {
        model.setEntries(listOf(fileEntry("file.txt", size = 5 * 1024 * 1024)))
        assertEquals("5.0 MB", model.getDisplayValue(0, FileTableModel.COL_SIZE))
    }

    @Test
    fun testDisplaySizeGB() {
        model.setEntries(listOf(fileEntry("file.txt", size = 2L * 1024 * 1024 * 1024)))
        assertEquals("2.0 GB", model.getDisplayValue(0, FileTableModel.COL_SIZE))
    }

    @Test
    fun testDisplaySizeDirectory() {
        model.setEntries(listOf(fileEntry("dir", isDirectory = true)))
        assertEquals("<DIR>", model.getDisplayValue(0, FileTableModel.COL_SIZE))
    }

    @Test
    fun testDisplayParentLink() {
        model.setEntries(listOf(fileEntry("..", isDirectory = true, isParentLink = true)))
        assertEquals("..", model.getDisplayValue(0, FileTableModel.COL_NAME))
        assertEquals("", model.getDisplayValue(0, FileTableModel.COL_SIZE))
        assertEquals("", model.getDisplayValue(0, FileTableModel.COL_DATE))
        assertEquals("", model.getDisplayValue(0, FileTableModel.COL_PERMS))
    }

    @Test
    fun testDisplayDate() {
        model.setEntries(listOf(fileEntry("file.txt", size = 100)))
        val display = model.getDisplayValue(0, FileTableModel.COL_DATE)
        assertTrue("Date should match yyyy-MM-dd HH:mm format", display.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }

    @Test
    fun testDisplayNullDate() {
        model.setEntries(listOf(fileEntry("file.txt", size = 100, lastModified = null)))
        assertEquals("", model.getDisplayValue(0, FileTableModel.COL_DATE))
    }

    // --- Cell editability ---

    @Test
    fun testCellEditableNameColumn() {
        model.setEntries(listOf(fileEntry("file.txt", size = 100)))
        assertTrue(model.isCellEditable(0, FileTableModel.COL_NAME))
    }

    @Test
    fun testCellNotEditableOtherColumns() {
        model.setEntries(listOf(fileEntry("file.txt", size = 100)))
        assertFalse(model.isCellEditable(0, FileTableModel.COL_EXT))
        assertFalse(model.isCellEditable(0, FileTableModel.COL_SIZE))
        assertFalse(model.isCellEditable(0, FileTableModel.COL_DATE))
        assertFalse(model.isCellEditable(0, FileTableModel.COL_PERMS))
    }

    @Test
    fun testParentNotEditable() {
        model.setEntries(listOf(fileEntry("..", isDirectory = true, isParentLink = true)))
        assertFalse(model.isCellEditable(0, FileTableModel.COL_NAME))
    }

    // --- getEntryAt ---

    @Test
    fun testGetEntryAt() {
        val entry = fileEntry("file.txt", size = 100)
        model.setEntries(listOf(entry))
        assertEquals(entry, model.getEntryAt(0))
    }

    @Test
    fun testGetEntryAtOutOfBounds() {
        model.setEntries(listOf(fileEntry("file.txt", size = 100)))
        assertNull(model.getEntryAt(5))
    }

    @Test
    fun testGetValueAtOutOfBoundsReturnsEmpty() {
        model.setEntries(listOf(fileEntry("file.txt", size = 100)))
        assertEquals("", model.getValueAt(5, FileTableModel.COL_NAME))
        assertEquals("", model.getValueAt(-1, FileTableModel.COL_NAME))
    }

    @Test
    fun testGetDisplayValueOutOfBoundsReturnsEmpty() {
        model.setEntries(listOf(fileEntry("file.txt", size = 100)))
        assertEquals("", model.getDisplayValue(5, FileTableModel.COL_NAME))
        assertEquals("", model.getDisplayValue(-1, FileTableModel.COL_SIZE))
    }

    @Test
    fun testGetValueAtEmptyModelReturnsEmpty() {
        assertEquals("", model.getValueAt(0, FileTableModel.COL_NAME))
    }

    @Test
    fun testGetDisplayValueEmptyModelReturnsEmpty() {
        assertEquals("", model.getDisplayValue(0, FileTableModel.COL_NAME))
    }

    @Test
    fun testRowCount() {
        model.setEntries(listOf(
            fileEntry("a.txt", size = 10),
            fileEntry("b.txt", size = 20),
            fileEntry("c.txt", size = 30),
        ))
        assertEquals(3, model.rowCount)
    }
}
