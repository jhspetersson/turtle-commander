package io.github.jhspetersson.turtlecommander.ui
import io.github.jhspetersson.turtlecommander.model.FileEntry

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.swing.JTable
import javax.swing.RowSorter
import javax.swing.SortOrder

class ParentPinningRowSorterTest {

    private lateinit var model: FileTableModel
    private lateinit var table: JTable
    private lateinit var sorter: ParentPinningRowSorter
    private var sortWithDirectories = false
    private var naturalNameSort = true

    private val t1 = FileTime.fromMillis(1000000000000L)
    private val t2 = FileTime.fromMillis(2000000000000L)
    private val t3 = FileTime.fromMillis(3000000000000L)
    private val t4 = FileTime.fromMillis(4000000000000L)
    private val t5 = FileTime.fromMillis(5000000000000L)

    @Before
    fun setUp() {
        model = FileTableModel()
        sorter = ParentPinningRowSorter(model, { sortWithDirectories }, { naturalNameSort })
        table = JTable(model)
        table.rowSorter = sorter
    }

    private fun entry(
        name: String,
        size: Long = 0,
        isDirectory: Boolean = false,
        isParentLink: Boolean = false,
        lastModified: FileTime? = t1,
    ) = FileEntry(
        name = name,
        path = Path.of("/test/$name"),
        isDirectory = isDirectory,
        size = size,
        lastModified = lastModified,
        permissions = "rwxr-xr-x",
        isParentLink = isParentLink,
    )

    /** Returns the visible list of names in the current view order. */
    private fun visibleNames(): List<String> {
        return (0 until table.rowCount).map { viewRow ->
            val modelRow = table.convertRowIndexToModel(viewRow)
            model.getEntryAt(modelRow)!!.name
        }
    }

    private fun sortBy(column: Int, order: SortOrder) {
        sorter.sortKeys = mutableListOf(RowSorter.SortKey(column, order))
    }

    // --- Parent always first ---

    @Test
    fun parentAlwaysFirstWhenSortByNameAsc() {
        model.setEntries(listOf(
            entry("..", isDirectory = true, isParentLink = true),
            entry("zebra.txt", size = 100),
            entry("alpha.txt", size = 200),
        ))
        sortBy(FileTableModel.COL_NAME, SortOrder.ASCENDING)
        assertEquals(listOf("..", "alpha.txt", "zebra.txt"), visibleNames())
    }

    @Test
    fun parentAlwaysFirstWhenSortByNameDesc() {
        model.setEntries(listOf(
            entry("..", isDirectory = true, isParentLink = true),
            entry("zebra.txt", size = 100),
            entry("alpha.txt", size = 200),
        ))
        sortBy(FileTableModel.COL_NAME, SortOrder.DESCENDING)
        assertEquals("..", visibleNames().first())
        assertEquals("zebra.txt", visibleNames()[1])
        assertEquals("alpha.txt", visibleNames()[2])
    }

    @Test
    fun parentAlwaysFirstWhenSortBySizeDesc() {
        model.setEntries(listOf(
            entry("..", isDirectory = true, isParentLink = true),
            entry("small.txt", size = 10),
            entry("big.txt", size = 9999),
        ))
        sortBy(FileTableModel.COL_SIZE, SortOrder.DESCENDING)
        assertEquals("..", visibleNames().first())
    }

    @Test
    fun parentAlwaysFirstWhenSortByDateDesc() {
        model.setEntries(listOf(
            entry("..", isDirectory = true, isParentLink = true, lastModified = t1),
            entry("old.txt", size = 10, lastModified = t2),
            entry("new.txt", size = 20, lastModified = t5),
        ))
        sortBy(FileTableModel.COL_DATE, SortOrder.DESCENDING)
        assertEquals("..", visibleNames().first())
    }

    @Test
    fun parentAlwaysFirstWhenSortByExtDesc() {
        model.setEntries(listOf(
            entry("..", isDirectory = true, isParentLink = true),
            entry("file.zzz", size = 10),
            entry("file.aaa", size = 20),
        ))
        sortBy(FileTableModel.COL_EXT, SortOrder.DESCENDING)
        assertEquals("..", visibleNames().first())
    }

    // --- Directories grouped before files (sortWithDirectories = false) ---

