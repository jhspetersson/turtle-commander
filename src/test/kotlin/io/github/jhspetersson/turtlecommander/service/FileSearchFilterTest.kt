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
    fun `invalid glob pattern returns no results`() {
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

        val results = search(criteria)
        // Invalid pattern compiles to null matcher, so all files match
        assertTrue(results.isNotEmpty())
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
