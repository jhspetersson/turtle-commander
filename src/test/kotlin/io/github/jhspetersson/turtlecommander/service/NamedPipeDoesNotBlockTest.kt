package io.github.jhspetersson.turtlecommander.service

import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.dialog.NamePatternMode
import io.github.jhspetersson.turtlecommander.util.isPipeOrDevice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class NamedPipeDoesNotBlockTest {

    private lateinit var dir: Path
    private lateinit var root: Path
    private lateinit var pipe: Path

    @Before
    fun setUp() {
        assumeTrue(java.io.File.separatorChar == '/')
        dir = Files.createTempDirectory("named-pipe-does-not-block-")
        root = Files.createDirectories(dir.resolve("root"))
        Files.writeString(root.resolve("plain.txt"), "needle")
        pipe = root.resolve("pipe.txt")
        val created = runCatching { ProcessBuilder("mkfifo", pipe.toString()).start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue(created)
    }

    @After
    fun tearDown() {
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun finishesInTime(action: () -> Unit) {
        var failure: Throwable? = null
        val worker = thread(isDaemon = true) {
            try {
                action()
            } catch (e: Throwable) {
                failure = e
            }
        }
        worker.join(20_000)
        assertFalse("the operation is blocked on the named pipe", worker.isAlive)
        failure?.let { throw it }
    }

    @Test
    fun `a named pipe is recognised and a plain file is not`() {
        assertTrue(isPipeOrDevice(pipe))
        assertFalse(isPipeOrDevice(root.resolve("plain.txt")))
    }

    @Test
    fun `content search skips a named pipe`() {
        val criteria = FileSearchCriteria(
            rootPath = root,
            namePattern = "*.txt",
            namePatternMode = NamePatternMode.GLOB,
            contentPattern = "needle",
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )
        val found = CopyOnWriteArrayList<Path>()

        finishesInTime { FileSearchService(criteria).search({ found.add(it.path) }, { false }, { _, _ -> }) }

        assertEquals(listOf(root.resolve("plain.txt")), found.toList())
    }

    @Test
    fun `copying a named pipe is refused`() {
        finishesInTime {
            assertThrows(IOException::class.java) {
                copyPreservingAttributes(pipe, dir.resolve("copy.txt"), replaceExisting = false)
            }
        }
    }

    @Test
    fun `zip reports a named pipe and packs the rest`() {
        val errors = CopyOnWriteArrayList<Path>()
        val archive = dir.resolve("out.zip")

        finishesInTime {
            runBlocking { ArchiveService().packZip(archive, listOf(root), false, { _, _ -> }, { path, _ -> errors.add(path) }, { false }) }
        }

        assertEquals(listOf(pipe), errors.toList())
        assertTrue(Files.exists(archive))
    }

    @Test
    fun `tar gz reports a named pipe and packs the rest`() {
        val errors = CopyOnWriteArrayList<Path>()
        val archive = dir.resolve("out.tar.gz")

        finishesInTime {
            runBlocking { ArchiveService().packTarGz(archive, listOf(root), { _, _ -> }, { path, _ -> errors.add(path) }, { false }) }
        }

        assertEquals(listOf(pipe), errors.toList())
        assertTrue(Files.exists(archive))
    }
}
