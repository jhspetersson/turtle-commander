package io.github.jhspetersson.turtlecommander.vfs

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
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
        base.toFile().deleteRecursively()
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

    private fun linkOrSkip(link: Path, target: String) {
        try {
            link.parent?.let { Files.createDirectories(it) }
            Files.createSymbolicLink(link, Path.of(target))
        } catch (e: Exception) {
            assumeNoException("symbolic link creation not permitted on this host", e)
        }
    }

    @Test
    fun `target routed through an in-tree link that climbs out escapes`() {
        linkOrSkip(base.resolve("sub").resolve("inner"), "..")
        assertTrue(symlinkTargetEscapes(base, base.resolve("outer"), "sub/inner/.."))
        assertTrue(symlinkTargetEscapes(base, base.resolve("outer"), "sub/inner/../x"))
    }

    @Test
    fun `target routed through an in-tree link that stays inside is allowed`() {
        linkOrSkip(base.resolve("sub").resolve("inner"), "..")
        assertFalse(symlinkTargetEscapes(base, base.resolve("outer"), "sub/inner"))
        assertFalse(symlinkTargetEscapes(base, base.resolve("outer"), "sub/inner/x"))
    }

    @Test
    fun `chain of in-tree links is allowed`() {
        Files.createDirectories(base.resolve("real"))
        linkOrSkip(base.resolve("a"), "real")
        linkOrSkip(base.resolve("b"), "a")
        assertFalse(symlinkTargetEscapes(base, base.resolve("c"), "b/file"))
    }

    @Test
    fun `link loop is refused instead of hanging`() {
        linkOrSkip(base.resolve("a"), "b")
        linkOrSkip(base.resolve("b"), "a")
        assertTrue(symlinkTargetEscapes(base, base.resolve("c"), "a/x"))
    }

    @Test
    fun `link whose own parent is an escaping link is refused`() {
        linkOrSkip(base.resolve("up"), "..")
        assertTrue(symlinkTargetEscapes(base, base.resolve("up").resolve("l"), "x"))
    }

    @Test
    fun `drive-relative target is refused on windows`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assertTrue(symlinkTargetEscapes(base, base.resolve("l"), "C:x"))
    }
}
