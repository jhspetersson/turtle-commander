package io.github.jhspetersson.turtlecommander.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class ResolveEntryPathTest {

    private val base: Path = Path.of("base").toAbsolutePath()

    @Test
    fun `plain and nested names resolve under the base`() {
        assertEquals(base.resolve("a.txt"), resolveEntryPath(base, "a.txt"))
        assertEquals(base.resolve("dir").resolve("a.txt"), resolveEntryPath(base, "dir/a.txt"))
        assertEquals(base.resolve("dir"), resolveEntryPath(base, "dir/"))
        assertEquals(base.resolve("a.txt"), resolveEntryPath(base, "./a.txt")?.normalize())
    }

    @Test
    fun `names windows would store under another name are reported as altered`() {
        for (name in listOf("notes.", "notes ", "dir./a.txt", "dir /a.txt", "NUL", "nul.txt", "sub/COM1", "lpt9.log", "aux")) {
            assertTrue(name, windowsAltersName(name))
            assertTrue(name, entryNameIsAltered(base, name, isWindows = true))
        }
        for (name in listOf("notes.txt", "./a.txt", "../a.txt", "a/./b", "console", "com10", "nullable.txt", ".hidden", "a..b")) {
            assertFalse(name, windowsAltersName(name))
        }
    }

    @Test
    fun `names only windows alters are left alone elsewhere`() {
        assertFalse(entryNameIsAltered(base, "notes.", isWindows = false))
        assertFalse(entryNameIsAltered(base, "NUL", isWindows = false))
    }

    @Test
    fun `names that climb out of the base escape and names of the base itself do not`() {
        assertTrue(entryNameEscapes(base, "../x"))
        assertTrue(entryNameEscapes(base, "a/../../x"))
        assertFalse(entryNameEscapes(base, ""))
        assertFalse(entryNameEscapes(base, "./"))
        assertFalse(entryNameEscapes(base, "a/.."))
        assertFalse(entryNameEscapes(base, "a.txt"))
        assertFalse(entryNameEscapes(base, "./a.txt"))
    }

    @Test
    fun `names that denote the base directory itself are rejected`() {
        assertNull(resolveEntryPath(base, ""))
        assertNull(resolveEntryPath(base, "/"))
        assertNull(resolveEntryPath(base, "."))
        assertNull(resolveEntryPath(base, "./"))
    }

    @Test
    fun `names that climb out of the base are rejected`() {
        assertNull(resolveEntryPath(base, "../x"))
        assertNull(resolveEntryPath(base, "a/../../x"))
        assertNull(resolveEntryPath(base, ".."))
    }
}
