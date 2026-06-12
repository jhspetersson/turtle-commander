package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PakVirtualFileSystemTest {

    private lateinit var pakPath: Path
    private var vfs: VirtualFileSystem? = null

    @After
    fun tearDown() {
        vfs?.close()
        if (::pakPath.isInitialized) Files.deleteIfExists(pakPath)
    }

    private fun leInt32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    /**
     * Assembles a minimal but well-formed Quake PAK: 12-byte header, raw file data, then a
     * 64-byte-per-entry directory at the end.
     */
    private fun buildPak(files: Map<String, ByteArray>): ByteArray {
        val data = ByteArrayOutputStream()
        val dir = ByteArrayOutputStream()
        val headerSize = 12
        for ((name, bytes) in files) {
            val offset = headerSize + data.size()
            data.write(bytes)
            val nameField = ByteArray(56)
            val nameBytes = name.toByteArray(StandardCharsets.US_ASCII)
            System.arraycopy(nameBytes, 0, nameField, 0, nameBytes.size)
            dir.write(nameField)
            dir.write(leInt32(offset))
            dir.write(leInt32(bytes.size))
        }
        val dirOffset = headerSize + data.size()
        val dirLength = dir.size()

        val out = ByteArrayOutputStream()
        out.write("PACK".toByteArray(StandardCharsets.US_ASCII))
        out.write(leInt32(dirOffset))
        out.write(leInt32(dirLength))
        out.write(data.toByteArray())
        out.write(dir.toByteArray())
        return out.toByteArray()
    }

    private fun writePak(files: Map<String, ByteArray>): Path {
        val path = Files.createTempFile("test-pak-", ".pak")
        Files.write(path, buildPak(files))
        return path
    }

    @Test
    fun `pak file extracts entries including nested directories`() = runBlocking {
        pakPath = writePak(
            linkedMapOf(
                "readme.txt" to "hello".toByteArray(),
                "maps/e1m1.bsp" to byteArrayOf(1, 2, 3, 4),
            ),
        )
        val pakVfs = PakVirtualFileSystem(pakPath)
        vfs = pakVfs

        val rootEntries = pakVfs.listFiles(pakVfs.root).filter { !it.isParentLink }
        assertNotNull(rootEntries.find { it.name == "readme.txt" })
        assertNotNull(rootEntries.find { it.name == "maps" && it.isDirectory })

        pakVfs.materialize(pakVfs.getPath("/readme.txt"))
        assertEquals("hello", Files.readString(pakVfs.getPath("/readme.txt")))

        val mapsEntries = pakVfs.listFiles(pakVfs.getPath("/maps")).filter { !it.isParentLink }
        val bsp = mapsEntries.find { it.name == "e1m1.bsp" }
        assertNotNull(bsp)
        pakVfs.materialize(pakVfs.getPath("/maps/e1m1.bsp"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(pakVfs.getPath("/maps/e1m1.bsp")))
    }

    @Test
    fun `entries are lazy stubs until materialized`() = runBlocking {
        pakPath = writePak(linkedMapOf("lazy.txt" to "real bytes".toByteArray()))
        val pakVfs = PakVirtualFileSystem(pakPath)
        vfs = pakVfs

        val path = pakVfs.getPath("/lazy.txt")
        // Lazy contract: right size, no content until materialized; idempotent afterwards.
        assertEquals("real bytes".length.toLong(), Files.size(path))
        assertNotEquals("real bytes", Files.readString(path))
        pakVfs.materialize(path)
        assertEquals("real bytes", Files.readString(path))
        pakVfs.materialize(path)
        assertEquals("real bytes", Files.readString(path))
    }

    @Test
    fun `pak vfs is writable`() {
        pakPath = writePak(linkedMapOf("a.txt" to "x".toByteArray()))
        val pakVfs = PakVirtualFileSystem(pakPath)
        vfs = pakVfs
        assertFalse(pakVfs.isReadOnly)
    }

    @Test
    fun `editing an entry and flushing repacks the pak`() = runBlocking {
        pakPath = writePak(
            linkedMapOf(
                "readme.txt" to "old".toByteArray(),
                "maps/e1m1.bsp" to byteArrayOf(1, 2, 3, 4),
            ),
        )
        val pakVfs = PakVirtualFileSystem(pakPath)
        vfs = pakVfs

        // Edit one file in place, then flush to repack the archive on disk.
        Files.write(pakVfs.getPath("/readme.txt"), "a much longer replacement body".toByteArray())
        pakVfs.flush()

        // Re-open from disk to confirm the change was persisted and the directory is consistent.
        val reopened = PakVirtualFileSystem(pakPath)
        reopened.use { reopened ->
            // The edit overwrote a still-pending stub: the repack must carry the user's
            // bytes, and the untouched sibling must have been materialized before the
            // archive was rewritten underneath it.
            reopened.materialize(reopened.getPath("/readme.txt"))
            assertEquals("a much longer replacement body", Files.readString(reopened.getPath("/readme.txt")))
            reopened.materialize(reopened.getPath("/maps/e1m1.bsp"))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(reopened.getPath("/maps/e1m1.bsp")))
        }
    }

    @Test
    fun `adding a new nested entry survives a repack`() = runBlocking {
        pakPath = writePak(linkedMapOf("a.txt" to "x".toByteArray()))
        val pakVfs = PakVirtualFileSystem(pakPath)
        vfs = pakVfs

        val newPath = pakVfs.getPath("/sounds/blip.wav")
        Files.createDirectories(newPath.parent)
        Files.write(newPath, byteArrayOf(9, 8, 7))
        pakVfs.flush()

        val reopened = PakVirtualFileSystem(pakPath)
        reopened.use { reopened ->
            reopened.materialize(reopened.getPath("/sounds/blip.wav"))
            assertArrayEquals(byteArrayOf(9, 8, 7), Files.readAllBytes(reopened.getPath("/sounds/blip.wav")))
            reopened.materialize(reopened.getPath("/a.txt"))
            assertEquals("x", Files.readString(reopened.getPath("/a.txt")))
        }
    }

    @Test
    fun `renaming an entry repacks the pak`() = runBlocking {
        pakPath = writePak(linkedMapOf("old.txt" to "data".toByteArray()))
        val pakVfs = PakVirtualFileSystem(pakPath)
        vfs = pakVfs

        pakVfs.renameFile(pakVfs.getPath("/old.txt"), "new.txt")

        val reopened = PakVirtualFileSystem(pakPath)
        reopened.use { reopened ->
            val names = reopened.listFiles(reopened.root).filter { !it.isParentLink }.map { it.name }
            assertTrue(names.contains("new.txt"))
            assertFalse(names.contains("old.txt"))
            // The rename repacked while the entry was still a pending stub — the archive
            // must carry the original bytes at the new name, not the stub's zeros.
            reopened.materialize(reopened.getPath("/new.txt"))
            assertEquals("data", Files.readString(reopened.getPath("/new.txt")))
        }
    }

    @Test
    fun `pak vfs root parent link points to archive parent`() = runBlocking {
        pakPath = writePak(linkedMapOf("a.txt" to "x".toByteArray()))
        val pakVfs = PakVirtualFileSystem(pakPath)
        vfs = pakVfs
        val parentLink = pakVfs.listFiles(pakVfs.root).find { it.isParentLink }
        assertNotNull(parentLink)
        assertEquals(pakPath.parent, parentLink!!.path)
    }

    @Test
    fun `non-pak file is rejected by the pak vfs`() {
        pakPath = Files.createTempFile("not-a-pak-", ".pak")
        Files.write(pakPath, "this is plainly not a pak archive".toByteArray())
        try {
            PakVirtualFileSystem(pakPath)
            fail("Expected construction to fail for a non-PAK file")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `provider falls back to zip for a zip disguised as pak`() = runBlocking {
        pakPath = Files.createTempFile("zip-as-pak-", ".pak")
        ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("inside.txt"))
                zos.write("from zip".toByteArray())
                zos.closeEntry()
            }
            Files.write(pakPath, bos.toByteArray())
        }

        val opened = PakFileSystemProvider().create(pakPath)
        vfs = opened
        val entries = opened.listFiles(opened.root).filter { !it.isParentLink }
        assertNotNull(entries.find { it.name == "inside.txt" })
    }

    @Test
    fun `provider throws SilentVfsOpenException when neither pak nor zip`() {
        pakPath = Files.createTempFile("garbage-", ".pak")
        Files.write(pakPath, ByteArray(256) { (it * 7 + 3).toByte() })
        try {
            PakFileSystemProvider().create(pakPath)
            fail("Expected SilentVfsOpenException for a file that is neither PAK nor ZIP")
        } catch (_: SilentVfsOpenException) {
            // expected
        }
    }

    @Test
    fun `registry recognises pak and pk3 extensions`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("game.pak"))
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("baseq3.pk3"))
    }
}
