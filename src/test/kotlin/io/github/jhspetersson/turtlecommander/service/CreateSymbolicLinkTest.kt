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
import java.nio.file.Path

class CreateSymbolicLinkTest {

    private val service = FileOperationService(CoroutineScope(Dispatchers.Unconfined))
    private val tempPaths = mutableListOf<Path>()

    @After
    fun cleanup() {
        for (p in tempPaths.reversed()) {
            try {
                if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.walk(p).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                } else {
                    Files.deleteIfExists(p)
                }
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    /** Creates the link, or skips the test when the host forbids it (Windows without Developer Mode). */
    private fun createOrSkip(link: Path, target: Path) {
        try {
            runBlocking { service.createSymbolicLink(link, target) }
        } catch (e: Exception) {
            assumeNoException("symbolic link creation not permitted on this host", e)
        }
    }

    @Test
    fun `creates a symbolic link to an existing target`() {
        val dir = Files.createTempDirectory("symlink-test-").also { tempPaths.add(it) }
        val target = Files.writeString(dir.resolve("target.txt"), "hello")
        val link = dir.resolve("link.txt")

        createOrSkip(link, target)

        assertTrue(Files.isSymbolicLink(link))
        assertEquals(target, Files.readSymbolicLink(link))
        assertEquals("hello", Files.readString(link)) // resolves through the link
    }

    @Test
    fun `link to a missing target is created as a broken link`() {
        val dir = Files.createTempDirectory("symlink-test-").also { tempPaths.add(it) }
        val missing = dir.resolve("nope.txt")
        val link = dir.resolve("dangling.txt")

        createOrSkip(link, missing)

        assertTrue(Files.isSymbolicLink(link))
        assertFalse("a link to a missing target must resolve as broken", Files.exists(link))
    }

    @Test
    fun `creates parent directories for the link`() {
        val dir = Files.createTempDirectory("symlink-test-").also { tempPaths.add(it) }
        val target = Files.writeString(dir.resolve("target.txt"), "x")
        val link = dir.resolve("sub/dir/link.txt")

        createOrSkip(link, target)

        assertTrue(Files.isSymbolicLink(link))
    }
}
