package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class XzVirtualFileSystemTest {

    private lateinit var xzPath: Path
    private lateinit var txzPath: Path
    private lateinit var tarXzPath: Path

    @Before
    fun setUp() {
        // Create a plain .xz file (single compressed file)
        xzPath = Files.createTempFile("test-", ".xz")
        XZCompressorOutputStream(Files.newOutputStream(xzPath)).use { xz ->
            xz.write("hello xz".toByteArray())
        }

        // Create a .txz file (tar wrapped in xz)
        txzPath = Files.createTempFile("test-", ".txz")
        createTarXz(txzPath)

        // Create a .tar.xz file
        tarXzPath = Files.createTempFile("test-archive-", ".tar.xz")
        // Rename to have proper double extension
        val renamed = tarXzPath.parent.resolve(tarXzPath.fileName.toString().replace(".tar.xz", "") + ".tar.xz")
        if (renamed != tarXzPath) {
            Files.move(tarXzPath, renamed)
            tarXzPath = renamed
        }
        createTarXz(tarXzPath)
    }

    private fun createTarXz(path: Path) {
        XZCompressorOutputStream(Files.newOutputStream(path)).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)

                val content = "file content".toByteArray()
                val entry = TarArchiveEntry("hello.txt")
                entry.size = content.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(content)
                tar.closeArchiveEntry()

                val dirEntry = TarArchiveEntry("subdir/")
                tar.putArchiveEntry(dirEntry)
                tar.closeArchiveEntry()

                val nestedContent = "nested".toByteArray()
                val nestedEntry = TarArchiveEntry("subdir/nested.txt")
                nestedEntry.size = nestedContent.size.toLong()
                tar.putArchiveEntry(nestedEntry)
                tar.write(nestedContent)
                tar.closeArchiveEntry()
            }
        }
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(xzPath)
        Files.deleteIfExists(txzPath)
        Files.deleteIfExists(tarXzPath)
    }

    // --- Provider tests ---

    private val provider = XzFileSystemProvider()

    @Test
    fun `provider supports xz extension`() {
        assertTrue(provider.supportsExtension("xz"))
    }

    @Test
    fun `provider supports txz extension`() {
        assertTrue(provider.supportsExtension("txz"))
    }

    @Test
    fun `provider does not support gz`() {
        assertFalse(provider.supportsExtension("gz"))
    }

    @Test
    fun `provider does not support tar`() {
        assertFalse(provider.supportsExtension("tar"))
    }

    @Test
    fun `provider does not support zip`() {
        assertFalse(provider.supportsExtension("zip"))
    }

    @Test
    fun `xz archive extensions set has correct count`() {
        assertEquals(2, XzFileSystemProvider.ARCHIVE_EXTENSIONS.size)
    }

    @Test
    fun `provider supports xz file`() {
        assertTrue(provider.supports(xzPath))
    }

    @Test
    fun `provider supports txz file`() {
        assertTrue(provider.supports(txzPath))
    }

    // --- Single .xz file VFS tests ---

    @Test
    fun `plain xz creates read-only single file VFS`() {
        val vfs = provider.create(xzPath)
        vfs.use { vfs ->
            assertTrue(vfs.isReadOnly)
        }
    }

    @Test
    fun `plain xz lists decompressed file`() = runBlocking {
        val vfs = provider.create(xzPath)
        vfs.use { vfs ->
            val entries = vfs.listFiles(vfs.root)
            val files = entries.filter { !it.isParentLink }
            assertEquals(1, files.size)
            assertFalse(files[0].name.endsWith(".xz"))
        }
    }

    @Test
    fun `plain xz decompressed file has correct content`() = runBlocking {
        val vfs = provider.create(xzPath)
        vfs.use { vfs ->
            val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
            val content = String(Files.readAllBytes(entries[0].path))
            assertEquals("hello xz", content)
        }
    }

    // --- .txz (tar.xz) VFS tests ---

    @Test
    fun `txz creates writable tar VFS`() {
        val vfs = provider.create(txzPath)
        vfs.use { vfs ->
            assertFalse(vfs.isReadOnly)
            assertTrue(vfs is TarVirtualFileSystem)
        }
    }

    @Test
    fun `txz lists tar contents`() = runBlocking {
        val vfs = provider.create(txzPath)
        vfs.use { vfs ->
            val entries = vfs.listFiles(vfs.root)
            val names = entries.filter { !it.isParentLink }.map { it.name }.toSet()
            assertTrue("hello.txt" in names)
            assertTrue("subdir" in names)
        }
    }

    @Test
    fun `txz can navigate into subdirectory`() = runBlocking {
        val vfs = provider.create(txzPath)
        vfs.use { vfs ->
            val subdir = vfs.root.resolve("subdir")
            val entries = vfs.listFiles(subdir)
            val names = entries.filter { !it.isParentLink }.map { it.name }
            assertTrue("nested.txt" in names)
        }
    }

    @Test
    fun `txz file content is correct`() = runBlocking {
        val vfs = provider.create(txzPath)
        vfs.use { vfs ->
            val filePath = vfs.root.resolve("hello.txt")
            val content = String(Files.readAllBytes(filePath))
            assertEquals("file content", content)
        }
    }

    // --- .tar.xz VFS tests ---

    @Test
    fun `tar xz creates writable tar VFS`() {
        val vfs = provider.create(tarXzPath)
        vfs.use { vfs ->
            assertFalse(vfs.isReadOnly)
            assertTrue(vfs is TarVirtualFileSystem)
        }
    }

    @Test
    fun `tar xz lists tar contents`() = runBlocking {
        val vfs = provider.create(tarXzPath)
        vfs.use { vfs ->
            val entries = vfs.listFiles(vfs.root)
            val names = entries.filter { !it.isParentLink }.map { it.name }.toSet()
            assertTrue("hello.txt" in names)
            assertTrue("subdir" in names)
        }
    }

    // --- Close / cleanup ---

    @Test
    fun `close cleans up temp directory`() {
        val vfs = provider.create(xzPath)
        val tempDir = vfs.root
        assertTrue(Files.exists(tempDir))
        vfs.close()
        assertFalse(Files.exists(tempDir))
    }
}
