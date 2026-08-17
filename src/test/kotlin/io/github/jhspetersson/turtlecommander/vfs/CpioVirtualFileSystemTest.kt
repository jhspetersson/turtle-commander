package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CpioVirtualFileSystemTest {

    private lateinit var cpioPath: Path
    private var vfs: CpioVirtualFileSystem? = null

    @After
    fun tearDown() {
        vfs?.close()
        if (::cpioPath.isInitialized) Files.deleteIfExists(cpioPath)
    }

    private fun writeCpio(entries: List<Triple<String, ByteArray?, Long>>): Path {
        val path = Files.createTempFile("test-", ".cpio")
        Files.newOutputStream(path).use { raw ->
            CpioArchiveOutputStream(raw).use { cpio ->
                for ((name, data, mode) in entries) {
                    val entry = CpioArchiveEntry(name)
                    entry.mode = mode
                    entry.size = (data?.size ?: 0).toLong()
                    entry.setUID(1000)
                    entry.setGID(1001)
                    entry.time = 1_600_000_000
                    cpio.putArchiveEntry(entry)
                    if (data != null) cpio.write(data)
                    cpio.closeArchiveEntry()
                }
            }
        }
        return path
    }

    private fun writeDefaultCpio(): Path = writeCpio(
        listOf(
            Triple("hello.txt", "Hello, CPIO!".toByteArray(), FILE_MODE),
            Triple("sub", null, DIR_MODE),
            Triple("sub/nested.txt", "nested content".toByteArray(), FILE_MODE),
            Triple("empty.txt", ByteArray(0), FILE_MODE),
        ),
    )

    @Test
    fun `cpio extracts in process`() = runBlocking {
        cpioPath = writeDefaultCpio()
        val cpioVfs = CpioVirtualFileSystem(cpioPath)
        vfs = cpioVfs

        val rootEntries = cpioVfs.listFiles(cpioVfs.root).filter { !it.isParentLink }
        assertNotNull(rootEntries.find { it.name == "hello.txt" })
        assertNotNull(rootEntries.find { it.name == "sub" && it.isDirectory })

        assertEquals("Hello, CPIO!", Files.readString(cpioVfs.getPath("/hello.txt")))
        assertEquals("nested content", Files.readString(cpioVfs.getPath("/sub/nested.txt")))
        assertEquals(0, Files.size(cpioVfs.getPath("/empty.txt")))
    }

    @Test
    fun `cpio vfs is writable`() {
        cpioPath = writeDefaultCpio()
        val cpioVfs = CpioVirtualFileSystem(cpioPath)
        vfs = cpioVfs
        assertFalse(cpioVfs.isReadOnly)
    }

    @Test
    fun `entry metadata is surfaced in listings`() = runBlocking {
        cpioPath = writeDefaultCpio()
        val cpioVfs = CpioVirtualFileSystem(cpioPath)
        vfs = cpioVfs

        val hello = cpioVfs.listFiles(cpioVfs.root).first { it.name == "hello.txt" }
        assertEquals("1000", hello.owner)
        assertEquals("1001", hello.group)
        assertEquals("rw-r--r--", hello.permissions)
    }

    @Test
    fun `mtime is preserved`() {
        cpioPath = writeDefaultCpio()
        val cpioVfs = CpioVirtualFileSystem(cpioPath)
        vfs = cpioVfs
        val mtime = Files.getLastModifiedTime(cpioVfs.getPath("/hello.txt"))
        assertEquals(1_600_000_000_000L, mtime.toMillis())
    }

    @Test
    fun `edit and flush repacks with preserved metadata`() {
        cpioPath = writeDefaultCpio()
        val cpioVfs = CpioVirtualFileSystem(cpioPath)
        vfs = cpioVfs

        Files.writeString(cpioVfs.getPath("/hello.txt"), "changed content")
        cpioVfs.flush()

        assertEquals("changed content", Files.readString(cpioVfs.getPath("/hello.txt")))

        val entriesByName = mutableMapOf<String, CpioArchiveEntry>()
        Files.newInputStream(cpioPath).use { raw ->
            CpioArchiveInputStream(raw).use { cpio ->
                var entry = cpio.nextEntry
                while (entry != null) {
                    entriesByName[entry.name] = entry
                    entry = cpio.nextEntry
                }
            }
        }
        val hello = entriesByName["hello.txt"]
        assertNotNull(hello)
        assertEquals("changed content".length.toLong(), hello!!.size)
        assertEquals(1000L, hello.getUID())
        assertEquals(1001L, hello.getGID())
        assertEquals(FILE_MODE, hello.mode)
        assertEquals(DIR_MODE, entriesByName["sub"]?.mode)
    }

    @Test
    fun `new file added inside the archive survives repack`() {
        cpioPath = writeDefaultCpio()
        val cpioVfs = CpioVirtualFileSystem(cpioPath)
        vfs = cpioVfs

        Files.writeString(cpioVfs.getPath("/added.txt"), "fresh")
        cpioVfs.flush()

        assertEquals("fresh", Files.readString(cpioVfs.getPath("/added.txt")))
        assertEquals("Hello, CPIO!", Files.readString(cpioVfs.getPath("/hello.txt")))
    }

    @Test
    fun `non-cpio file is rejected silently`() {
        cpioPath = Files.createTempFile("not-a-cpio-", ".cpio")
        Files.write(cpioPath, "just some text".toByteArray())
        try {
            CpioVirtualFileSystem(cpioPath)
            fail("Expected construction to fail for a non-cpio file")
        } catch (_: SilentVfsOpenException) {
        }
    }

    @Test
    fun `hostile entry names cannot escape the extraction root`() = runBlocking {
        cpioPath = writeCpio(
            listOf(
                Triple("../../escape.txt", "outside".toByteArray(), FILE_MODE),
                Triple("safe.txt", "inside".toByteArray(), FILE_MODE),
            ),
        )
        val cpioVfs = CpioVirtualFileSystem(cpioPath)
        vfs = cpioVfs
        assertEquals("inside", Files.readString(cpioVfs.getPath("/safe.txt")))
        assertTrue(Files.notExists(cpioVfs.root.parent.resolve("escape.txt")))
        assertTrue(Files.notExists(cpioVfs.root.parent.parent.resolve("escape.txt")))
    }

    @Test
    fun `symlink escaping the archive root is skipped`() = runBlocking {
        val target = "../../outside.txt"
        cpioPath = writeCpio(
            listOf(
                Triple("evil-link", target.toByteArray(), SYMLINK_MODE),
                Triple("safe.txt", "inside".toByteArray(), FILE_MODE),
            ),
        )
        val cpioVfs = CpioVirtualFileSystem(cpioPath)
        vfs = cpioVfs
        assertEquals("inside", Files.readString(cpioVfs.getPath("/safe.txt")))
        assertTrue(Files.notExists(cpioVfs.getPath("/evil-link")))
    }

    companion object {
        private val FILE_MODE = (CpioConstants.C_ISREG or 0x1A4).toLong()
        private val DIR_MODE = (CpioConstants.C_ISDIR or 0x1ED).toLong()
        private val SYMLINK_MODE = (CpioConstants.C_ISLNK or 0x1FF).toLong()
    }
}
