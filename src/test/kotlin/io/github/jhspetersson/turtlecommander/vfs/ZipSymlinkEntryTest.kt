package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ZipSymlinkEntryTest {

    private lateinit var dir: Path
    private lateinit var archive: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("zip-symlink-entry-")
        archive = dir.resolve("links.zip")
        ZipArchiveOutputStream(archive).use { zip ->
            val file = ZipArchiveEntry("bin/tool")
            file.unixMode = 0b1_000_000_111_101_101
            zip.putArchiveEntry(file)
            zip.write("#!/bin/sh".toByteArray())
            zip.closeArchiveEntry()
            val link = ZipArchiveEntry("bin/alias")
            link.unixMode = 0b1_010_000_111_111_111
            zip.putArchiveEntry(link)
            zip.write("tool".toByteArray())
            zip.closeArchiveEntry()
        }
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun entries(): Map<String, ZipArchiveEntry> =
        ZipFile.builder().setPath(archive).get().use { zip -> zip.entries.asSequence().associateBy { it.name } }

    private fun assertLinkAndModeKept() {
        val entries = entries()
        val link = entries.getValue("bin/alias")
        assertTrue("alias must still be a symlink entry, mode ${Integer.toOctalString(link.unixMode)}", link.isUnixSymlink)
        assertEquals(0b111_101_101, entries.getValue("bin/tool").unixMode and 0b111_111_111)
        assertTrue(entries.containsKey("added.txt"))
    }

    @Test
    fun `a zip with a symlink entry is opened by the extracting file system and keeps the link after an edit`() {
        ZipFileSystemProvider().create(archive).use { vfs ->
            assertTrue(vfs is ZipExtractVirtualFileSystem)
            Files.writeString(vfs.root.resolve("added.txt"), "x")
            vfs.flush()
        }

        assertLinkAndModeKept()
    }

    @Test
    fun `a zip without symlink entries is still opened by the jdk zip file system`() {
        val plain = dir.resolve("plain.zip")
        ZipArchiveOutputStream(plain).use { zip ->
            zip.putArchiveEntry(ZipArchiveEntry("a.txt"))
            zip.write("a".toByteArray())
            zip.closeArchiveEntry()
        }

        ZipFileSystemProvider().create(plain).use { vfs ->
            assertTrue(vfs is ZipVirtualFileSystem)
        }
    }

    @Test
    fun `a symlink entry stays a symlink after an edit through the extracting fallback`() {
        ZipExtractVirtualFileSystem(archive).use { vfs ->
            Files.writeString(vfs.root.resolve("added.txt"), "x")
            vfs.flush()
        }

        assertLinkAndModeKept()
    }
}
