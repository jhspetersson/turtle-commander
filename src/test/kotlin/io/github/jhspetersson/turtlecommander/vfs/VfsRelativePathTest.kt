package io.github.jhspetersson.turtlecommander.vfs

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class VfsRelativePathTest {

    @Test
    fun `relative path with matching root`() {
        val root = Path.of("/tmp/turtle-tar-abc123")
        val path = Path.of("/tmp/turtle-tar-abc123/subdir/file.txt")
        val result = vfsRelativePath(root, path)
        assertEquals("subdir/file.txt", result.replace('\\', '/'))
    }

    @Test
    fun `relative path when path equals root`() {
        val root = Path.of("/tmp/turtle-tar-abc123")
        val path = Path.of("/tmp/turtle-tar-abc123")
        val result = vfsRelativePath(root, path)
        assertEquals("", result)
    }

    @Test
    fun `relative path with trailing separator on root`() {
        val root = Path.of("/tmp/turtle-tar-abc123")
        val path = Path.of("/tmp/turtle-tar-abc123/file.txt")
        val result = vfsRelativePath(root, path)
        assertEquals("file.txt", result)
    }

    @Test
    fun `relative path with non-matching prefix should not produce wrong result`() {
        // e.g. root = /tmp/turtle-tar-abc and path = /tmp/turtle-tar-abcdef/file.txt
        // string-based comparison would incorrectly match, Path.relativize won't
        val root = Path.of("/tmp/turtle-tar-abc")
        val path = Path.of("/tmp/turtle-tar-abcdef/file.txt")
        val result = vfsRelativePath(root, path)
        // Should NOT return "def/file.txt" — this is a different directory
        assertNotEquals("def/file.txt", result.replace('\\', '/'))
    }
}
