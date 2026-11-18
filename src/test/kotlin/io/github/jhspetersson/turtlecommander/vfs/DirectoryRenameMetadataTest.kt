package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioConstants
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DirectoryRenameMetadataTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("directory-rename-metadata-")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `renameEntryKeys moves the entry and everything under it`() {
        val map = mutableMapOf("bin" to 1, "bin/run.sh" to 2, "bin/deep/x" to 3, "binary" to 4, "other/bin/y" to 5)

        map.renameEntryKeys("bin", "tools")

        assertEquals(
            mapOf("tools" to 1, "tools/run.sh" to 2, "tools/deep/x" to 3, "binary" to 4, "other/bin/y" to 5),
            map,
        )
    }

    @Test
    fun `tar children keep mode and owner when their directory is renamed`() = runBlocking {
        val archive = dir.resolve("tree.tar")
        TarArchiveOutputStream(Files.newOutputStream(archive)).use { out ->
            val directory = TarArchiveEntry("bin/")
            directory.mode = 0b111_101_000
            out.putArchiveEntry(directory)
            out.closeArchiveEntry()
            val bytes = "#!/bin/sh".toByteArray()
            val file = TarArchiveEntry("bin/run.sh")
            file.mode = 0b111_101_101
            file.userName = "alice"
            file.groupName = "devs"
            file.setUserId(1234)
            file.size = bytes.size.toLong()
            out.putArchiveEntry(file)
            out.write(bytes)
            out.closeArchiveEntry()
            val link = TarArchiveEntry("bin/mtab", TarArchiveEntry.LF_SYMLINK)
            link.linkName = "/proc/mounts"
            out.putArchiveEntry(link)
            out.closeArchiveEntry()
        }

        TarVirtualFileSystem(archive, Files::newInputStream, Files::newOutputStream).use { vfs ->
            vfs.renameFile(vfs.root.resolve("bin"), "tools")
            assertEquals("rwxr-xr-x", vfs.entryMetadata(vfs.root.resolve("tools/run.sh"))?.permissions)
        }

        val entries = TarArchiveInputStream(Files.newInputStream(archive)).use { tar ->
            generateSequence { tar.nextEntry }.associateBy { it.name.trimEnd('/') }
        }
        assertEquals(setOf("tools", "tools/run.sh", "tools/mtab"), entries.keys)
        val run = entries.getValue("tools/run.sh")
        assertEquals(0b111_101_101, run.mode)
        assertEquals("alice", run.userName)
        assertEquals("devs", run.groupName)
        assertEquals(1234L, run.longUserId)
        assertEquals(0b111_101_000, entries.getValue("tools").mode and 0b111_111_111)
        assertEquals("/proc/mounts", entries.getValue("tools/mtab").linkName)
    }

    @Test
    fun `cpio children keep mode and owner when their directory is renamed`() = runBlocking {
        val archive = dir.resolve("tree.cpio")
        val fileMode = (CpioConstants.C_ISREG or 0b111_101_101).toLong()
        CpioArchiveOutputStream(Files.newOutputStream(archive)).use { out ->
            val directory = CpioArchiveEntry("bin")
            directory.mode = (CpioConstants.C_ISDIR or 0b111_101_000).toLong()
            out.putArchiveEntry(directory)
            out.closeArchiveEntry()
            val bytes = "#!/bin/sh".toByteArray()
            val file = CpioArchiveEntry("bin/run.sh")
            file.mode = fileMode
            file.setUID(1234)
            file.setGID(4321)
            file.size = bytes.size.toLong()
            out.putArchiveEntry(file)
            out.write(bytes)
            out.closeArchiveEntry()
        }

        CpioVirtualFileSystem(archive).use { vfs ->
            vfs.renameFile(vfs.root.resolve("bin"), "tools")
        }

        val entries = CpioArchiveInputStream(Files.newInputStream(archive)).use { cpio ->
            generateSequence { cpio.nextEntry }.associateBy { it.name }
        }
        assertNull(entries["bin/run.sh"])
        val run = entries.getValue("tools/run.sh")
        assertEquals(fileMode, run.mode)
        assertEquals(1234L, run.getUID())
        assertEquals(4321L, run.getGID())
    }

    @Test
    fun `7z children keep their attributes when their directory is renamed`() = runBlocking {
        val archive = dir.resolve("tree.7z")
        val attributes = 0x8000 or (0b111_101_101 shl 16) or 0x20
        SevenZOutputFile(archive.toFile()).use { out ->
            val directory = SevenZArchiveEntry().apply {
                name = "bin"
                isDirectory = true
            }
            out.putArchiveEntry(directory)
            out.closeArchiveEntry()
            val file = SevenZArchiveEntry().apply {
                name = "bin/run.sh"
                size = 3
                hasWindowsAttributes = true
                windowsAttributes = attributes
            }
            out.putArchiveEntry(file)
            out.write("abc".toByteArray())
            out.closeArchiveEntry()
        }

        SevenZipVirtualFileSystem(archive).use { vfs ->
            vfs.renameFile(vfs.root.resolve("bin"), "tools")
        }

        val run = SevenZFile.builder().setPath(archive).get().use { z ->
            generateSequence { z.nextEntry }.first { it.name == "tools/run.sh" }
        }
        assertTrue(run.hasWindowsAttributes)
        assertEquals(attributes, run.windowsAttributes)
    }
}
