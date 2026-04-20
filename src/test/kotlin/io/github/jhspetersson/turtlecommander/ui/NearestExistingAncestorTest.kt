package io.github.jhspetersson.turtlecommander.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class NearestExistingAncestorTest {

    @Test
    fun `returns immediate parent when it exists`() {
        val root = Path.of("/a")
        val path = Path.of("/a/b")
        val exists: (Path) -> Boolean = { it == root }

        val result = FileTab.nearestExistingAncestor(path, exists)

        assertEquals(root, result)
    }

    @Test
    fun `walks past deleted ancestors to the first surviving directory`() {
        // Scenario from the bug: panel sat in /root/test/test2/test3 while the
        // other panel deleted /root/test. Only /root survives, so navigation
        // should fall back to it. (Using POSIX paths keeps the test portable —
        // Path.of on Linux parses "C:\\..." as a relative filename.)
        val surviving = Path.of("/root")
        val dead = setOf(
            Path.of("/root/test"),
            Path.of("/root/test/test2"),
            Path.of("/root/test/test2/test3"),
        )
        val path = Path.of("/root/test/test2/test3")
        val exists: (Path) -> Boolean = { it !in dead && it == surviving }

        val result = FileTab.nearestExistingAncestor(path, exists)

        assertEquals(surviving, result)
    }

    @Test
    fun `returns null when even the root is unavailable`() {
        val path = Path.of("/gone/deeper/leaf")
        val exists: (Path) -> Boolean = { false }

        val result = FileTab.nearestExistingAncestor(path, exists)

        assertNull(result)
    }

    @Test
    fun `returns null when starting path has no parent`() {
        // Roots (e.g. "/" or "C:\") have a null parent, so the walk terminates
        // immediately. The caller then surfaces the original error.
        val root = Path.of("/")
        val exists: (Path) -> Boolean = { true }

        val result = FileTab.nearestExistingAncestor(root, exists)

        assertNull(result)
    }

    @Test
    fun `does not test the starting path itself`() {
        // navigateTo only calls this after listFiles(path) already failed, so
        // testing `path` would either be a wasted syscall or, worse, loop on a
        // directory that exists but can't be listed (permissions).
        val path = Path.of("/a/b")
        val tested = mutableListOf<Path>()
        val exists: (Path) -> Boolean = { candidate ->
            tested.add(candidate)
            true
        }

        FileTab.nearestExistingAncestor(path, exists)

        assert(path !in tested) { "starting path should not be probed, got $tested" }
    }

    @Test
    fun `skips ancestor that throws and continues walking`() {
        // Mirrors the production wrapper which swallows exceptions from
        // Files.isDirectory (e.g. a transient I/O error on one ancestor
        // shouldn't abort the whole walk).
        val good = Path.of("/a")
        val path = Path.of("/a/bad/leaf")
        val exists: (Path) -> Boolean = { candidate ->
            when (candidate) {
                Path.of("/a/bad") -> false
                good -> true
                else -> false
            }
        }

        val result = FileTab.nearestExistingAncestor(path, exists)

        assertEquals(good, result)
    }

    @Test
    fun `resolves against real filesystem after deletion`() {
        val base = Files.createTempDirectory("nearest-ancestor-")
        try {
            val leaf = base.resolve("a").resolve("b").resolve("c")
            Files.createDirectories(leaf)
            // Delete the whole "a" subtree so only `base` survives.
            base.resolve("a").toFile().deleteRecursively()

            val result = FileTab.nearestExistingAncestor(leaf) { Files.isDirectory(it) }

            assertEquals(base, result)
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
