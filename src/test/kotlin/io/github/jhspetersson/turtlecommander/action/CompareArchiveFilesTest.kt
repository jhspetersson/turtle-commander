package io.github.jhspetersson.turtlecommander.action

import io.github.jhspetersson.turtlecommander.vfs.TarVirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.ZipVirtualFileSystem
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CompareArchiveFilesTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("compare-archive-files-")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a lazy tar entry is read with its real content`() {
        val archive = dir.resolve("data.tar")
        TarArchiveOutputStream(Files.newOutputStream(archive)).use { out ->
            val bytes = "tar content".toByteArray()
            val entry = TarArchiveEntry("a.txt")
            entry.size = bytes.size.toLong()
            out.putArchiveEntry(entry)
            out.write(bytes)
            out.closeArchiveEntry()
        }

        TarVirtualFileSystem(archive, Files::newInputStream, Files::newOutputStream).use { vfs ->
            assertEquals("tar content", String(archiveFileBytes(vfs.root.resolve("a.txt"))!!))
        }
    }

    @Test
    fun `a zip entry is read through the zip file system`() {
        val archive = dir.resolve("data.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            zos.putNextEntry(ZipEntry("sub/b.txt"))
            zos.write("zip content".toByteArray())
            zos.closeEntry()
        }

        ZipVirtualFileSystem(archive).use { vfs ->
            assertEquals("zip content", String(archiveFileBytes(vfs.getPath("/sub/b.txt"))!!))
        }
    }

    @Test
    fun `a plain file is left to the local file system`() {
        val plain = dir.resolve("plain.txt")
        Files.writeString(plain, "plain")

        assertNull(archiveFileBytes(plain))
    }
}
