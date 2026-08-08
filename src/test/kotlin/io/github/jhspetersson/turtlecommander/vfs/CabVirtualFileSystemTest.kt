package io.github.jhspetersson.turtlecommander.vfs

import de.morihofi.cab4j.archive.CabArchive
import de.morihofi.cab4j.generator.CabGenerator
import de.morihofi.cab4j.structures.CfFolder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
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

    private fun writeCab(compression: CfFolder.COMPRESS_TYPE): Path {
        val path = Files.createTempFile("test-", ".cab")
        val archive = CabArchive()
        archive.addFile("hello.txt", "Hello, CAB!".toByteArray())
        archive.addFile("sub\\nested.txt", "nested content".toByteArray())
        archive.addFile("empty.txt", ByteArray(0))
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
}
