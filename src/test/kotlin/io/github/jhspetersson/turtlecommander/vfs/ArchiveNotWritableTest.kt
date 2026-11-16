package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveNotWritableTest {

    private lateinit var dir: Path
    private val locked = mutableListOf<Path>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("archive-not-writable-")
    }

    @After
    fun tearDown() {
        locked.forEach { it.toFile().setWritable(true) }
        dir.toFile().deleteRecursively()
    }

    private fun tar(name: String): Path {
        val path = dir.resolve(name)
        TarArchiveOutputStream(Files.newOutputStream(path)).use { out ->
            val bytes = "payload".toByteArray()
            val entry = TarArchiveEntry("a.txt")
            entry.size = bytes.size.toLong()
            out.putArchiveEntry(entry)
            out.write(bytes)
            out.closeArchiveEntry()
        }
        return path
    }

    private fun zip(name: String): Path {
        val path = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            out.putNextEntry(ZipEntry("a.txt"))
            out.write("payload".toByteArray())
            out.closeEntry()
        }
        return path
    }

    private fun lock(path: Path) {
        assertTrue(path.toFile().setWritable(false))
        locked.add(path)
        assumeFalse(Files.isWritable(path))
    }

    @Test
    fun `a writable tar opens writable`() {
        TarVirtualFileSystem(tar("plain.tar"), Files::newInputStream, Files::newOutputStream).use { vfs ->
            assertFalse(vfs.isReadOnly)
        }
    }

    @Test
    fun `a tar that cannot be rewritten opens read-only and is left untouched by a flush`() {
        val archive = tar("locked.tar")
        val original = Files.readAllBytes(archive)
        lock(archive)

        TarVirtualFileSystem(archive, Files::newInputStream, Files::newOutputStream).use { vfs ->
            assertTrue(vfs.isReadOnly)
            Files.writeString(vfs.root.resolve("new.txt"), "x")
            vfs.flush()
        }

        assertArrayEquals(original, Files.readAllBytes(archive))
    }

    @Test
    fun `a writable zip opens writable`() {
        ZipVirtualFileSystem(zip("plain.zip")).use { vfs ->
            assertFalse(vfs.isReadOnly)
        }
    }

    @Test
    fun `a zip that cannot be rewritten opens read-only`() {
        val archive = zip("locked.zip")
        lock(archive)

        ZipVirtualFileSystem(archive).use { vfs ->
            assertTrue(vfs.isReadOnly)
        }
    }
}
