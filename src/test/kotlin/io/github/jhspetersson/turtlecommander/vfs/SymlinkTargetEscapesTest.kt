package io.github.jhspetersson.turtlecommander.vfs

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Platform-independent coverage for the symlink-target containment check that guards archive
 * extraction against the "symlink-then-child" write escape (and absolute-target links).
 */
class SymlinkTargetEscapesTest {

    private val base: Path = Files.createTempDirectory("symlink-escape-base-")

    @After
    fun cleanup() {
        Files.deleteIfExists(base)
    }

    @Test
    fun `absolute target outside the base escapes`() {
        val outside = base.parent.resolve("some-other-dir")
        assertTrue(symlinkTargetEscapes(base, base.resolve("link"), outside.toString()))
    }

    @Test
    fun `relative target climbing above the base escapes`() {
        val link = base.resolve("a").resolve("link")
        assertTrue(symlinkTargetEscapes(base, link, "../../../etc/passwd"))
    }

    @Test
    fun `relative target that stays within the base is allowed`() {
        val link = base.resolve("a").resolve("link")
        // base/a/link -> ../b  ==>  base/b, still inside base
        assertFalse(symlinkTargetEscapes(base, link, "../b"))
    }

    @Test
    fun `sibling target is allowed`() {
        val link = base.resolve("a").resolve("link")
        assertFalse(symlinkTargetEscapes(base, link, "sibling.txt"))
    }

    @Test
    fun `absolute target inside the base is allowed`() {
        val inside = base.resolve("nested").resolve("file")
        assertFalse(symlinkTargetEscapes(base, base.resolve("link"), inside.toString()))
    }
}
