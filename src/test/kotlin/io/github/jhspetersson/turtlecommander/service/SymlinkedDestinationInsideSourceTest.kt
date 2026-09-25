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

class SymlinkedDestinationInsideSourceTest {

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

    private fun sourceTree(root: Path): Path {
        val src = Files.createDirectory(root.resolve("src"))
        val sub = Files.createDirectory(src.resolve("sub"))
        Files.writeString(sub.resolve("a.txt"), "a")
        return src
    }

    private fun linkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: Exception) {
            assumeNoException("symbolic link creation not permitted on this host", e)
        }
    }

    private class Run(val errors: List<Path>, val ticks: Int)

    private fun copy(source: Path, destination: Path): Run = runBlocking {
        val errors = mutableListOf<Path>()
        var ticks = 0
        service.copyFilesWithProgress(
            sources = listOf(source),
            destination = destination,
            initialPolicy = OverwritePolicy.OVERWRITE_ALL,
            onProgress = { _, _ -> ticks++ },
            onOverwriteConfirm = { OverwriteResponse.OVERWRITE_ALL },
            onError = { path, _ -> errors.add(path) },
            isCancelled = { false },
        )
        Run(errors, ticks)
    }

    private fun move(source: Path, destination: Path): Run = runBlocking {
        val errors = mutableListOf<Path>()
        var ticks = 0
        service.moveFilesWithProgress(
            sources = listOf(source),
            destination = destination,
            initialPolicy = OverwritePolicy.OVERWRITE_ALL,
            onProgress = { _, _ -> ticks++ },
            onOverwriteConfirm = { OverwriteResponse.OVERWRITE_ALL },
            onError = { path, _ -> errors.add(path) },
            isCancelled = { false },
        )
        Run(errors, ticks)
    }

    @Test
    fun `copying into a symlink that points inside the source is rejected`() {
        val root = tempDir("link-into-self-copy-")
        val src = sourceTree(root)
        val dst = root.resolve("dst")
        linkOrSkip(dst, src.resolve("sub"))

        val run = copy(src, dst)

        assertEquals("the self-referential source must be reported once", listOf(src), run.errors)
        assertEquals("nothing should be copied", 0, run.ticks)
        assertFalse("no nested copy may appear inside the source", Files.exists(src.resolve("sub").resolve("src")))
    }

    @Test
    fun `moving into a symlink that points at the source is rejected and leaves the source intact`() {
        val root = tempDir("link-into-self-move-")
        val src = sourceTree(root)
        val dst = root.resolve("dst")
        linkOrSkip(dst, src)

        val run = move(src, dst)

        assertEquals("the self-referential source must be reported once", listOf(src), run.errors)
        assertEquals("nothing should be moved", 0, run.ticks)
        assertTrue("the source tree must survive", Files.exists(src.resolve("sub").resolve("a.txt")))
        assertFalse("no nested copy may appear inside the source", Files.exists(src.resolve("src")))
    }

    @Test
    fun `copying into a symlink that points outside the source still works`() {
        val root = tempDir("link-outside-copy-")
        val src = sourceTree(root)
        val real = Files.createDirectory(root.resolve("real"))
        val dst = root.resolve("dst")
        linkOrSkip(dst, real)

        val run = copy(src, dst)

        assertTrue("no errors expected", run.errors.isEmpty())
        assertTrue("the tree lands behind the link", Files.exists(real.resolve("src").resolve("sub").resolve("a.txt")))
    }
}
