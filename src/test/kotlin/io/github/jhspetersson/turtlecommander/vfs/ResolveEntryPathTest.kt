package io.github.jhspetersson.turtlecommander.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
