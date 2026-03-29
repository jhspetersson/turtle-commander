package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.FileOperationService
import io.github.jhspetersson.turtlecommander.ui.FileTableModel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Integration tests for the FileTableModel: populating from real file listings,
 * column values, editable cells, directory size provider.
 */
class TableModelIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var model: FileTableModel

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("turtle-test-model-")
        model = FileTableModel()
    }

    override fun tearDown() {
        try {
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun fileEntry(
        name: String,
        size: Long = 0,
        isDirectory: Boolean = false,
        isParentLink: Boolean = false,
    ) = FileEntry(
        name = name,
        path = tempDir.resolve(name),
        isDirectory = isDirectory,
        size = size,
        lastModified = FileTime.fromMillis(1700000000000L),
        permissions = "rwxr-xr-x",
        isParentLink = isParentLink,
    )

    // --- Populate from real listing ---

    fun testPopulateFromRealDirectory() = runBlocking {
        Files.writeString(tempDir.resolve("file1.txt"), "hello")
        Files.createDirectory(tempDir.resolve("subdir"))

        val fileOps = project.service<FileOperationService>()
        val entries = fileOps.listFiles(tempDir)

        model.setEntries(entries)

        assertTrue("Model should have entries", model.rowCount > 0)
        val names = (0 until model.rowCount).mapNotNull { model.getEntryAt(it)?.name }
        assertTrue("Should contain file1.txt", "file1.txt" in names)
        assertTrue("Should contain subdir", "subdir" in names)
    }

    // --- Column values ---

    fun testNameColumnReturnsNameWithoutExtension() {
        model.setEntries(listOf(fileEntry("test.txt", size = 42)))

        // COL_NAME strips extension (extension goes to COL_EXT)
        val value = model.getValueAt(0, FileTableModel.COL_NAME)
        assertEquals("test", value)
    }

    fun testNameColumnReturnsFullNameForDirectory() {
        model.setEntries(listOf(fileEntry("mydir", isDirectory = true)))

        assertEquals("mydir", model.getValueAt(0, FileTableModel.COL_NAME))
    }

    fun testNameColumnReturnsFullNameForDotfile() {
        model.setEntries(listOf(fileEntry(".gitignore")))

        assertEquals(".gitignore", model.getValueAt(0, FileTableModel.COL_NAME))
    }

    fun testExtColumnReturnsExtension() {
        model.setEntries(listOf(fileEntry("test.txt"), fileEntry("archive.tar.gz")))

        assertEquals("txt", model.getValueAt(0, FileTableModel.COL_EXT))
        assertEquals("gz", model.getValueAt(1, FileTableModel.COL_EXT))
    }

    fun testExtColumnEmptyForDirectories() {
        model.setEntries(listOf(fileEntry("mydir", isDirectory = true)))

        assertEquals("", model.getValueAt(0, FileTableModel.COL_EXT))
    }

    fun testSizeColumnReturnsNumericValue() {
        model.setEntries(listOf(fileEntry("big.bin", size = 1024)))

        val sizeValue = model.getValueAt(0, FileTableModel.COL_SIZE)
        // Size column returns a Long for sorting purposes
        assertTrue("Size column should return a number", sizeValue is Long)
        assertEquals(1024L, sizeValue)
    }

    fun testSizeColumnDirectoryReturnsNegativeOne() {
        model.setEntries(listOf(fileEntry("dir", isDirectory = true)))

        val sizeValue = model.getValueAt(0, FileTableModel.COL_SIZE)
        assertEquals("Directory size should be -1", -1L, sizeValue)
    }

    fun testSizeColumnParentLinkReturnsMinValue() {
        model.setEntries(listOf(fileEntry("..", isDirectory = true, isParentLink = true)))

        val sizeValue = model.getValueAt(0, FileTableModel.COL_SIZE)
        assertEquals("Parent link size should be Long.MIN_VALUE", Long.MIN_VALUE, sizeValue)
    }

    // --- Display values ---

    fun testDisplayValueForSize() {
        model.setEntries(listOf(fileEntry("test.txt", size = 1024)))

        val display = model.getDisplayValue(0, FileTableModel.COL_SIZE)
        assertNotNull(display)
        // Should be formatted (e.g., "1 KB" or "1.0 KB")
        assertTrue("Display size should contain some representation", display.isNotEmpty())
    }

    fun testDisplayValueForDirectorySize() {
        model.setEntries(listOf(fileEntry("dir", isDirectory = true)))

        val display = model.getDisplayValue(0, FileTableModel.COL_SIZE)
        assertEquals("<DIR>", display)
    }

    fun testDisplayValueForParentLink() {
        model.setEntries(listOf(fileEntry("..", isDirectory = true, isParentLink = true)))

        // Parent link displays empty string for non-name columns
        val display = model.getDisplayValue(0, FileTableModel.COL_SIZE)
        assertEquals("", display)

        // But name column shows ".."
        val nameDisplay = model.getDisplayValue(0, FileTableModel.COL_NAME)
        assertEquals("..", nameDisplay)
    }

    // --- Cell editability ---

    fun testOnlyNameColumnIsEditable() {
        model.setEntries(listOf(fileEntry("test.txt")))

        assertTrue("Name column should be editable", model.isCellEditable(0, FileTableModel.COL_NAME))
        assertFalse("Ext column should not be editable", model.isCellEditable(0, FileTableModel.COL_EXT))
        assertFalse("Size column should not be editable", model.isCellEditable(0, FileTableModel.COL_SIZE))
    }

    fun testParentLinkNotEditable() {
        model.setEntries(listOf(fileEntry("..", isParentLink = true)))

        assertFalse("Parent link should not be editable", model.isCellEditable(0, FileTableModel.COL_NAME))
    }

    // --- Directory size provider ---

    fun testDirectorySizeProvider() {
        model.directorySizeProvider = { path ->
            if (path.fileName.toString() == "measured") 42L else null
        }
        model.setEntries(listOf(
            fileEntry("measured", isDirectory = true),
            fileEntry("unknown", isDirectory = true),
        ))

        val measuredSize = model.getValueAt(0, FileTableModel.COL_SIZE)
        val unknownSize = model.getValueAt(1, FileTableModel.COL_SIZE)

        assertEquals("Measured directory should return provided size", 42L, measuredSize)
        assertEquals("Unknown directory should return -1", -1L, unknownSize)
    }

    // --- Entry retrieval ---

    fun testGetEntryAtValidIndex() {
        val entry = fileEntry("test.txt")
        model.setEntries(listOf(entry))

        assertEquals(entry, model.getEntryAt(0))
    }

    fun testGetEntryAtInvalidIndex() {
        model.setEntries(listOf(fileEntry("test.txt")))

        assertNull(model.getEntryAt(-1))
        assertNull(model.getEntryAt(99))
    }

    // --- SetEntries clears previous data ---

    fun testSetEntriesReplacesPrevious() {
        model.setEntries(listOf(fileEntry("a.txt"), fileEntry("b.txt")))
        assertEquals(2, model.rowCount)

        model.setEntries(listOf(fileEntry("c.txt")))
        assertEquals(1, model.rowCount)
        assertEquals("c.txt", model.getEntryAt(0)?.name)
    }

    // --- Column class types ---

    fun testColumnClasses() {
        assertEquals(String::class.java, model.getColumnClass(FileTableModel.COL_NAME))
        assertEquals(String::class.java, model.getColumnClass(FileTableModel.COL_EXT))
        assertEquals(Long::class.javaObjectType, model.getColumnClass(FileTableModel.COL_SIZE))
    }

    // --- Permissions column ---

    fun testPermissionsColumnFromRealFile() = runBlocking {
        Files.writeString(tempDir.resolve("perms.txt"), "test")

        val fileOps = project.service<FileOperationService>()
        val entries = fileOps.listFiles(tempDir)
        model.setEntries(entries)

        val permsEntry = (0 until model.rowCount)
            .mapNotNull { model.getEntryAt(it) }
            .find { it.name == "perms.txt" }

        assertNotNull(permsEntry)
        assertTrue("Permissions should not be blank", permsEntry!!.permissions.isNotBlank())
    }
}
