package io.github.jhspetersson.turtlecommander.model

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class FileEntryTest {

    @Test
    fun testDataClassEquality() {
        val path = Path.of("/test/file.txt")
        val time = FileTime.fromMillis(1000)
        val a = FileEntry("file.txt", path, false, 100, lastModified = time, permissions = "rw-r--r--")
        val b = FileEntry("file.txt", path, false, 100, lastModified = time, permissions = "rw-r--r--")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun testDataClassInequality() {
        val time = FileTime.fromMillis(1000)
        val a = FileEntry("file.txt", Path.of("/a"), false, 100, lastModified = time, permissions = "rw-r--r--")
        val b = FileEntry("other.txt", Path.of("/b"), false, 100, lastModified = time, permissions = "rw-r--r--")
        assertNotEquals(a, b)
    }

    @Test
    fun testDefaultValues() {
        val entry = FileEntry("test", Path.of("/test"), false, 0, lastModified = null, permissions = "")
        assertFalse(entry.isParentLink)
    }

    @Test
    fun testParentLink() {
        val entry = FileEntry("..", Path.of("/"), true, 0, lastModified = null, permissions = "", isParentLink = true)
        assertTrue(entry.isParentLink)
        assertTrue(entry.isDirectory)
    }

    @Test
    fun testCopyWithModification() {
        val original = FileEntry("file.txt", Path.of("/test"), false, 100, lastModified = null, permissions = "rw-r--r--")
        val renamed = original.copy(name = "renamed.txt")
        assertEquals("renamed.txt", renamed.name)
        assertEquals(original.path, renamed.path)
        assertEquals(original.size, renamed.size)
    }
}
