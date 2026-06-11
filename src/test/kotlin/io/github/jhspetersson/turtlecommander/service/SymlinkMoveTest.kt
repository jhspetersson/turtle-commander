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

/**
 * Regression tests for moving symbolic links to directories. `Path.isDirectory()` follows
 * links, so the move loop used to recurse *through* a dir link — moving the linked target's
 * contents to the destination and deleting the link, leaving the target directory drained.
 * The fix moves link nodes as files (same guard the copy path always had).
 */
class SymlinkMoveTest {

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
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    private fun tempDir(prefix: String): Path =
        Files.createTempDirectory(prefix).also { tempPaths.add(it) }

    /** Creates the link, or skips the test when the host forbids it (Windows without Developer Mode). */
    private fun createLinkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: Exception) {
            assumeNoException("symbolic link creation not permitted on this host", e)
        }
    }

    private fun move(sources: List<Path>, destination: Path) = runBlocking {
        val errors = mutableListOf<Pair<Path, Exception>>()
        service.moveFilesWithProgress(
            sources = sources,
            destination = destination,
            initialPolicy = OverwritePolicy.OVERWRITE_ALL,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.OVERWRITE_ALL },
            onError = { path, e -> errors.add(path to e) },
            isCancelled = { false },
        )
        assertTrue("move reported errors: $errors", errors.isEmpty())
    }

    @Test
    fun `moving a directory containing a dir symlink moves the link node and keeps the target intact`() {
        val linkedTarget = tempDir("symlink-move-target-")
        Files.writeString(linkedTarget.resolve("data.txt"), "payload")

        val src = tempDir("symlink-move-src-")
        Files.writeString(src.resolve("regular.txt"), "regular")
        createLinkOrSkip(src.resolve("linkdir"), linkedTarget)

        val destParent = tempDir("symlink-move-dst-")
        move(listOf(src), destParent)

        // The linked target must be untouched — the drain bug moved its contents away.
        assertTrue("linked target directory must survive the move", Files.isDirectory(linkedTarget))
        assertEquals("payload", Files.readString(linkedTarget.resolve("data.txt")))

        // The moved tree contains the link as a link, still pointing at the original target.
        val movedLink = destParent.resolve(src.fileName.toString()).resolve("linkdir")
        assertTrue("moved entry must still be a symlink", Files.isSymbolicLink(movedLink))
        assertEquals(linkedTarget, Files.readSymbolicLink(movedLink))
        assertEquals("regular", Files.readString(destParent.resolve(src.fileName.toString()).resolve("regular.txt")))

        // The source tree is gone (the move emptied and removed it).
        assertFalse("source directory must be removed after the move", Files.exists(src, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `moving a top-level dir symlink moves the link node and keeps the target intact`() {
        val linkedTarget = tempDir("symlink-move-target-")
        Files.writeString(linkedTarget.resolve("data.txt"), "payload")

        val srcParent = tempDir("symlink-move-src-")
        val link = srcParent.resolve("linkdir")
        createLinkOrSkip(link, linkedTarget)

        val destParent = tempDir("symlink-move-dst-")
        move(listOf(link), destParent)

        assertTrue("linked target directory must survive the move", Files.isDirectory(linkedTarget))
        assertEquals("payload", Files.readString(linkedTarget.resolve("data.txt")))

        val movedLink = destParent.resolve("linkdir")
        assertTrue("moved entry must still be a symlink", Files.isSymbolicLink(movedLink))
        assertEquals(linkedTarget, Files.readSymbolicLink(movedLink))
        assertFalse("source link must be removed after the move", Files.exists(link, LinkOption.NOFOLLOW_LINKS))
    }
}
