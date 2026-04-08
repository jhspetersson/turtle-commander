package io.github.jhspetersson.turtlecommander.service
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.dialog.DateFilter
import io.github.jhspetersson.turtlecommander.dialog.DateFilterMode
import io.github.jhspetersson.turtlecommander.dialog.NamePatternMode
import io.github.jhspetersson.turtlecommander.dialog.SizeFilter
import io.github.jhspetersson.turtlecommander.dialog.SizeFilterMode

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class FileSearchFilterTest {

    private val tempDirs = mutableListOf<Path>()

    @After
    fun cleanup() {
        for (dir in tempDirs.reversed()) {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun createTempDir(): Path {
        val dir = Files.createTempDirectory("search-test-")
        tempDirs.add(dir)
        return dir
    }

    private fun search(criteria: FileSearchCriteria): List<FileEntry> {
        val results = mutableListOf<FileEntry>()
        val service = FileSearchService(criteria)
        service.search(
            onResult = { results.add(it) },
            isCancelled = { false },
            onProgress = { _, _ -> },
        )
        return results
    }

    // --- Size filter ---

    @Test
    fun `size filter MORE_THAN`() {
        val dir = createTempDir()
        Files.write(dir.resolve("small.txt"), ByteArray(10))
        Files.write(dir.resolve("big.txt"), ByteArray(1000))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = SizeFilter(SizeFilterMode.MORE_THAN, 500, null),
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("big.txt", results[0].name)
    }

    @Test
    fun `size filter LESS_THAN`() {
        val dir = createTempDir()
        Files.write(dir.resolve("small.txt"), ByteArray(10))
        Files.write(dir.resolve("big.txt"), ByteArray(1000))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = SizeFilter(SizeFilterMode.LESS_THAN, 500, null),
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("small.txt", results[0].name)
    }

    @Test
    fun `size filter APPROX_EQUAL`() {
        val dir = createTempDir()
        Files.write(dir.resolve("close.txt"), ByteArray(105))
        Files.write(dir.resolve("far.txt"), ByteArray(500))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = SizeFilter(SizeFilterMode.APPROX_EQUAL, 100, null),
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("close.txt", results[0].name)
    }

    @Test
    fun `size filter IN_BETWEEN`() {
        val dir = createTempDir()
        Files.write(dir.resolve("small.txt"), ByteArray(10))
        Files.write(dir.resolve("mid.txt"), ByteArray(500))
        Files.write(dir.resolve("big.txt"), ByteArray(2000))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = SizeFilter(SizeFilterMode.IN_BETWEEN, 100, 1000),
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("mid.txt", results[0].name)
    }

    @Test
    fun `size filter skips directories`() {
        val dir = createTempDir()
        Files.createDirectory(dir.resolve("subdir"))
        Files.write(dir.resolve("file.txt"), ByteArray(100))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = SizeFilter(SizeFilterMode.MORE_THAN, 0, null),
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("file.txt", results[0].name)
    }

    // --- Name pattern ---

    @Test
    fun `glob pattern matches`() {
        val dir = createTempDir()
        Files.write(dir.resolve("report.txt"), ByteArray(0))
        Files.write(dir.resolve("image.png"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = "*.txt",
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("report.txt", results[0].name)
    }

    @Test
    fun `regexp pattern matches`() {
        val dir = createTempDir()
        Files.write(dir.resolve("data_2024.csv"), ByteArray(0))
        Files.write(dir.resolve("notes.txt"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = "\\d{4}",
            namePatternMode = NamePatternMode.REGEXP,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("data_2024.csv", results[0].name)
    }

    @Test
    fun `glob pattern is case-insensitive by default`() {
        val dir = createTempDir()
        Files.write(dir.resolve("Report.TXT"), ByteArray(0))
        Files.write(dir.resolve("image.png"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = "*.txt",
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("Report.TXT", results[0].name)
    }

    @Test
    fun `glob pattern case-sensitive when enabled`() {
        val dir = createTempDir()
        Files.write(dir.resolve("report.txt"), ByteArray(0))
        Files.write(dir.resolve("image.PNG"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = "*.txt",
            namePatternMode = NamePatternMode.GLOB,
            caseSensitive = true,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("report.txt", results[0].name)
    }

    @Test
    fun `regexp pattern is case-insensitive by default`() {
        val dir = createTempDir()
        Files.write(dir.resolve("Data_2024.csv"), ByteArray(0))
        Files.write(dir.resolve("notes.txt"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = "data",
            namePatternMode = NamePatternMode.REGEXP,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("Data_2024.csv", results[0].name)
    }

    @Test
    fun `regexp pattern case-sensitive when enabled`() {
        val dir = createTempDir()
        Files.write(dir.resolve("data_2024.csv"), ByteArray(0))
        Files.write(dir.resolve("notes.txt"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = "^data",
            namePatternMode = NamePatternMode.REGEXP,
            caseSensitive = true,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("data_2024.csv", results[0].name)
    }

    @Test
    fun `simple glob without dot or star is wrapped`() {
        val dir = createTempDir()
        Files.write(dir.resolve("report.txt"), ByteArray(0))
        Files.write(dir.resolve("my_report.csv"), ByteArray(0))
        Files.write(dir.resolve("image.png"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = "report",
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(2, results.size)
        assertTrue(results.all { "report" in it.name.lowercase() })
    }

    @Test
    fun `glob with star is not wrapped`() {
        val dir = createTempDir()
        Files.write(dir.resolve("report.txt"), ByteArray(0))
        Files.write(dir.resolve("my_report.csv"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = "report*",
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("report.txt", results[0].name)
    }

    @Test
    fun `glob with dot is not wrapped`() {
        val dir = createTempDir()
        Files.write(dir.resolve("report.txt"), ByteArray(0))
        Files.write(dir.resolve("report.csv"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = "report.txt",
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("report.txt", results[0].name)
    }

    @Test(expected = FileSearchService.InvalidPatternException::class)
    fun `invalid glob pattern throws InvalidPatternException`() {
        val dir = createTempDir()
        Files.write(dir.resolve("file.txt"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = "[invalid",
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        search(criteria)
    }

    @Test(expected = FileSearchService.InvalidPatternException::class)
    fun `invalid regex pattern throws InvalidPatternException`() {
        val dir = createTempDir()
        Files.write(dir.resolve("file.txt"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = "(unclosed",
            namePatternMode = NamePatternMode.REGEXP,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )

        search(criteria)
    }

    // --- Date filter ---

    @Test
    fun `modification date filter LATER`() {
        val dir = createTempDir()
        Files.write(dir.resolve("recent.txt"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = DateFilter(
                mode = DateFilterMode.LATER,
                startMillis = 0,
                endMillis = 1000,
                startMillis2 = null,
                endMillis2 = null,
                text1 = "",
                text2 = null,
            ),
        )

        val results = search(criteria)
        assertEquals(1, results.size)
    }

    @Test
    fun `modification date filter EARLIER excludes recent files`() {
        val dir = createTempDir()
        Files.write(dir.resolve("recent.txt"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = DateFilter(
                mode = DateFilterMode.EARLIER,
                startMillis = 1000,
                endMillis = 1000,
                startMillis2 = null,
                endMillis2 = null,
                text1 = "",
                text2 = null,
            ),
        )

        val results = search(criteria)
        assertTrue(results.isEmpty())
    }

    // --- Owner filter ---

    @Test
    fun `owner filter matches current user`() {
        val dir = createTempDir()
        val file = Files.write(dir.resolve("owned.txt"), ByteArray(0))

        // Get the actual owner of the file we just created
        val actualOwner = Files.getOwner(file).name

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
            ownerPattern = actualOwner,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("owned.txt", results[0].name)
    }

    @Test
    fun `owner filter partial match`() {
        val dir = createTempDir()
        val file = Files.write(dir.resolve("owned.txt"), ByteArray(0))

        val actualOwner = Files.getOwner(file).name
        // Use just the last few characters as a substring match
        val partial = actualOwner.takeLast((actualOwner.length / 2).coerceAtLeast(2))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
            ownerPattern = partial,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
    }

    @Test
    fun `owner filter case-insensitive`() {
        val dir = createTempDir()
        val file = Files.write(dir.resolve("owned.txt"), ByteArray(0))

        val actualOwner = Files.getOwner(file).name

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
            ownerPattern = actualOwner.uppercase(),
        )

        val results = search(criteria)
        assertEquals(1, results.size)
    }

    @Test
    fun `owner filter excludes non-matching`() {
        val dir = createTempDir()
        Files.write(dir.resolve("file.txt"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
            ownerPattern = "nonexistent_user_xyz_12345",
        )

        val results = search(criteria)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `null owner pattern matches all files`() {
        val dir = createTempDir()
        Files.write(dir.resolve("a.txt"), ByteArray(0))
        Files.write(dir.resolve("b.txt"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
            ownerPattern = null,
        )

        val results = search(criteria)
        assertEquals(2, results.size)
    }

    // --- Group filter ---

    @Test
    fun `group filter excludes when no match`() {
        val dir = createTempDir()
        Files.write(dir.resolve("file.txt"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
            groupPattern = "nonexistent_group_xyz_12345",
        )

        // FileSearchService uses isWindows flag; on Windows group is "" so nothing matches
        // On Linux/macOS, group is set but won't match this fake name
        val results = search(criteria)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `null group pattern matches all files`() {
        val dir = createTempDir()
        Files.write(dir.resolve("a.txt"), ByteArray(0))
        Files.write(dir.resolve("b.txt"), ByteArray(0))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
            groupPattern = null,
        )

        val results = search(criteria)
        assertEquals(2, results.size)
    }

    // --- Combined owner + other filters ---

    @Test
    fun `owner filter combined with name pattern`() {
        val dir = createTempDir()
        val file = Files.write(dir.resolve("match.txt"), ByteArray(0))
        Files.write(dir.resolve("match.csv"), ByteArray(0))

        val actualOwner = Files.getOwner(file).name

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = "*.txt",
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
            ownerPattern = actualOwner,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("match.txt", results[0].name)
    }

    @Test
    fun `owner filter combined with size filter`() {
        val dir = createTempDir()
        val file = Files.write(dir.resolve("big.txt"), ByteArray(1000))
        Files.write(dir.resolve("small.txt"), ByteArray(10))

        val actualOwner = Files.getOwner(file).name

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = SizeFilter(SizeFilterMode.MORE_THAN, 500, null),
            creationDateFilter = null,
            modificationDateFilter = null,
            ownerPattern = actualOwner,
        )

        val results = search(criteria)
        assertEquals(1, results.size)
        assertEquals("big.txt", results[0].name)
    }

    // --- Cancellation ---

    @Test
    fun `search respects cancellation`() {
        val dir = createTempDir()
        for (i in 1..100) {
            Files.write(dir.resolve("file$i.txt"), ByteArray(0))
        }

        val results = mutableListOf<FileEntry>()
        val service = FileSearchService(
            FileSearchCriteria(
                rootPath = dir,
                namePattern = null,
                namePatternMode = NamePatternMode.GLOB,
                sizeFilter = null,
                creationDateFilter = null,
                modificationDateFilter = null,
            ),
        )
        service.search(
            onResult = { results.add(it) },
            isCancelled = { results.size >= 5 },
            onProgress = { _, _ -> },
        )

        assertTrue(results.size < 100)
    }
}
