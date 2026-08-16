package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

class ArjVirtualFileSystemTest {

    private lateinit var arjPath: Path
    private var vfs: ArjVirtualFileSystem? = null

    @After
    fun tearDown() {
        vfs?.close()
        if (::arjPath.isInitialized) Files.deleteIfExists(arjPath)
    }

    private class StoredArjWriter(archiveName: String) {
        private val out = ByteArrayOutputStream()

        init {
            val basic = ByteArrayOutputStream()
            basic.write(FIRST_HEADER_SIZE)
            basic.write(VERSION)
            basic.write(MIN_VERSION)
            basic.write(HOST_OS_UNIX)
            basic.write(0)
            basic.write(0)
            basic.write(FILE_TYPE_MAIN_HEADER)
            basic.write(0)
            writeIntLe(basic, 0)
            writeIntLe(basic, 0)
            writeIntLe(basic, 0)
            writeIntLe(basic, 0)
            writeShortLe(basic, 0)
            writeShortLe(basic, 0)
            basic.write(0)
            basic.write(0)
            writeNulTerminated(basic, archiveName)
            writeNulTerminated(basic, "")
            writeHeaderBlock(basic.toByteArray())
        }

        fun addFile(name: String, data: ByteArray, mtimeSeconds: Int = MTIME_SECONDS) {
            val crc = CRC32().also { it.update(data) }
            writeHeaderBlock(localHeader(name, FILE_TYPE_BINARY, data.size, crc.value.toInt(), mtimeSeconds))
            out.write(data)
        }

        fun addDirectory(name: String) {
            writeHeaderBlock(localHeader(name, FILE_TYPE_DIRECTORY, 0, 0, MTIME_SECONDS))
        }

        fun toBytes(): ByteArray {
            out.write(0x60)
            out.write(0xEA)
            writeShortLe(out, 0)
            return out.toByteArray()
        }

        private fun localHeader(name: String, fileType: Int, size: Int, crc: Int, mtimeSeconds: Int): ByteArray {
            val basic = ByteArrayOutputStream()
            basic.write(FIRST_HEADER_SIZE)
            basic.write(VERSION)
            basic.write(MIN_VERSION)
            basic.write(HOST_OS_UNIX)
            basic.write(0)
            basic.write(METHOD_STORED)
            basic.write(fileType)
            basic.write(0)
            writeIntLe(basic, mtimeSeconds)
            writeIntLe(basic, size)
            writeIntLe(basic, size)
            writeIntLe(basic, crc)
            writeShortLe(basic, 0)
            writeShortLe(basic, 0)
            basic.write(0)
            basic.write(0)
            writeNulTerminated(basic, name)
            writeNulTerminated(basic, "")
            return basic.toByteArray()
        }

        private fun writeHeaderBlock(basic: ByteArray) {
            out.write(0x60)
            out.write(0xEA)
            writeShortLe(out, basic.size)
            out.write(basic)
            val crc = CRC32().also { it.update(basic) }
            writeIntLe(out, crc.value.toInt())
            writeShortLe(out, 0)
        }

        private fun writeShortLe(target: ByteArrayOutputStream, value: Int) {
            target.write(value and 0xFF)
            target.write((value ushr 8) and 0xFF)
        }

        private fun writeIntLe(target: ByteArrayOutputStream, value: Int) {
            target.write(value and 0xFF)
            target.write((value ushr 8) and 0xFF)
            target.write((value ushr 16) and 0xFF)
            target.write((value ushr 24) and 0xFF)
        }

        private fun writeNulTerminated(target: ByteArrayOutputStream, value: String) {
            target.write(value.toByteArray(Charsets.US_ASCII))
            target.write(0)
        }

        companion object {
            private const val FIRST_HEADER_SIZE = 30
            private const val VERSION = 11
            private const val MIN_VERSION = 1
            private const val HOST_OS_UNIX = 2
            private const val FILE_TYPE_BINARY = 0
            private const val FILE_TYPE_MAIN_HEADER = 2
            private const val FILE_TYPE_DIRECTORY = 3
            private const val METHOD_STORED = 0
            const val MTIME_SECONDS = 1_600_000_000
        }
    }

    private fun writeArj(): Path {
        val writer = StoredArjWriter("test.arj")
        writer.addFile("hello.txt", "Hello, ARJ!".toByteArray())
        writer.addDirectory("sub")
        writer.addFile("sub/nested.txt", "nested content".toByteArray())
        writer.addFile("empty.txt", ByteArray(0))
        val path = Files.createTempFile("test-", ".arj")
        Files.write(path, writer.toBytes())
        return path
    }

    @Test
    fun `stored arj extracts in process`() = runBlocking {
        arjPath = writeArj()
        val arjVfs = ArjVirtualFileSystem(arjPath)
        vfs = arjVfs

        val rootEntries = arjVfs.listFiles(arjVfs.root).filter { !it.isParentLink }
        assertNotNull(rootEntries.find { it.name == "hello.txt" })
        assertNotNull(rootEntries.find { it.name == "sub" && it.isDirectory })

        assertEquals("Hello, ARJ!", Files.readString(arjVfs.getPath("/hello.txt")))
        assertEquals("nested content", Files.readString(arjVfs.getPath("/sub/nested.txt")))
        assertEquals(0, Files.size(arjVfs.getPath("/empty.txt")))
    }

    @Test
    fun `unix mtime is preserved`() {
        arjPath = writeArj()
        val arjVfs = ArjVirtualFileSystem(arjPath)
        vfs = arjVfs
        val mtime = Files.getLastModifiedTime(arjVfs.getPath("/hello.txt"))
        assertEquals(StoredArjWriter.MTIME_SECONDS * 1000L, mtime.toMillis())
    }

    @Test
    fun `arj vfs is read-only`() {
        arjPath = writeArj()
        val arjVfs = ArjVirtualFileSystem(arjPath)
        vfs = arjVfs
        assertTrue(arjVfs.isReadOnly)
    }

    @Test
    fun `non-arj file is rejected silently`() {
        arjPath = Files.createTempFile("not-an-arj-", ".arj")
        Files.write(arjPath, "just some text".toByteArray())
        try {
            ArjVirtualFileSystem(arjPath)
            fail("Expected construction to fail for a non-ARJ file")
        } catch (_: SilentVfsOpenException) {
        }
    }

    @Test
    fun `hostile entry names cannot escape the extraction root`() = runBlocking {
        val writer = StoredArjWriter("evil.arj")
        writer.addFile("../../escape.txt", "outside".toByteArray())
        writer.addFile("safe.txt", "inside".toByteArray())
        arjPath = Files.createTempFile("test-", ".arj")
        Files.write(arjPath, writer.toBytes())

        val arjVfs = ArjVirtualFileSystem(arjPath)
        vfs = arjVfs
        assertEquals("inside", Files.readString(arjVfs.getPath("/safe.txt")))
        assertTrue(Files.notExists(arjVfs.root.parent.resolve("escape.txt")))
        assertTrue(Files.notExists(arjVfs.root.parent.parent.resolve("escape.txt")))
    }
}
