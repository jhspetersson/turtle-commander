package io.github.jhspetersson.turtlecommander.service
import io.github.jhspetersson.turtlecommander.util.fileErrorMessage
import io.github.jhspetersson.turtlecommander.util.countFiles

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

// ─── fileErrorMessage ───────────────────────────────────────────────────────

class FileErrorMessageTest {

    @Test
    fun `AccessDeniedException shows access denied with path`() {
        val e = AccessDeniedException("C:\\Documents and Settings")
        assertEquals("Access denied: C:\\Documents and Settings", fileErrorMessage(e))
    }

    @Test
    fun `exception with message shows that message`() {
        val e = RuntimeException("something went wrong")
        assertEquals("something went wrong", fileErrorMessage(e))
    }

    @Test
    fun `exception with null message shows class name`() {
        val e = RuntimeException()
        assertEquals("RuntimeException", fileErrorMessage(e))
    }

    @Test
    fun `NoSuchFileException shows its message`() {
        val e = NoSuchFileException("/nonexistent")
        assertEquals("/nonexistent", fileErrorMessage(e))
    }
}

// ─── countFiles with inaccessible entries ───────────────────────────────────

class CountFilesTest {

    private val tempDirs = mutableListOf<Path>()

    @After
    fun tearDown() {
        for (dir in tempDirs.reversed()) {
            try {
                Files.walk(dir).sorted(Comparator.reverseOrder()).forEach {
                    try {
                        it.toFile().setReadable(true)
                        it.toFile().setWritable(true)
                        Files.deleteIfExists(it)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    @Test
    fun `counts files in simple directory`() = runBlocking {
        val dir = Files.createTempDirectory("count-test-")
        tempDirs.add(dir)
        Files.writeString(dir.resolve("a.txt"), "a")
        Files.writeString(dir.resolve("b.txt"), "b")

        // dir itself + 2 files = 3
        val count = countFiles(listOf(dir))
        assertEquals(3, count)
    }

    @Test
    fun `counts single file`() = runBlocking {
        val dir = Files.createTempDirectory("count-test-")
        tempDirs.add(dir)
        val file = Files.writeString(dir.resolve("a.txt"), "a")

        assertEquals(1, countFiles(listOf(file)))
    }

    @Test
    fun `counts mixed sources`() = runBlocking {
        val dir = Files.createTempDirectory("count-test-")
        tempDirs.add(dir)
        val file1 = Files.writeString(dir.resolve("a.txt"), "a")
        val subdir = Files.createDirectory(dir.resolve("sub"))
        Files.writeString(subdir.resolve("b.txt"), "b")

        // file1 (1) + subdir tree (subdir + b.txt = 2) = 3
        assertEquals(3, countFiles(listOf(file1, subdir)))
    }

    @Test
    fun `empty list returns zero`() = runBlocking {
        assertEquals(0, countFiles(emptyList()))
    }

    @Test
    fun `empty directory counts as one`() = runBlocking {
        val dir = Files.createTempDirectory("count-test-")
        tempDirs.add(dir)

        // just the directory itself
        assertEquals(1, countFiles(listOf(dir)))
    }

    @Test
    fun `nested directories are counted`() = runBlocking {
        val dir = Files.createTempDirectory("count-test-")
        tempDirs.add(dir)
        val sub1 = Files.createDirectory(dir.resolve("sub1"))
        val sub2 = Files.createDirectory(sub1.resolve("sub2"))
        Files.writeString(sub2.resolve("deep.txt"), "deep")

        // dir + sub1 + sub2 + deep.txt = 4
        assertEquals(4, countFiles(listOf(dir)))
    }
}

// ─── packZip error handling ─────────────────────────────────────────────────

class PackZipErrorHandlingTest {

    private val tempFiles = mutableListOf<Path>()

    @After
    fun tearDown() {
        for (path in tempFiles.reversed()) {
            try {
                if (Files.isDirectory(path)) {
                    Files.walk(path).sorted(Comparator.reverseOrder()).forEach {
                        try { Files.deleteIfExists(it) } catch (_: Exception) {}
                    }
                } else {
                    Files.deleteIfExists(path)
                }
            } catch (_: Exception) {}
        }
    }

    @Test
    fun `packZip returns success count for normal files`() = runBlocking {
        val dir = Files.createTempDirectory("pack-test-")
        tempDirs(dir)
        Files.writeString(dir.resolve("a.txt"), "aaa")
        Files.writeString(dir.resolve("b.txt"), "bbb")

        val archivePath = Files.createTempFile("pack-test-", ".zip")
        tempDirs(archivePath)
        Files.delete(archivePath)

        val service = ArchiveService()
        val count = service.packZip(
            archivePath, listOf(dir), appendToExisting = false, archiveExists = false,
            onProgress = { _, _ -> },
            onError = { _, _ -> fail("should not error") },
            isCancelled = { false },
        )

        assertEquals(2, count)
        assertTrue(Files.exists(archivePath))
    }

    @Test
    fun `packZip returns zero for nonexistent source`() = runBlocking {
        val dir = Files.createTempDirectory("pack-test-")
        tempDirs(dir)
        val nonexistent = dir.resolve("ghost.txt")

        val archivePath = Files.createTempFile("pack-test-", ".zip")
        tempDirs(archivePath)
        Files.delete(archivePath)

        val errors = mutableListOf<Path>()
        val service = ArchiveService()
        val count = service.packZip(
            archivePath, listOf(nonexistent), appendToExisting = false, archiveExists = false,
            onProgress = { _, _ -> },
            onError = { path, _ -> errors.add(path) },
            isCancelled = { false },
        )

        assertEquals(0, count)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `packZip packs accessible files and reports errors for others`() = runBlocking {
        val dir = Files.createTempDirectory("pack-test-")
        tempDirs(dir)
        val good = Files.writeString(dir.resolve("good.txt"), "good")
        val nonexistent = dir.resolve("missing.txt")

        val archivePath = Files.createTempFile("pack-test-", ".zip")
        tempDirs(archivePath)
        Files.delete(archivePath)

        val errors = mutableListOf<Path>()
        val service = ArchiveService()
        val count = service.packZip(
            archivePath, listOf(good, nonexistent), appendToExisting = false, archiveExists = false,
            onProgress = { _, _ -> },
            onError = { path, _ -> errors.add(path) },
            isCancelled = { false },
        )

        assertEquals(1, count)
        assertEquals(1, errors.size)
    }

    @Test
    fun `packZip reports progress`() = runBlocking {
        val dir = Files.createTempDirectory("pack-test-")
        tempDirs(dir)
        Files.writeString(dir.resolve("a.txt"), "a")
        Files.writeString(dir.resolve("b.txt"), "b")

        val archivePath = Files.createTempFile("pack-test-", ".zip")
        tempDirs(archivePath)
        Files.delete(archivePath)

        val progressCounts = mutableListOf<Int>()
        val service = ArchiveService()
        service.packZip(
            archivePath, listOf(dir), appendToExisting = false, archiveExists = false,
            onProgress = { count, _ -> progressCounts.add(count) },
            onError = { _, _ -> },
            isCancelled = { false },
        )

        assertTrue(progressCounts.isNotEmpty())
        assertEquals(progressCounts.sorted(), progressCounts) // monotonically increasing
    }

    @Test
    fun `packZip cancellation stops early`() = runBlocking {
        val dir = Files.createTempDirectory("pack-test-")
        tempDirs(dir)
        Files.writeString(dir.resolve("a.txt"), "a")
        Files.writeString(dir.resolve("b.txt"), "b")

        val archivePath = Files.createTempFile("pack-test-", ".zip")
        tempDirs(archivePath)
        Files.delete(archivePath)

        val service = ArchiveService()
        val count = service.packZip(
            archivePath, listOf(dir), appendToExisting = false, archiveExists = false,
            onProgress = { _, _ -> },
            onError = { _, _ -> },
            isCancelled = { true }, // cancel immediately
        )

        assertEquals(0, count)
    }

    private fun tempDirs(path: Path) { tempFiles.add(path) }
}

// ─── packTarGz error handling ───────────────────────────────────────────────

class PackTarGzErrorHandlingTest {

    private val tempFiles = mutableListOf<Path>()

    @After
    fun tearDown() {
        for (path in tempFiles.reversed()) {
            try {
                if (Files.isDirectory(path)) {
                    Files.walk(path).sorted(Comparator.reverseOrder()).forEach {
                        try { Files.deleteIfExists(it) } catch (_: Exception) {}
                    }
                } else {
                    Files.deleteIfExists(path)
                }
            } catch (_: Exception) {}
        }
    }

    @Test
    fun `packTarGz returns success count for normal files`() = runBlocking {
        val dir = Files.createTempDirectory("pack-test-")
        tempDirs(dir)
        Files.writeString(dir.resolve("a.txt"), "aaa")
        Files.writeString(dir.resolve("b.txt"), "bbb")

        val archivePath = Files.createTempFile("pack-test-", ".tar.gz")
        tempDirs(archivePath)

        val service = ArchiveService()
        val count = service.packTarGz(
            archivePath, listOf(dir),
            onProgress = { _, _ -> },
            onError = { _, _ -> fail("should not error") },
            isCancelled = { false },
        )

        assertEquals(2, count)
        assertTrue(Files.exists(archivePath))
        assertTrue(Files.size(archivePath) > 0)
    }

    @Test
    fun `packTarGz returns zero for nonexistent source`() = runBlocking {
        val dir = Files.createTempDirectory("pack-test-")
        tempDirs(dir)
        val nonexistent = dir.resolve("ghost.txt")

        val archivePath = Files.createTempFile("pack-test-", ".tar.gz")
        tempDirs(archivePath)

        val errors = mutableListOf<Path>()
        val service = ArchiveService()
        val count = service.packTarGz(
            archivePath, listOf(nonexistent),
            onProgress = { _, _ -> },
            onError = { path, _ -> errors.add(path) },
            isCancelled = { false },
        )

        assertEquals(0, count)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `packTarGz packs accessible files and reports errors for others`() = runBlocking {
        val dir = Files.createTempDirectory("pack-test-")
        tempDirs(dir)
        val good = Files.writeString(dir.resolve("good.txt"), "good")
        val nonexistent = dir.resolve("missing.txt")

        val archivePath = Files.createTempFile("pack-test-", ".tar.gz")
        tempDirs(archivePath)

        val errors = mutableListOf<Path>()
        val service = ArchiveService()
        val count = service.packTarGz(
            archivePath, listOf(good, nonexistent),
            onProgress = { _, _ -> },
            onError = { path, _ -> errors.add(path) },
            isCancelled = { false },
        )

        assertEquals(1, count)
        assertEquals(1, errors.size)
    }

    @Test
    fun `packTarGz cancellation stops early`() = runBlocking {
        val dir = Files.createTempDirectory("pack-test-")
        tempDirs(dir)
        Files.writeString(dir.resolve("a.txt"), "a")
        Files.writeString(dir.resolve("b.txt"), "b")

        val archivePath = Files.createTempFile("pack-test-", ".tar.gz")
        tempDirs(archivePath)

        val service = ArchiveService()
        val count = service.packTarGz(
            archivePath, listOf(dir),
            onProgress = { _, _ -> },
            onError = { _, _ -> },
            isCancelled = { true },
        )

        assertEquals(0, count)
    }

    @Test
    fun `packTarGz single file success`() = runBlocking {
        val dir = Files.createTempDirectory("pack-test-")
        tempDirs(dir)
        val file = Files.writeString(dir.resolve("single.txt"), "content")

        val archivePath = Files.createTempFile("pack-test-", ".tar.gz")
        tempDirs(archivePath)

        val service = ArchiveService()
        val count = service.packTarGz(
            archivePath, listOf(file),
            onProgress = { _, _ -> },
            onError = { _, _ -> fail("should not error") },
            isCancelled = { false },
        )

        assertEquals(1, count)
    }

    private fun tempDirs(path: Path) { tempFiles.add(path) }
}
