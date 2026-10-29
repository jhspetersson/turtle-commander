package io.github.jhspetersson.turtlecommander.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class RenameCollisionTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("rename-collision-")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `no collision when the target does not exist`() {
        val source = Files.writeString(dir.resolve("a.txt"), "a")
        assertNull(detectRenameCollision(source, dir.resolve("z.txt"), "z.txt"))
    }

    @Test
    fun `existing file target is a collision that can be overwritten`() {
        val source = Files.writeString(dir.resolve("a.txt"), "a")
        val target = Files.writeString(dir.resolve("b.txt"), "b")
        val collision = detectRenameCollision(source, target, "b.txt")
        assertNotNull(collision)
        assertEquals(target, collision!!.target)
        assertEquals("b.txt", collision.newName)
        assertFalse(collision.targetIsDirectory)
        assertTrue(collision.canOverwrite)
    }

    @Test
    fun `empty directory target can be overwritten`() {
        val source = Files.writeString(dir.resolve("a.txt"), "a")
        val target = Files.createDirectory(dir.resolve("b"))
        val collision = detectRenameCollision(source, target, "b")
        assertNotNull(collision)
        assertTrue(collision!!.targetIsDirectory)
        assertTrue(collision.canOverwrite)
    }

    @Test
    fun `non-empty directory target cannot be overwritten`() {
        val source = Files.writeString(dir.resolve("a.txt"), "a")
        val target = Files.createDirectory(dir.resolve("b"))
        Files.writeString(target.resolve("child.txt"), "c")
        val collision = detectRenameCollision(source, target, "b")
        assertNotNull(collision)
        assertTrue(collision!!.targetIsDirectory)
        assertFalse(collision.canOverwrite)
    }

    @Test
    fun `case-only rename is never a collision`() {
        val source = Files.writeString(dir.resolve("Readme.txt"), "r")
        assertNull(detectRenameCollision(source, dir.resolve("README.TXT"), "README.TXT"))
    }
}
