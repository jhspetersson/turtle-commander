package io.github.jhspetersson.turtlecommander.service

import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.dialog.NamePatternMode
import io.github.jhspetersson.turtlecommander.dialog.PermissionsFilter
import io.github.jhspetersson.turtlecommander.dialog.PermissionsFilterMode
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.util.PermissionFlag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class FilePermissionsFilterTest {

    private val tempDirs = mutableListOf<Path>()

    @After
    fun cleanup() {
        for (dir in tempDirs.reversed()) {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun createTempDir(): Path {
        val dir = Files.createTempDirectory("perm-search-test-")
        tempDirs.add(dir)
        return dir
    }

    private fun search(
        criteria: FileSearchCriteria,
        isWindows: Boolean = false,
        flagsByName: Map<String, Set<PermissionFlag>> = emptyMap(),
    ): List<FileEntry> {
        val results = mutableListOf<FileEntry>()
        var flagCalls = 0
        val service = FileSearchService(
            criteria,
            isWindows = isWindows,
            ownerReader = { "" },
            groupReader = { "" },
            permissionsFlagReader = { path ->
                flagCalls++
                flagsByName[path.fileName.toString()] ?: emptySet()
            },
        )
        service.search(
            onResult = { results.add(it) },
            isCancelled = { false },
            onProgress = { _, _ -> },
        )
        lastFlagCalls = flagCalls
        return results
    }

    private var lastFlagCalls = 0

    private fun baseCriteria(dir: Path, filter: PermissionsFilter) = FileSearchCriteria(
        rootPath = dir,
        namePattern = null,
        namePatternMode = NamePatternMode.GLOB,
        sizeFilter = null,
        creationDateFilter = null,
        modificationDateFilter = null,
        permissionsFilter = filter,
    )

    @Test
    fun `permissions not read when filter is null`() {
        val dir = createTempDir()
        Files.write(dir.resolve("a.txt"), ByteArray(1))

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = null,
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )
        val results = search(criteria)

        assertEquals(1, results.size)
        assertEquals(0, lastFlagCalls)
    }

    @Test
    fun `posix EXACT_MATCH returns only files with identical flag set`() {
        val dir = createTempDir()
        Files.write(dir.resolve("exact.txt"), ByteArray(1))
        Files.write(dir.resolve("superset.txt"), ByteArray(1))
        Files.write(dir.resolve("different.txt"), ByteArray(1))

        val target = setOf(PermissionFlag.OWNER_READ, PermissionFlag.OWNER_WRITE)
        val flags = mapOf(
            "exact.txt" to target,
            "superset.txt" to target + PermissionFlag.GROUP_READ,
            "different.txt" to setOf(PermissionFlag.OWNER_READ),
        )

        val results = search(
            baseCriteria(dir, PermissionsFilter(PermissionsFilterMode.EXACT_MATCH, target)),
            flagsByName = flags,
        )

        assertEquals(listOf("exact.txt"), results.map { it.name }.sorted())
    }

    @Test
    fun `posix ALL_OF returns files containing every required flag`() {
        val dir = createTempDir()
        Files.write(dir.resolve("full.txt"), ByteArray(1))
        Files.write(dir.resolve("extra.txt"), ByteArray(1))
        Files.write(dir.resolve("partial.txt"), ByteArray(1))

        val required = setOf(PermissionFlag.OWNER_READ, PermissionFlag.OWNER_WRITE)
        val flags = mapOf(
            "full.txt" to required,
            "extra.txt" to required + PermissionFlag.OTHERS_READ,
            "partial.txt" to setOf(PermissionFlag.OWNER_READ),
        )

        val results = search(
            baseCriteria(dir, PermissionsFilter(PermissionsFilterMode.ALL_OF, required)),
            flagsByName = flags,
        ).map { it.name }.sorted()

        assertEquals(listOf("extra.txt", "full.txt"), results)
    }

    @Test
    fun `posix ALL_OF filters on the setuid setgid and sticky bits`() {
        val dir = createTempDir()
        Files.write(dir.resolve("suid.txt"), ByteArray(1))
        Files.write(dir.resolve("sticky.txt"), ByteArray(1))
        Files.write(dir.resolve("plain.txt"), ByteArray(1))

        val flags = mapOf(
            "suid.txt" to setOf(PermissionFlag.OWNER_EXECUTE, PermissionFlag.SETUID),
            "sticky.txt" to setOf(PermissionFlag.OTHERS_EXECUTE, PermissionFlag.STICKY),
            "plain.txt" to setOf(PermissionFlag.OWNER_EXECUTE),
        )

        val results = search(
            baseCriteria(dir, PermissionsFilter(PermissionsFilterMode.ALL_OF, setOf(PermissionFlag.SETUID))),
            flagsByName = flags,
        ).map { it.name }

        assertEquals(listOf("suid.txt"), results)
    }

    @Test
    fun `posix ANY_OF returns files sharing at least one flag`() {
        val dir = createTempDir()
        Files.write(dir.resolve("match.txt"), ByteArray(1))
        Files.write(dir.resolve("nomatch.txt"), ByteArray(1))

        val any = setOf(PermissionFlag.OWNER_EXECUTE, PermissionFlag.GROUP_EXECUTE)
        val flags = mapOf(
            "match.txt" to setOf(PermissionFlag.OWNER_READ, PermissionFlag.GROUP_EXECUTE),
            "nomatch.txt" to setOf(PermissionFlag.OWNER_READ, PermissionFlag.OTHERS_READ),
        )

        val results = search(
            baseCriteria(dir, PermissionsFilter(PermissionsFilterMode.ANY_OF, any)),
            flagsByName = flags,
        ).map { it.name }

        assertEquals(listOf("match.txt"), results)
    }

    @Test
    fun `posix NONE_OF returns files with no forbidden flags`() {
        val dir = createTempDir()
        Files.write(dir.resolve("clean.txt"), ByteArray(1))
        Files.write(dir.resolve("dirty.txt"), ByteArray(1))

        val forbidden = setOf(PermissionFlag.OTHERS_WRITE, PermissionFlag.GROUP_WRITE)
        val flags = mapOf(
            "clean.txt" to setOf(PermissionFlag.OWNER_READ, PermissionFlag.OWNER_WRITE),
            "dirty.txt" to setOf(PermissionFlag.OWNER_READ, PermissionFlag.OTHERS_WRITE),
        )

        val results = search(
            baseCriteria(dir, PermissionsFilter(PermissionsFilterMode.NONE_OF, forbidden)),
            flagsByName = flags,
        ).map { it.name }

        assertEquals(listOf("clean.txt"), results)
    }

    @Test
    fun `NONE_OF with empty flag set matches all files`() {
        val dir = createTempDir()
        Files.write(dir.resolve("a.txt"), ByteArray(1))
        Files.write(dir.resolve("b.txt"), ByteArray(1))

        val results = search(
            baseCriteria(dir, PermissionsFilter(PermissionsFilterMode.NONE_OF, emptySet())),
            flagsByName = mapOf(
                "a.txt" to setOf(PermissionFlag.OWNER_READ),
                "b.txt" to setOf(PermissionFlag.OTHERS_WRITE),
            ),
        )
        assertEquals(2, results.size)
    }

    @Test
    fun `ALL_OF with empty flag set matches all files`() {
        val dir = createTempDir()
        Files.write(dir.resolve("a.txt"), ByteArray(1))
        Files.write(dir.resolve("b.txt"), ByteArray(1))

        val results = search(
            baseCriteria(dir, PermissionsFilter(PermissionsFilterMode.ALL_OF, emptySet())),
            flagsByName = mapOf(
                "a.txt" to setOf(PermissionFlag.OWNER_READ),
                "b.txt" to emptySet(),
            ),
        )
        assertEquals(2, results.size)
    }

    @Test
    fun `dos EXACT_MATCH handles windows flags`() {
        val dir = createTempDir()
        Files.write(dir.resolve("hidden.txt"), ByteArray(1))
        Files.write(dir.resolve("plain.txt"), ByteArray(1))

        val target = setOf(PermissionFlag.DOS_HIDDEN, PermissionFlag.DOS_ARCHIVE)
        val flags = mapOf(
            "hidden.txt" to target,
            "plain.txt" to setOf(PermissionFlag.DOS_ARCHIVE),
        )

        val results = search(
            baseCriteria(dir, PermissionsFilter(PermissionsFilterMode.EXACT_MATCH, target)),
            isWindows = true,
            flagsByName = flags,
        ).map { it.name }

        assertEquals(listOf("hidden.txt"), results)
    }

    @Test
    fun `dos ALL_OF requires all listed attributes`() {
        val dir = createTempDir()
        Files.write(dir.resolve("hs.txt"), ByteArray(1))
        Files.write(dir.resolve("h.txt"), ByteArray(1))

        val required = setOf(PermissionFlag.DOS_HIDDEN, PermissionFlag.DOS_SYSTEM)
        val flags = mapOf(
            "hs.txt" to required + PermissionFlag.DOS_ARCHIVE,
            "h.txt" to setOf(PermissionFlag.DOS_HIDDEN),
        )

        val results = search(
            baseCriteria(dir, PermissionsFilter(PermissionsFilterMode.ALL_OF, required)),
            isWindows = true,
            flagsByName = flags,
        ).map { it.name }

        assertTrue(results.contains("hs.txt"))
        assertTrue(!results.contains("h.txt"))
    }

    @Test
    fun `permissions filter combines with name filter`() {
        val dir = createTempDir()
        Files.write(dir.resolve("match.kt"), ByteArray(1))
        Files.write(dir.resolve("match.txt"), ByteArray(1))
        Files.write(dir.resolve("other.kt"), ByteArray(1))

        val required = setOf(PermissionFlag.OWNER_EXECUTE)
        val flags = mapOf(
            "match.kt" to required,
            "match.txt" to required,
            "other.kt" to emptySet(),
        )

        val criteria = FileSearchCriteria(
            rootPath = dir,
            namePattern = "*.kt",
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
            permissionsFilter = PermissionsFilter(PermissionsFilterMode.ALL_OF, required),
        )

        val results = search(criteria, flagsByName = flags).map { it.name }
        assertEquals(listOf("match.kt"), results)
    }
}
