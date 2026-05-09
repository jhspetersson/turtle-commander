package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

class CompressedSingleFileVirtualFileSystemTest {

    private lateinit var fakePath: Path
    private lateinit var vfs: CompressedSingleFileVirtualFileSystem

    @Before
    fun setUp() {
        // Create a fake "compressed" file — the decompressor just returns the raw bytes
        fakePath = Files.createTempFile("test-fake-", ".fakegz")
        val content = "decompressed content"
        Files.write(fakePath, content.toByteArray())

        vfs = CompressedSingleFileVirtualFileSystem(fakePath, ".fakegz") { inputStream: InputStream ->
            // Identity "decompressor" — just returns the same stream
            inputStream
        }
    }

    @After
    fun tearDown() {
        vfs.close()
        Files.deleteIfExists(fakePath)
    }

    @Test
    fun `root is temp directory`() {
        assertTrue(Files.isDirectory(vfs.root))
    }

    @Test
    fun `isRoot returns true for root`() {
        assertTrue(vfs.isRoot(vfs.root))
    }

    @Test
    fun `isRoot returns false for file inside`() {
        assertFalse(vfs.isRoot(vfs.root.resolve("something")))
    }

    @Test
    fun `getPath with empty returns root`() {
        assertEquals(vfs.root, vfs.getPath(""))
    }

    @Test
    fun `getPath with slash returns root`() {
        assertEquals(vfs.root, vfs.getPath("/"))
    }

    @Test
    fun `getPath resolves relative path`() {
        val path = vfs.getPath("file.txt")
        assertEquals(vfs.root.resolve("file.txt"), path)
    }

    @Test
    fun `listFiles contains parent link`() = runBlocking {
        val entries = vfs.listFiles(vfs.root)
        val parent = entries.find { it.isParentLink }
        assertNotNull(parent)
    }

    @Test
    fun `listFiles contains decompressed file`() = runBlocking {
        val entries = vfs.listFiles(vfs.root)
        // The inner file name should be the original name minus the compression suffix
        val innerFiles = entries.filter { !it.isParentLink }
        assertEquals(1, innerFiles.size)
        assertFalse(innerFiles[0].isDirectory)
    }

    @Test
    fun `decompressed file has correct content`() = runBlocking {
        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        val filePath = entries[0].path
        val content = String(Files.readAllBytes(filePath))
        assertEquals("decompressed content", content)
    }

    @Test
    fun `archivePath returns original path`() {
        assertEquals(fakePath, vfs.archivePath)
    }

    @Test
    fun `close cleans up temp directory`() {
        val tempDir = vfs.root
        assertTrue(Files.exists(tempDir))
        vfs.close()
        assertFalse(Files.exists(tempDir))
    }

    @Test
    fun `inner file name strips compression suffix`() = runBlocking {
        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        val name = entries[0].name
        assertFalse("Inner name should not end with compression suffix", name.endsWith(".fakegz"))
    }

    @Test
    fun `isReadOnly returns true`() {
        assertTrue("CompressedSingleFileVirtualFileSystem should be read-only", vfs.isReadOnly)
    }

    @Test
    fun `renameFile rejects on read-only VFS`() = runBlocking {
        // The class is isReadOnly = true, but renameFile used to silently move the inner
        // file in the temp dir; the next flush() would re-extract and lose the rename.
        // Calling rename on a read-only VFS must surface as an error rather than data loss.
        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        val source = entries[0].path
        val ex = try {
            vfs.renameFile(source, "renamed.txt")
            null
        } catch (e: UnsupportedOperationException) {
            e
        }
        assertNotNull("expected UnsupportedOperationException on read-only VFS rename", ex)
    }

    @Test
    fun `inner file name strips mixed-case compression suffix`() = runBlocking {
        val mixedCasePath = Files.createTempFile("test-mixed-", ".FakeGz")
        Files.write(mixedCasePath, "content".toByteArray())
        val mixedVfs = CompressedSingleFileVirtualFileSystem(mixedCasePath, ".fakegz") { it }
        try {
            val entries = mixedVfs.listFiles(mixedVfs.root).filter { !it.isParentLink }
            val name = entries[0].name
            assertFalse("Inner name should not end with mixed-case suffix", name.endsWith(".FakeGz", ignoreCase = true))
        } finally {
            mixedVfs.close()
            Files.deleteIfExists(mixedCasePath)
        }
    }

    @Test
    fun `inner file name strips uppercase compression suffix`() = runBlocking {
        val upperPath = Files.createTempFile("test-upper-", ".FAKEGZ")
        Files.write(upperPath, "content".toByteArray())
        val upperVfs = CompressedSingleFileVirtualFileSystem(upperPath, ".fakegz") { it }
        try {
            val entries = upperVfs.listFiles(upperVfs.root).filter { !it.isParentLink }
            val name = entries[0].name
            assertFalse("Inner name should not end with uppercase suffix", name.endsWith(".FAKEGZ", ignoreCase = true))
        } finally {
            upperVfs.close()
            Files.deleteIfExists(upperPath)
        }
    }
}
