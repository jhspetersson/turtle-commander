package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

class AlteredEntryNameTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("altered-entry-name-")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun tar(vararg names: String): Path {
        val path = dir.resolve("names.tar")
        TarArchiveOutputStream(Files.newOutputStream(path)).use { out ->
            for (name in names) {
                val bytes = "content of $name".toByteArray()
                val entry = TarArchiveEntry(name)
                entry.size = bytes.size.toLong()
                out.putArchiveEntry(entry)
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        return path
    }

    private fun assumeInvalidOnThisHost(name: String) {
        val invalid = try {
            dir.resolve(name)
            false
        } catch (_: InvalidPathException) {
            true
        }
        assumeTrue("\"$name\" is a valid file name on this host", invalid)
    }

    @Test
    fun `ordinary names are not altered`() {
        assertFalse(entryNameIsAltered(dir, "docs/readme.txt"))
        assertFalse(entryNameIsAltered(dir, "docs/"))
        assertFalse(entryNameIsAltered(dir, "./"))
    }

    @Test
    fun `an archive with a name this host cannot represent opens read-only`() {
        assumeInvalidOnThisHost("what?.txt")
        val archive = tar("what?.txt", "a.txt")
        val original = Files.readAllBytes(archive)

        TarVirtualFileSystem(archive, Files::newInputStream, Files::newOutputStream).use { vfs ->
            assertTrue(vfs.isReadOnly)
            assertEquals(listOf("what?.txt"), vfs.unextractableEntries)
            val shown = vfs.root.resolve("what_.txt")
            vfs.materialize(shown)
            assertEquals("content of what?.txt", Files.readString(shown))
            Files.writeString(vfs.root.resolve("a.txt"), "edited")
            vfs.flush()
        }

        assertArrayEquals(original, Files.readAllBytes(archive))
    }

    @Test
    fun `an archive with only representable names stays writable`() {
        val archive = tar("plain.txt", "a.txt")

        TarVirtualFileSystem(archive, Files::newInputStream, Files::newOutputStream).use { vfs ->
            assertFalse(vfs.isReadOnly)
            assertTrue(vfs.unextractableEntries.isEmpty())
        }
    }
}
