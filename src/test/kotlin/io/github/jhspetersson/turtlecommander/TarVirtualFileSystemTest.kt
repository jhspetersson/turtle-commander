package io.github.jhspetersson.turtlecommander

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TarVirtualFileSystemTest {

    private lateinit var tarPath: Path
    private lateinit var vfs: TarVirtualFileSystem

    @Before
    fun setUp() {
        tarPath = Files.createTempFile("test-archive-", ".tar")
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            val dirEntry = TarArchiveEntry("mydir/")
            tar.putArchiveEntry(dirEntry)
            tar.closeArchiveEntry()

            val content = "Hello from tar".toByteArray()
            val fileEntry = TarArchiveEntry("mydir/hello.txt")
            fileEntry.size = content.size.toLong()
            tar.putArchiveEntry(fileEntry)
            tar.write(content)
            tar.closeArchiveEntry()

            val rootContent = "Root file".toByteArray()
            val rootFile = TarArchiveEntry("root.txt")
            rootFile.size = rootContent.size.toLong()
            tar.putArchiveEntry(rootFile)
            tar.write(rootContent)
            tar.closeArchiveEntry()
        }
        vfs = TarVirtualFileSystem(tarPath, inputStreamFactory = { Files.newInputStream(it) }, outputStreamFactory = { Files.newOutputStream(it) })
    }

    @After
    fun tearDown() {
        vfs.close()
        Files.deleteIfExists(tarPath)
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
    fun `isRoot returns false for subpath`() {
        assertFalse(vfs.isRoot(vfs.root.resolve("mydir")))
    }

    @Test
    fun `getPath with empty string returns root`() {
        assertEquals(vfs.root, vfs.getPath(""))
    }

    @Test
    fun `getPath with slash returns root`() {
        assertEquals(vfs.root, vfs.getPath("/"))
    }

    @Test
    fun `getPath resolves relative path`() {
        val path = vfs.getPath("mydir")
        assertEquals(vfs.root.resolve("mydir"), path)
    }

    @Test
    fun `getPath strips leading slash`() {
        val path = vfs.getPath("/mydir")
        assertEquals(vfs.root.resolve("mydir"), path)
    }

    @Test
    fun `listFiles at root has parent link`() = runBlocking {
        val entries = vfs.listFiles(vfs.root)
        val parent = entries.find { it.isParentLink }
        assertNotNull(parent)
        assertEquals("..", parent!!.name)
    }

    @Test
    fun `listFiles at root contains directory`() = runBlocking {
        val entries = vfs.listFiles(vfs.root)
        val dir = entries.find { it.name == "mydir" }
        assertNotNull(dir)
        assertTrue(dir!!.isDirectory)
    }

    @Test
    fun `listFiles at root contains file`() = runBlocking {
        val entries = vfs.listFiles(vfs.root)
        val file = entries.find { it.name == "root.txt" }
        assertNotNull(file)
        assertFalse(file!!.isDirectory)
    }

    @Test
    fun `listFiles directories sorted before files`() = runBlocking {
        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        val dirIdx = entries.indexOfFirst { it.isDirectory }
        val fileIdx = entries.indexOfFirst { !it.isDirectory }
        assertTrue(dirIdx < fileIdx)
    }

    @Test
    fun `listFiles in subdir contains nested file`() = runBlocking {
        val entries = vfs.listFiles(vfs.root.resolve("mydir"))
        val file = entries.find { it.name == "hello.txt" }
        assertNotNull(file)
        assertEquals("Hello from tar".length.toLong(), file!!.size)
    }

    @Test
    fun `listFiles subdir parent link points to root`() = runBlocking {
        val entries = vfs.listFiles(vfs.root.resolve("mydir"))
        val parent = entries.find { it.isParentLink }
        assertNotNull(parent)
        assertEquals(vfs.root, parent!!.path)
    }

    @Test
    fun `archivePath returns original path`() {
        assertEquals(tarPath, vfs.archivePath)
    }

    @Test
    fun `close cleans up temp directory`() {
        val tempDir = vfs.root
        assertTrue(Files.exists(tempDir))
        vfs.close()
        assertFalse(Files.exists(tempDir))
    }

    @Test
    fun `flush re-extracts archive`() = runBlocking {
        val oldRoot = vfs.root
        vfs.flush()
        val newRoot = vfs.root
        assertNotEquals(oldRoot, newRoot)
        // Old root should be cleaned up
        assertFalse(Files.exists(oldRoot))
        // New root should exist
        assertTrue(Files.exists(newRoot))
    }
}
