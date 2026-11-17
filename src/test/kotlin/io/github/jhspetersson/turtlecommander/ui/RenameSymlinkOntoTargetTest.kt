package io.github.jhspetersson.turtlecommander.ui

import io.github.jhspetersson.turtlecommander.util.isSameDirectoryEntry
import io.github.jhspetersson.turtlecommander.util.renameInPlace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

class RenameSymlinkOntoTargetTest {

    private lateinit var dir: Path
    private lateinit var target: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("rename-symlink-onto-target-")
        target = Files.writeString(dir.resolve("target.txt"), "precious")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun symlink(name: String): Path {
        val link = dir.resolve(name)
        assumeTrue(
            "symbolic link creation not permitted on this host",
            runCatching { Files.createSymbolicLink(link, target.fileName) }.isSuccess,
        )
        return link
    }

    @Test
    fun `a symlink and its target are different entries`() {
        val link = symlink("link.txt")

        assertTrue(Files.isSameFile(link, target))
        assertFalse(isSameDirectoryEntry(link, target))
    }

    @Test
    fun `renaming a symlink to its target's name is a collision`() {
        val link = symlink("link.txt")

        assertNotNull(detectRenameCollision(link, target, "target.txt"))
    }

    @Test
    fun `renaming a symlink onto its target leaves the target intact`() {
        val link = symlink("link.txt")

        try {
            renameInPlace(link, target)
            fail("Expected FileAlreadyExistsException")
        } catch (_: FileAlreadyExistsException) {
        }

        assertFalse(Files.isSymbolicLink(target))
        assertEquals("precious", Files.readString(target))
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun `two symlinks to the same file are different entries`() {
        val first = symlink("first.txt")
        val second = symlink("second.txt")

        assertFalse(isSameDirectoryEntry(first, second))
        assertNotNull(detectRenameCollision(first, second, "second.txt"))
    }

    @Test
    fun `a path is the same entry as itself and as its other spelling when the host ignores case`() {
        val upper = dir.resolve("TARGET.TXT")

        assertTrue(isSameDirectoryEntry(target, target))
        assumeTrue("host file system is case-sensitive", Files.exists(upper))
        assertTrue(isSameDirectoryEntry(target, upper))
        assertNull(detectRenameCollision(target, upper, "TARGET.TXT"))
        renameInPlace(target, upper)
        assertEquals(listOf("TARGET.TXT"), Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() })
    }
}
