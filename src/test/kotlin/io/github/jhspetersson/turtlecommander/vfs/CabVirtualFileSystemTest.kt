package io.github.jhspetersson.turtlecommander.vfs

import de.morihofi.cab4j.archive.CabArchive
import de.morihofi.cab4j.generator.CabGenerator
import de.morihofi.cab4j.structures.CfFolder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class CabVirtualFileSystemTest {

    private lateinit var cabPath: Path
    private var vfs: CabVirtualFileSystem? = null

    @After
    fun tearDown() {
        vfs?.close()
        if (::cabPath.isInitialized) Files.deleteIfExists(cabPath)
    }

    private val defaultEntries = mapOf(
        "hello.txt" to "Hello, CAB!".toByteArray(),
        "sub\\nested.txt" to "nested content".toByteArray(),
        "empty.txt" to ByteArray(0),
    )

    private fun writeCab(compression: CfFolder.COMPRESS_TYPE, entries: Map<String, ByteArray> = defaultEntries): Path {
        val path = Files.createTempFile("test-", ".cab")
        val archive = CabArchive()
        for ((name, data) in entries) archive.addFile(name, data)
        val generator = CabGenerator(archive)
        generator.setCompressionType(compression)
        FileChannel.open(path, StandardOpenOption.WRITE).use { generator.writeCabinet(it) }
        return path
    }

    private fun openAndVerify() = runBlocking {
        val cabVfs = CabVirtualFileSystem(cabPath)
        vfs = cabVfs

        val rootEntries = cabVfs.listFiles(cabVfs.root).filter { !it.isParentLink }
        assertNotNull(rootEntries.find { it.name == "hello.txt" })
        assertNotNull(rootEntries.find { it.name == "sub" })

        assertEquals("Hello, CAB!", Files.readString(cabVfs.getPath("/hello.txt")))
        assertEquals("nested content", Files.readString(cabVfs.getPath("/sub/nested.txt")))
        assertEquals(0, Files.size(cabVfs.getPath("/empty.txt")))
    }

    @Test
    fun `uncompressed cab extracts in process`() {
        cabPath = writeCab(CfFolder.COMPRESS_TYPE.TCOMP_TYPE_NONE)
        openAndVerify()
    }

    @Test
    fun `mszip cab extracts in process`() {
        cabPath = writeCab(CfFolder.COMPRESS_TYPE.TCOMP_TYPE_MSZIP)
        openAndVerify()
    }

    @Test
    fun `lzx cab extracts in process`() {
        cabPath = writeCab(CfFolder.COMPRESS_TYPE.TCOMP_TYPE_LZX)
        openAndVerify()
    }

    @Test
    fun `cab vfs is read-only`() {
        cabPath = writeCab(CfFolder.COMPRESS_TYPE.TCOMP_TYPE_NONE)
        val cabVfs = CabVirtualFileSystem(cabPath)
        vfs = cabVfs
        assertTrue(cabVfs.isReadOnly)
    }

    @Test
    fun `non-cab file is rejected silently`() {
        cabPath = Files.createTempFile("not-a-cab-", ".cab")
        Files.write(cabPath, "just some text".toByteArray())
        try {
            CabVirtualFileSystem(cabPath)
            fail("Expected construction to fail for a non-CAB file")
        } catch (_: SilentVfsOpenException) {
        }
    }

    @Test
    fun `hostile entry names cannot escape the extraction root`() = runBlocking {
        cabPath = Files.createTempFile("test-", ".cab")
        val archive = CabArchive()
        archive.addFile("..\\..\\escape.txt", "outside".toByteArray())
        archive.addFile("safe.txt", "inside".toByteArray())
        val generator = CabGenerator(archive)
        FileChannel.open(cabPath, StandardOpenOption.WRITE).use { generator.writeCabinet(it) }

        val cabVfs = CabVirtualFileSystem(cabPath)
        vfs = cabVfs
        assertEquals("inside", Files.readString(cabVfs.getPath("/safe.txt")))
        assertTrue(Files.notExists(cabVfs.root.parent.resolve("escape.txt")))
        assertTrue(Files.notExists(cabVfs.root.parent.parent.resolve("escape.txt")))
    }

    @Test
    fun `out of memory in the in-process reader falls through to the 7-Zip fallback`() {
        cabPath = writeCab(CfFolder.COMPRESS_TYPE.TCOMP_TYPE_NONE, mapOf("hello.txt" to "Hello, CAB!".toByteArray()))
        val oom = OutOfMemoryError("Java heap space")
        try {
            val cabVfs = CabVirtualFileSystem(cabPath, null) { throw oom }
            vfs = cabVfs
            assertNotNull(System7z.findBinary())
            assertEquals("Hello, CAB!", Files.readString(cabVfs.getPath("/hello.txt")))
        } catch (e: System7zUnavailableException) {
            assertNull(System7z.findBinary())
            assertSame(oom, e.cause)
        }
    }

    @Test
    fun `cabinet larger than the heap headroom or the array limit exceeds the in-process limit`() {
        assertTrue(CabVirtualFileSystem.exceedsInProcessLimit(size = 100, heapHeadroom = 50))
        assertTrue(CabVirtualFileSystem.exceedsInProcessLimit(size = Int.MAX_VALUE.toLong(), heapHeadroom = Long.MAX_VALUE))
        assertFalse(CabVirtualFileSystem.exceedsInProcessLimit(size = 100, heapHeadroom = 1000))
    }

    @Test
    fun `oversized cabinet is refused before being read into memory`() {
        cabPath = writeCab(CfFolder.COMPRESS_TYPE.TCOMP_TYPE_NONE)
        try {
            CabVirtualFileSystem.readWholeFile(cabPath, heapHeadroom = 1)
            fail("Expected the oversized cabinet to be refused")
        } catch (_: IOException) {
        }
        assertEquals(Files.size(cabPath), CabVirtualFileSystem.readWholeFile(cabPath, heapHeadroom = Long.MAX_VALUE).remaining().toLong())
    }
}
