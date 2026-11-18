package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SpecialFileRepackTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("special-file-repack-")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun tar(): Path {
        val path = dir.resolve("dev.tar")
        TarArchiveOutputStream(Files.newOutputStream(path)).use { out ->
            val console = TarArchiveEntry("dev/console", TarConstants.LF_CHR)
            console.devMajor = 5
            console.devMinor = 1
            console.mode = 0b110_000_000
            out.putArchiveEntry(console)
            out.closeArchiveEntry()
            val disk = TarArchiveEntry("dev/sda", TarConstants.LF_BLK)
            disk.devMajor = 8
            disk.devMinor = 0
            out.putArchiveEntry(disk)
            out.closeArchiveEntry()
            out.putArchiveEntry(TarArchiveEntry("run/pipe", TarConstants.LF_FIFO))
            out.closeArchiveEntry()
            val bytes = "payload".toByteArray()
            val file = TarArchiveEntry("a.txt")
            file.size = bytes.size.toLong()
            out.putArchiveEntry(file)
            out.write(bytes)
            out.closeArchiveEntry()
        }
        return path
    }

    private fun tarEntries(path: Path): Map<String, TarArchiveEntry> =
        TarArchiveInputStream(Files.newInputStream(path)).use { tar ->
            generateSequence { tar.nextEntry }.associateBy { it.name.trimEnd('/') }
        }

    private fun cpio(): Path {
        val path = dir.resolve("dev.cpio")
        CpioArchiveOutputStream(Files.newOutputStream(path)).use { out ->
            val console = CpioArchiveEntry("dev/console")
            console.mode = (CpioConstants.C_ISCHR or 0b110_000_000).toLong()
            console.remoteDeviceMaj = 5
            console.remoteDeviceMin = 1
            out.putArchiveEntry(console)
            out.closeArchiveEntry()
            val pipe = CpioArchiveEntry("run/pipe")
            pipe.mode = (CpioConstants.C_ISFIFO or 0b110_100_100).toLong()
            out.putArchiveEntry(pipe)
            out.closeArchiveEntry()
            val body = "payload".toByteArray()
            val file = CpioArchiveEntry("a.txt")
            file.mode = (CpioConstants.C_ISREG or 0b110_100_100).toLong()
            file.size = body.size.toLong()
            out.putArchiveEntry(file)
            out.write(body)
            out.closeArchiveEntry()
        }
        return path
    }

    private fun cpioEntries(path: Path): Map<String, CpioArchiveEntry> =
        CpioArchiveInputStream(Files.newInputStream(path)).use { cpio ->
            generateSequence { cpio.nextEntry }.associateBy { it.name }
        }

    @Test
    fun `tar device nodes and fifos survive a repack`() {
        val archive = tar()

        TarVirtualFileSystem(archive, Files::newInputStream, Files::newOutputStream).use { vfs ->
            Files.writeString(vfs.root.resolve("a.txt"), "edited")
            vfs.flush()
        }

        val entries = tarEntries(archive)
        val console = entries.getValue("dev/console")
        assertTrue(console.isCharacterDevice)
        assertEquals(5, console.devMajor)
        assertEquals(1, console.devMinor)
        assertEquals(0b110_000_000, console.mode)
        val disk = entries.getValue("dev/sda")
        assertTrue(disk.isBlockDevice)
        assertEquals(8, disk.devMajor)
        assertTrue(entries.getValue("run/pipe").isFIFO)
        assertTrue(entries.getValue("a.txt").isFile)
    }

    @Test
    fun `a tar device node the user filled with content becomes a regular file`() {
        val archive = tar()

        TarVirtualFileSystem(archive, Files::newInputStream, Files::newOutputStream).use { vfs ->
            Files.writeString(vfs.root.resolve("dev/console"), "now a file")
            vfs.flush()
        }

        val console = tarEntries(archive).getValue("dev/console")
        assertTrue(console.isFile)
        assertEquals(10L, console.size)
    }

    @Test
    fun `cpio device nodes and fifos survive a repack`() {
        val archive = cpio()

        CpioVirtualFileSystem(archive).use { vfs ->
            Files.writeString(vfs.root.resolve("a.txt"), "edited")
            vfs.flush()
        }

        val entries = cpioEntries(archive)
        val console = entries.getValue("dev/console")
        assertTrue(console.isCharacterDevice)
        assertEquals(5L, console.remoteDeviceMaj)
        assertEquals(1L, console.remoteDeviceMin)
        assertEquals((CpioConstants.C_ISCHR or 0b110_000_000).toLong(), console.mode)
        assertTrue(entries.getValue("run/pipe").isPipe)
        assertTrue(entries.getValue("a.txt").isRegularFile)
    }
}
