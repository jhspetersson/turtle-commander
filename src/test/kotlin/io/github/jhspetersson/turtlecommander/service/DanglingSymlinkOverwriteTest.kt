package io.github.jhspetersson.turtlecommander.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class DanglingSymlinkOverwriteTest {

    private val service = FileOperationService(CoroutineScope(Dispatchers.Unconfined))
    private val tempPaths = mutableListOf<Path>()

    @After
    fun cleanup() {
        for (p in tempPaths.reversed()) {
            try {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    Files.walk(p).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                } else {
                    Files.deleteIfExists(p)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun tempDir(prefix: String): Path =
        Files.createTempDirectory(prefix).also { tempPaths.add(it) }

    private fun createDanglingLinkOrSkip(link: Path) {
        try {
            Files.createSymbolicLink(link, link.resolveSibling("missing-target.txt"))
        } catch (e: Exception) {
            assumeNoException("symbolic link creation not permitted on this host", e)
        }
    }

    private class Run(val prompted: List<Path>, val errors: List<Pair<Path, Exception>>)

    private fun copy(source: Path, destination: Path, response: OverwriteResponse): Run = runBlocking {
        val prompted = mutableListOf<Path>()
        val errors = mutableListOf<Pair<Path, Exception>>()
        service.copyFilesWithProgress(
            sources = listOf(source),
            destination = destination,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { prompted.add(it); response },
            onError = { path, e -> errors.add(path to e) },
            isCancelled = { false },
        )
        Run(prompted, errors)
    }

    private fun move(source: Path, destination: Path, response: OverwriteResponse): Run = runBlocking {
        val prompted = mutableListOf<Path>()
        val errors = mutableListOf<Pair<Path, Exception>>()
        service.moveFilesWithProgress(
            sources = listOf(source),
            destination = destination,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { prompted.add(it); response },
            onError = { path, e -> errors.add(path to e) },
            isCancelled = { false },
        )
        Run(prompted, errors)
    }

    @Test
    fun `copy onto a dangling symlink prompts and replaces the link on overwrite`() {
        val src = tempDir("dangling-copy-src-")
        val source = Files.writeString(src.resolve("file.txt"), "payload")
        val dst = tempDir("dangling-copy-dst-")
        val target = dst.resolve("file.txt")
        createDanglingLinkOrSkip(target)

        val run = copy(source, dst, OverwriteResponse.OVERWRITE)

        assertTrue("copy reported errors: ${run.errors}", run.errors.isEmpty())
        assertEquals(listOf(target), run.prompted)
        assertFalse("target must no longer be a symlink", Files.isSymbolicLink(target))
        assertTrue(Files.isRegularFile(target))
        assertEquals("payload", Files.readString(target))
        assertEquals("payload", Files.readString(source))
    }

    @Test
    fun `copy onto a dangling symlink leaves the link alone on skip`() {
        val src = tempDir("dangling-copy-src-")
        val source = Files.writeString(src.resolve("file.txt"), "payload")
        val dst = tempDir("dangling-copy-dst-")
        val target = dst.resolve("file.txt")
        createDanglingLinkOrSkip(target)

        val run = copy(source, dst, OverwriteResponse.SKIP)

        assertTrue("copy reported errors: ${run.errors}", run.errors.isEmpty())
        assertEquals(listOf(target), run.prompted)
        assertTrue("target must still be the dangling link", Files.isSymbolicLink(target))
    }

    @Test
    fun `move onto a dangling symlink prompts and replaces the link on overwrite`() {
        val src = tempDir("dangling-move-src-")
        val source = Files.writeString(src.resolve("file.txt"), "payload")
        val dst = tempDir("dangling-move-dst-")
        val target = dst.resolve("file.txt")
        createDanglingLinkOrSkip(target)

        val run = move(source, dst, OverwriteResponse.OVERWRITE)

        assertTrue("move reported errors: ${run.errors}", run.errors.isEmpty())
        assertEquals(listOf(target), run.prompted)
        assertFalse("target must no longer be a symlink", Files.isSymbolicLink(target))
        assertTrue(Files.isRegularFile(target))
        assertEquals("payload", Files.readString(target))
        assertFalse("source must be gone after the move", Files.exists(source, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `move onto a dangling symlink leaves both sides alone on skip`() {
        val src = tempDir("dangling-move-src-")
        val source = Files.writeString(src.resolve("file.txt"), "payload")
        val dst = tempDir("dangling-move-dst-")
        val target = dst.resolve("file.txt")
        createDanglingLinkOrSkip(target)

        val run = move(source, dst, OverwriteResponse.SKIP)

        assertTrue("move reported errors: ${run.errors}", run.errors.isEmpty())
        assertEquals(listOf(target), run.prompted)
        assertTrue("target must still be the dangling link", Files.isSymbolicLink(target))
        assertEquals("payload", Files.readString(source))
    }
}
