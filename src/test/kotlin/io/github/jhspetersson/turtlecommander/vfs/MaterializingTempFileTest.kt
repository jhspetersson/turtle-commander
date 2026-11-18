package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MaterializingTempFileTest {

    private lateinit var dir: Path
    private lateinit var archive: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("materializing-temp-file-")
        archive = dir.resolve("data.tar")
        TarArchiveOutputStream(Files.newOutputStream(archive)).use { out ->
            for (name in listOf("a.txt", "sub/b.txt")) {
                val bytes = "content of $name".toByteArray()
                val entry = TarArchiveEntry(name)
                entry.size = bytes.size.toLong()
                out.putArchiveEntry(entry)
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun open() = TarVirtualFileSystem(archive, Files::newInputStream, Files::newOutputStream)

    @Test
    fun `a materialization in progress does not make the archive look modified`() {
        val original = Files.readAllBytes(archive)

        open().use { vfs ->
            Files.writeString(vfs.root.resolve("sub").resolve("${MATERIALIZING_TEMP_PREFIX}123.tmp"), "half written")
            vfs.flush()
            assertEquals("content of a.txt", Files.readString(vfs.root.resolve("a.txt").also { vfs.materialize(it) }))
        }

        assertArrayEquals(original, Files.readAllBytes(archive))
    }

    @Test
    fun `a materialization temp file is never written into the archive`() {
        open().use { vfs ->
            Files.writeString(vfs.root.resolve("${MATERIALIZING_TEMP_PREFIX}123.tmp"), "half written")
            Files.writeString(vfs.root.resolve("new.txt"), "x")
            vfs.flush()
        }

        val names = TarArchiveInputStream(Files.newInputStream(archive)).use { tar ->
            generateSequence { tar.nextEntry }.filter { !it.isDirectory }.map { it.name }.toSet()
        }
        assertEquals(setOf("a.txt", "sub/b.txt", "new.txt"), names)
    }

    @Test
    fun `materializing leaves the archive unmodified on the next flush`() {
        val original = Files.readAllBytes(archive)

        open().use { vfs ->
            vfs.materializeAll(listOf(vfs.root.resolve("a.txt"), vfs.root.resolve("sub/b.txt")))
            vfs.flush()
        }

        assertArrayEquals(original, Files.readAllBytes(archive))
    }
}