    @Test
    fun dirsBeforeFilesNameAsc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("file_a.txt", size = 100),
            entry("dir_b", isDirectory = true),
            entry("file_c.txt", size = 200),
            entry("dir_a", isDirectory = true),
        ))
        sortBy(FileTableModel.COL_NAME, SortOrder.ASCENDING)
        assertEquals(listOf("dir_a", "dir_b", "file_a.txt", "file_c.txt"), visibleNames())
    }

    @Test
    fun dirsBeforeFilesNameDesc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("file_a.txt", size = 100),
            entry("dir_b", isDirectory = true),
            entry("file_c.txt", size = 200),
            entry("dir_a", isDirectory = true),
        ))
        sortBy(FileTableModel.COL_NAME, SortOrder.DESCENDING)
        val names = visibleNames()
        // Dirs first (in desc order within group), then files (in desc order)
        assertEquals("dir_b", names[0])
        assertEquals("dir_a", names[1])
        assertEquals("file_c.txt", names[2])
        assertEquals("file_a.txt", names[3])
    }

    @Test
    fun dirsBeforeFilesExtAsc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("file.zzz", size = 100),
            entry("dir_b", isDirectory = true),
            entry("file.aaa", size = 200),
            entry("dir_a", isDirectory = true),
        ))
        sortBy(FileTableModel.COL_EXT, SortOrder.ASCENDING)
        val names = visibleNames()
        // Dirs first (sorted by name asc), then files (sorted by ext asc)
        assertTrue("Dirs should come first", names.indexOf("dir_a") < names.indexOf("file.aaa"))
        assertTrue("Dirs should come first", names.indexOf("dir_b") < names.indexOf("file.zzz"))
    }

    @Test
    fun dirsBeforeFilesExtDesc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("file.zzz", size = 100),
            entry("dir_b", isDirectory = true),
            entry("file.aaa", size = 200),
            entry("dir_a", isDirectory = true),
        ))
        sortBy(FileTableModel.COL_EXT, SortOrder.DESCENDING)
        val names = visibleNames()
        // Dirs first, then files sorted by ext desc
        assertTrue("Dirs should come before files", names.indexOf("dir_a") < names.indexOf("file.zzz"))
        assertTrue("Dirs should come before files", names.indexOf("dir_b") < names.indexOf("file.aaa"))
        assertTrue("file.zzz should come before file.aaa in ext desc", names.indexOf("file.zzz") < names.indexOf("file.aaa"))
    }

    @Test
    fun dirsBeforeFilesSizeAsc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("small.txt", size = 10),
            entry("dir_a", isDirectory = true),
            entry("big.txt", size = 9999),
        ))
        sortBy(FileTableModel.COL_SIZE, SortOrder.ASCENDING)
        val names = visibleNames()
        assertTrue("Dir should come before files", names.indexOf("dir_a") < names.indexOf("small.txt"))
        assertTrue("small before big", names.indexOf("small.txt") < names.indexOf("big.txt"))
    }

    @Test
    fun dirsBeforeFilesSizeDesc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("small.txt", size = 10),
            entry("dir_a", isDirectory = true),
            entry("big.txt", size = 9999),
        ))
        sortBy(FileTableModel.COL_SIZE, SortOrder.DESCENDING)
        val names = visibleNames()
        assertEquals("Dir should be first", "dir_a", names[0])
        assertEquals("big before small in desc", "big.txt", names[1])
        assertEquals("small last", "small.txt", names[2])
    }

    @Test
    fun dirsBeforeFilesDateDesc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("old.txt", size = 10, lastModified = t1),
            entry("dir_a", isDirectory = true, lastModified = t2),
            entry("new.txt", size = 20, lastModified = t5),
        ))
        sortBy(FileTableModel.COL_DATE, SortOrder.DESCENDING)
        val names = visibleNames()
        assertEquals("Dir should be first", "dir_a", names[0])
    }

    // --- sortWithDirectories = true: dirs mixed with files ---

    @Test
    fun dirsMixedWithFilesWhenSettingOn() {
        sortWithDirectories = true
        model.setEntries(listOf(
            entry("file_a.txt", size = 100),
            entry("dir_b", isDirectory = true),
            entry("file_c.txt", size = 200),
            entry("dir_a", isDirectory = true),
        ))
        sortBy(FileTableModel.COL_NAME, SortOrder.ASCENDING)
        // Sorted alphabetically without grouping
        assertEquals(listOf("dir_a", "dir_b", "file_a.txt", "file_c.txt"), visibleNames())
    }

    @Test
    fun dirsMixedWithFilesNameDesc() {
        sortWithDirectories = true
        model.setEntries(listOf(
            entry("aaa.txt", size = 100),
            entry("dir_z", isDirectory = true),
            entry("zzz.txt", size = 200),
            entry("dir_a", isDirectory = true),
        ))
        sortBy(FileTableModel.COL_NAME, SortOrder.DESCENDING)
        assertEquals(listOf("zzz.txt", "dir_z", "dir_a", "aaa.txt"), visibleNames())
    }

    // --- Secondary name sort key ---

    @Test
    fun secondaryNameSortForSizeColumn() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("charlie.txt", size = 100),
            entry("alpha.txt", size = 100),
            entry("bravo.txt", size = 100),
        ))
        sortBy(FileTableModel.COL_SIZE, SortOrder.ASCENDING)
        // Same size, should be sorted by name ascending
        assertEquals(listOf("alpha.txt", "bravo.txt", "charlie.txt"), visibleNames())
    }

    @Test
    fun secondaryNameSortForExtColumn() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("charlie.txt", size = 100),
            entry("alpha.txt", size = 200),
            entry("bravo.txt", size = 300),
        ))
        sortBy(FileTableModel.COL_EXT, SortOrder.ASCENDING)
        // Same extension (.txt), should be sorted by name ascending
        assertEquals(listOf("alpha.txt", "bravo.txt", "charlie.txt"), visibleNames())
    }

    @Test
    fun secondaryNameSortForDateColumn() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("charlie.txt", size = 100, lastModified = t1),
            entry("alpha.txt", size = 200, lastModified = t1),
            entry("bravo.txt", size = 300, lastModified = t1),
        ))
        sortBy(FileTableModel.COL_DATE, SortOrder.ASCENDING)
        // Same date, should be sorted by name ascending
        assertEquals(listOf("alpha.txt", "bravo.txt", "charlie.txt"), visibleNames())
    }

    // --- Directories sorted by name within their group ---

    @Test
    fun dirsSortedByNameWhenSortByExtAsc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("dir_c", isDirectory = true),
            entry("dir_a", isDirectory = true),
            entry("dir_b", isDirectory = true),
            entry("file.txt", size = 100),
        ))
        sortBy(FileTableModel.COL_EXT, SortOrder.ASCENDING)
        // Dirs grouped first, sorted by name (secondary key)
        assertEquals(listOf("dir_a", "dir_b", "dir_c", "file.txt"), visibleNames())
    }

    @Test
    fun dirsSortedByNameWhenSortByExtDesc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("dir_c", isDirectory = true),
            entry("dir_a", isDirectory = true),
            entry("dir_b", isDirectory = true),
            entry("file.txt", size = 100),
        ))
        sortBy(FileTableModel.COL_EXT, SortOrder.DESCENDING)
        // Dirs grouped first, sorted by name ascending (stable secondary key)
        assertEquals("dir_a", visibleNames()[0])
        assertEquals("dir_b", visibleNames()[1])
        assertEquals("dir_c", visibleNames()[2])
        assertEquals("file.txt", visibleNames()[3])
    }

    @Test
    fun dirsSortedByNameWhenSortBySizeDesc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("dir_c", isDirectory = true),
            entry("dir_a", isDirectory = true),
            entry("dir_b", isDirectory = true),
            entry("file.txt", size = 100),
        ))
        sortBy(FileTableModel.COL_SIZE, SortOrder.DESCENDING)
        // Dirs first, sorted by name ascending
        assertEquals("dir_a", visibleNames()[0])
        assertEquals("dir_b", visibleNames()[1])
        assertEquals("dir_c", visibleNames()[2])
    }

    // --- Switching sort columns resets secondary key ---

    @Test
    fun switchingFromDateToExtResortsDirsByName() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("dir_c", isDirectory = true, lastModified = t1),
            entry("dir_a", isDirectory = true, lastModified = t3),
            entry("dir_b", isDirectory = true, lastModified = t2),
            entry("file.txt", size = 100, lastModified = t4),
        ))
        // First sort by date
        sortBy(FileTableModel.COL_DATE, SortOrder.ASCENDING)
        // Then sort by ext
        sortBy(FileTableModel.COL_EXT, SortOrder.ASCENDING)
        // Dirs should be sorted by name (secondary key), not by previous date order
        assertEquals("dir_a", visibleNames()[0])
        assertEquals("dir_b", visibleNames()[1])
        assertEquals("dir_c", visibleNames()[2])
    }

    @Test
    fun switchingFromDateToExtDescResortsDirsByName() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("dir_c", isDirectory = true, lastModified = t1),
            entry("dir_a", isDirectory = true, lastModified = t3),
            entry("dir_b", isDirectory = true, lastModified = t2),
            entry("file.txt", size = 100, lastModified = t4),
        ))
        // First sort by date desc
        sortBy(FileTableModel.COL_DATE, SortOrder.DESCENDING)
        // Then sort by ext desc
        sortBy(FileTableModel.COL_EXT, SortOrder.DESCENDING)
        // Dirs should be sorted by name asc (stable secondary), not by previous date order
        assertEquals("dir_a", visibleNames()[0])
        assertEquals("dir_b", visibleNames()[1])
        assertEquals("dir_c", visibleNames()[2])
    }

    // --- Combined: parent + dirs + files ---

    @Test
    fun fullScenarioNameAsc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("..", isDirectory = true, isParentLink = true),
            entry("zebra.txt", size = 500),
            entry("docs", isDirectory = true),
            entry("alpha.txt", size = 100),
            entry("build", isDirectory = true),
        ))
        sortBy(FileTableModel.COL_NAME, SortOrder.ASCENDING)
        assertEquals(listOf("..", "build", "docs", "alpha.txt", "zebra.txt"), visibleNames())
    }

    @Test
    fun fullScenarioNameDesc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("..", isDirectory = true, isParentLink = true),
            entry("zebra.txt", size = 500),
            entry("docs", isDirectory = true),
            entry("alpha.txt", size = 100),
            entry("build", isDirectory = true),
        ))
        sortBy(FileTableModel.COL_NAME, SortOrder.DESCENDING)
        assertEquals("..", visibleNames()[0])
        assertEquals("docs", visibleNames()[1])
        assertEquals("build", visibleNames()[2])
        assertEquals("zebra.txt", visibleNames()[3])
        assertEquals("alpha.txt", visibleNames()[4])
    }

    @Test
    fun fullScenarioSizeAsc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("..", isDirectory = true, isParentLink = true),
            entry("big.txt", size = 9999),
            entry("dir_a", isDirectory = true),
            entry("small.txt", size = 10),
            entry("dir_b", isDirectory = true),
        ))
        sortBy(FileTableModel.COL_SIZE, SortOrder.ASCENDING)
        val names = visibleNames()
        assertEquals("..", names[0])
        assertEquals("dir_a", names[1])
        assertEquals("dir_b", names[2])
        assertEquals("small.txt", names[3])
        assertEquals("big.txt", names[4])
    }

    @Test
    fun fullScenarioSizeDesc() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("..", isDirectory = true, isParentLink = true),
            entry("big.txt", size = 9999),
            entry("dir_a", isDirectory = true),
            entry("small.txt", size = 10),
            entry("dir_b", isDirectory = true),
        ))
        sortBy(FileTableModel.COL_SIZE, SortOrder.DESCENDING)
        val names = visibleNames()
        assertEquals("..", names[0])
        assertEquals("dir_a", names[1])
        assertEquals("dir_b", names[2])
        assertEquals("big.txt", names[3])
        assertEquals("small.txt", names[4])
    }

    // --- Toggle sort order via header click ---

    @Test
    fun toggleSortOrderWorksForAllColumns() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("dir_a", isDirectory = true),
            entry("beta.txt", size = 200),
            entry("alpha.txt", size = 100),
        ))
        // Toggle Name: ASC then DESC
        sorter.toggleSortOrder(FileTableModel.COL_NAME)
        assertEquals(listOf("dir_a", "alpha.txt", "beta.txt"), visibleNames())
        sorter.toggleSortOrder(FileTableModel.COL_NAME)
        assertEquals("dir_a", visibleNames()[0]) // dir still first
        assertEquals("beta.txt", visibleNames()[1])
        assertEquals("alpha.txt", visibleNames()[2])
    }

    @Test
    fun toggleSortOrderExtBothDirections() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("file.zzz", size = 100),
            entry("dir_a", isDirectory = true),
            entry("file.aaa", size = 200),
        ))
        sorter.toggleSortOrder(FileTableModel.COL_EXT)
        val ascNames = visibleNames()
        assertEquals("dir_a", ascNames[0]) // dir first
        assertEquals("file.aaa", ascNames[1])
        assertEquals("file.zzz", ascNames[2])

        sorter.toggleSortOrder(FileTableModel.COL_EXT)
        val descNames = visibleNames()
        assertEquals("dir_a", descNames[0]) // dir still first
        assertEquals("file.zzz", descNames[1])
        assertEquals("file.aaa", descNames[2])
    }

    // --- Chevron on correct column ---

    @Test
    fun sortKeysReflectUserColumn() {
        model.setEntries(listOf(entry("file.txt", size = 100)))
        sortBy(FileTableModel.COL_EXT, SortOrder.DESCENDING)
        val keys = sorter.sortKeys
        // The first key visible to the header should be the user's column, not a hidden grouping key
        assertTrue("Sort keys should contain user's column", keys.any { it.column == FileTableModel.COL_EXT })
        assertEquals("First key should be user's column", FileTableModel.COL_EXT, keys[0].column)
    }

    // --- Dynamically changing sortWithDirectories ---

    @Test
    fun changingSortWithDirectoriesRegroups() {
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("file_a.txt", size = 100),
            entry("dir_b", isDirectory = true),
            entry("aaa.txt", size = 200),
            entry("dir_a", isDirectory = true),
        ))
        sortBy(FileTableModel.COL_NAME, SortOrder.ASCENDING)
        assertEquals(listOf("dir_a", "dir_b", "aaa.txt", "file_a.txt"), visibleNames())

        // Switch to sorting dirs with files
        sortWithDirectories = true
        // Re-apply sort to pick up new setting (must force re-sort via sort())
        sorter.sort()
        assertEquals(listOf("aaa.txt", "dir_a", "dir_b", "file_a.txt"), visibleNames())
    }

    // --- Natural vs lexicographic name sorting ---

    @Test
    fun naturalSortOrdersEmbeddedNumbersNumerically() {
        naturalNameSort = true
        sortWithDirectories = true
        model.setEntries(listOf(
            entry("file10.txt", size = 100),
            entry("file2.txt", size = 100),
            entry("file1.txt", size = 100),
        ))
        sortBy(FileTableModel.COL_NAME, SortOrder.ASCENDING)
        // Natural order: 1, 2, 10 (not lexicographic 1, 10, 2)
        assertEquals(listOf("file1.txt", "file2.txt", "file10.txt"), visibleNames())
    }

    @Test
    fun naturalSortDescReversesNumericOrder() {
        naturalNameSort = true
        sortWithDirectories = true
        model.setEntries(listOf(
            entry("file10.txt", size = 100),
            entry("file2.txt", size = 100),
            entry("file1.txt", size = 100),
        ))
        sortBy(FileTableModel.COL_NAME, SortOrder.DESCENDING)
        assertEquals(listOf("file10.txt", "file2.txt", "file1.txt"), visibleNames())
    }

    @Test
    fun lexicographicSortOrdersEmbeddedNumbersAsStrings() {
        naturalNameSort = false
        sortWithDirectories = true
        model.setEntries(listOf(
            entry("file10.txt", size = 100),
            entry("file2.txt", size = 100),
            entry("file1.txt", size = 100),
        ))
        sortBy(FileTableModel.COL_NAME, SortOrder.ASCENDING)
        // Lexicographic order: "file1", "file10", "file2"
        assertEquals(listOf("file1.txt", "file10.txt", "file2.txt"), visibleNames())
    }

    @Test
    fun naturalSortAppliesToSecondaryNameKey() {
        // When sorting by a non-name column, ties break on the name key, which
        // reuses the Name column's comparator and so should also sort naturally.
        naturalNameSort = true
        sortWithDirectories = true
        model.setEntries(listOf(
            entry("file10.txt", size = 100),
            entry("file2.txt", size = 100),
            entry("file1.txt", size = 100),
        ))
        sortBy(FileTableModel.COL_SIZE, SortOrder.ASCENDING)
        assertEquals(listOf("file1.txt", "file2.txt", "file10.txt"), visibleNames())
    }

    @Test
    fun naturalSortDoesNotAffectExtensionColumn() {
        // Natural ordering is scoped to the Name column; the Ext column keeps
        // plain lexicographic comparison of extensions.
        naturalNameSort = true
        sortWithDirectories = true
        model.setEntries(listOf(
            entry("a.v10", size = 100),
            entry("b.v2", size = 100),
            entry("c.v1", size = 100),
        ))
        sortBy(FileTableModel.COL_EXT, SortOrder.ASCENDING)
        // Lexicographic on extension: v1, v10, v2
        assertEquals(listOf("c.v1", "a.v10", "b.v2"), visibleNames())
    }

    @Test
    fun naturalSortKeepsDirectoryGrouping() {
        naturalNameSort = true
        sortWithDirectories = false
        model.setEntries(listOf(
            entry("file10", size = 100),
            entry("dir2", isDirectory = true),
            entry("dir10", isDirectory = true),
            entry("file2", size = 100),
            entry("dir1", isDirectory = true),
        ))
        sortBy(FileTableModel.COL_NAME, SortOrder.ASCENDING)
        // Dirs first (natural), then files (natural)
        assertEquals(listOf("dir1", "dir2", "dir10", "file2", "file10"), visibleNames())
    }
}
