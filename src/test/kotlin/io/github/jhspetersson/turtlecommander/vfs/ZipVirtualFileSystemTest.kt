package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipVirtualFileSystemTest {

    private lateinit var zipPath: Path
    private lateinit var vfs: ZipVirtualFileSystem

    @Before
    fun setUp() {
        zipPath = Files.createTempFile("test-archive-", ".zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(ZipEntry("hello.txt"))
            zos.write("Hello World".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("subdir/"))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("subdir/nested.txt"))
            zos.write("Nested file".toByteArray())
            zos.closeEntry()
        }
        vfs = ZipVirtualFileSystem(zipPath)
    }

    @After
    fun tearDown() {
        vfs.close()
        Files.deleteIfExists(zipPath)
    }

    @Test
    fun `root is slash`() {
        assertEquals("/", vfs.root.toString())
    }

    @Test
    fun `isRoot returns true for root`() {
        assertTrue(vfs.isRoot(vfs.root))
    }

    @Test
    fun `isRoot returns false for subdir`() {
        assertFalse(vfs.isRoot(vfs.getPath("/subdir")))
    }

    @Test
    fun `getPath returns path in zip filesystem`() {
        val path = vfs.getPath("/hello.txt")
        assertEquals("/hello.txt", path.toString())
    }

    @Test
    fun `listFiles at root contains parent link`() = runBlocking {
        val entries = vfs.listFiles(vfs.root)
        val parentLink = entries.find { it.isParentLink }
        assertNotNull(parentLink)
        assertEquals("..", parentLink!!.name)
    }

    @Test
    fun `listFiles at root contains hello txt`() = runBlocking {
        val entries = vfs.listFiles(vfs.root)
        val hello = entries.find { it.name == "hello.txt" }
        assertNotNull(hello)
        assertFalse(hello!!.isDirectory)
    }

    @Test
    fun `listFiles at root contains subdir`() = runBlocking {
        val entries = vfs.listFiles(vfs.root)
        val subdir = entries.find { it.name == "subdir" }
        assertNotNull(subdir)
        assertTrue(subdir!!.isDirectory)
    }

    @Test
    fun `listFiles directories come before files`() = runBlocking {
        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        val dirIndex = entries.indexOfFirst { it.isDirectory }
        val fileIndex = entries.indexOfFirst { !it.isDirectory }
        assertTrue("Directories should come before files", dirIndex < fileIndex)
    }

    @Test
    fun `listFiles in subdir contains nested file`() = runBlocking {
        val subdirPath = vfs.getPath("/subdir")
        val entries = vfs.listFiles(subdirPath)
        val nested = entries.find { it.name == "nested.txt" }
        assertNotNull(nested)
    }

    @Test
    fun `listFiles in subdir parent link points to root`() = runBlocking {
        val subdirPath = vfs.getPath("/subdir")
        val entries = vfs.listFiles(subdirPath)
        val parentLink = entries.find { it.isParentLink }
        assertNotNull(parentLink)
        assertEquals("/", parentLink!!.path.toString())
    }

    @Test
    fun `listFiles at root parent link points to archive parent`() = runBlocking {
        val entries = vfs.listFiles(vfs.root)
        val parentLink = entries.find { it.isParentLink }
        assertNotNull(parentLink)
        assertEquals(zipPath.parent, parentLink!!.path)
    }

    @Test
    fun `archivePath returns original path`() {
        assertEquals(zipPath, vfs.archivePath)
    }

    @Test
    fun `file entries have empty permissions`() = runBlocking {
        val entries = vfs.listFiles(vfs.root)
        entries.filter { !it.isParentLink }.forEach {
            assertEquals("", it.permissions)
        }
    }

    @Test
    fun `flush reopens filesystem`() = runBlocking {
        val entriesBefore = vfs.listFiles(vfs.root)
        vfs.flush()
        val entriesAfter = vfs.listFiles(vfs.root)
        assertEquals(entriesBefore.size, entriesAfter.size)
    }
}

/**
 * Exercises the fallback extraction path used for zips that Java's built-in
 * ZipFileSystem rejects (e.g. invalid CEN headers). Even though we build valid
 * zips here, ZipExtractVirtualFileSystem can be instantiated directly and its
 * extraction loop is the interesting thing — it previously called
 * `zip.entries.toList()` just to know the total, which allocated an O(n)
 * reference list for every extracted archive.
 */
class ZipExtractVirtualFileSystemTest {

    private lateinit var zipPath: Path
    private var vfs: ZipExtractVirtualFileSystem? = null

    @After
    fun tearDown() {
        vfs?.close()
        if (::zipPath.isInitialized) Files.deleteIfExists(zipPath)
    }

    private fun createZip(vararg entries: Pair<String, String?>) {
        zipPath = Files.createTempFile("zip-extract-test-", ".zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                if (content != null) zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
    }

    @Test
    fun `extracts all entries without materializing a list`() = runBlocking {
        createZip(
            "a.txt" to "alpha",
            "sub/" to null,
            "sub/b.txt" to "beta",
            "sub/c.txt" to "gamma",
        )

        val extractVfs = ZipExtractVirtualFileSystem(zipPath)
        vfs = extractVfs

        val rootEntries = extractVfs.listFiles(extractVfs.root).filter { !it.isParentLink }
        assertNotNull(rootEntries.find { it.name == "a.txt" })
        assertNotNull(rootEntries.find { it.name == "sub" })

        val subEntries = extractVfs.listFiles(extractVfs.getPath("/sub")).filter { !it.isParentLink }
        assertNotNull(subEntries.find { it.name == "b.txt" })
        assertNotNull(subEntries.find { it.name == "c.txt" })

        // Contents must survive the streaming iteration intact.
        val aPath = extractVfs.getPath("/a.txt")
        assertEquals("alpha", Files.readString(aPath))
        val bPath = extractVfs.getPath("/sub/b.txt")
        assertEquals("beta", Files.readString(bPath))
    }

    @Test
    fun `handles empty archive`() = runBlocking {
        createZip()
        val extractVfs = ZipExtractVirtualFileSystem(zipPath)
        vfs = extractVfs
        val entries = extractVfs.listFiles(extractVfs.root).filter { !it.isParentLink }
        assertTrue(entries.isEmpty())
    }
}
