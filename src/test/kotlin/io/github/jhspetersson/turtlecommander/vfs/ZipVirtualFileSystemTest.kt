package io.github.jhspetersson.turtlecommander.vfs

import io.github.jhspetersson.turtlecommander.service.FileOperationService
import io.github.jhspetersson.turtlecommander.service.OverwritePolicy
import io.github.jhspetersson.turtlecommander.service.OverwriteResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
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
    fun `entry surfaces stored unix mode as permissions`() = runBlocking {
        val zip = Files.createTempFile("test-perm-", ".zip")
        try {
            ZipArchiveOutputStream(Files.newOutputStream(zip)).use { out ->
                val content = "data".toByteArray()
                val entry = ZipArchiveEntry("script.sh")
                entry.unixMode = 0b111_101_101 // 0755 rwxr-xr-x
                entry.size = content.size.toLong()
                out.putArchiveEntry(entry)
                out.write(content)
                out.closeArchiveEntry()
            }
            ZipVirtualFileSystem(zip).use { fs ->
                val entry = fs.listFiles(fs.root).first { it.name == "script.sh" }
                assertEquals("rwxr-xr-x", entry.permissions)
                // ZIP carries no owner/group names.
                assertEquals("", entry.owner)
                assertEquals("", entry.group)
            }
        } finally {
            Files.deleteIfExists(zip)
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
 * Exercises the fallback path used for zips that Java's built-in ZipFileSystem
 * rejects (e.g. invalid CEN headers). Even though we build valid zips here,
 * ZipExtractVirtualFileSystem can be instantiated directly. It materialises
 * lazily: opening stubs the directory tree with size-correct placeholder files,
 * and real bytes are only streamed on [VirtualFileSystem.materialize] (or any
 * repack, which must not write stub zeros into the rebuilt archive).
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
    fun `open stubs the tree and materialize streams real content`() = runBlocking {
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

        // Lazy contract: before materialization the stub has the right size but no content.
        val aPath = extractVfs.getPath("/a.txt")
        assertEquals("alpha".length.toLong(), Files.size(aPath))
        assertNotEquals("alpha", Files.readString(aPath))

        // After materialization the real bytes are in place (and a second call is a no-op).
        extractVfs.materialize(aPath)
        assertEquals("alpha", Files.readString(aPath))
        extractVfs.materialize(aPath)
        assertEquals("alpha", Files.readString(aPath))

        val bPath = extractVfs.getPath("/sub/b.txt")
        extractVfs.materialize(bPath)
        assertEquals("beta", Files.readString(bPath))
    }

    @Test
    fun `registry dispatch materializes through OpenVfsRegistry`() = runBlocking {
        createZip("payload.txt" to "registry-routed")
        val extractVfs = ZipExtractVirtualFileSystem(zipPath)
        vfs = extractVfs

        val path = extractVfs.getPath("/payload.txt")
        OpenVfsRegistry.materializeIfNeeded(path)
        assertEquals("registry-routed", Files.readString(path))
    }

    @Test
    fun `repack materializes pending stubs instead of writing zeros`() = runBlocking {
        createZip(
            "kept.txt" to "kept-content",
            "renamed.txt" to "renamed-content",
        )
        val extractVfs = ZipExtractVirtualFileSystem(zipPath)
        vfs = extractVfs

        // renameFile triggers a repack while both entries are still unmaterialized stubs.
        extractVfs.renameFile(extractVfs.getPath("/renamed.txt"), "after.txt")

        // Read the rewritten archive with a completely fresh VFS: every entry must carry
        // its real bytes — a zero-filled stub leaking into the repack would show up here.
        extractVfs.close()
        vfs = null
        val reopened = ZipExtractVirtualFileSystem(zipPath)
        vfs = reopened
        val kept = reopened.getPath("/kept.txt")
        reopened.materialize(kept)
        assertEquals("kept-content", Files.readString(kept))
        val renamed = reopened.getPath("/after.txt")
        reopened.materialize(renamed)
        assertEquals("renamed-content", Files.readString(renamed))
    }

    @Test
    fun `materialize then flush does not rewrite the archive`() = runBlocking {
        createZip("read-only-use.txt" to "just reading")
        val extractVfs = ZipExtractVirtualFileSystem(zipPath)
        vfs = extractVfs

        val bytesBefore = Files.readAllBytes(zipPath)
        extractVfs.materialize(extractVfs.getPath("/read-only-use.txt"))
        extractVfs.flush()

        assertArrayEquals(
            "copying a file out (materialize + flush) must leave the archive byte-identical",
            bytesBefore,
            Files.readAllBytes(zipPath),
        )
    }

    @Test
    fun `overwriting a pending stub survives the repack`() = runBlocking {
        createZip("o.txt" to "original")
        val extractVfs = ZipExtractVirtualFileSystem(zipPath)
        vfs = extractVfs

        // Simulates copy-into-archive replacing an existing, still-unmaterialized entry.
        Files.writeString(extractVfs.getPath("/o.txt"), "user-overwrite")
        extractVfs.flush()

        extractVfs.close()
        vfs = null
        val reopened = ZipExtractVirtualFileSystem(zipPath)
        vfs = reopened
        val p = reopened.getPath("/o.txt")
        reopened.materialize(p)
        assertEquals("the user's bytes must win over the original entry", "user-overwrite", Files.readString(p))
    }

    @Test
    fun `deleting a pending stub is not resurrected by the repack`() = runBlocking {
        createZip("del.txt" to "doomed", "keep.txt" to "kept")
        val extractVfs = ZipExtractVirtualFileSystem(zipPath)
        vfs = extractVfs

        Files.delete(extractVfs.getPath("/del.txt"))
        extractVfs.flush()

        extractVfs.close()
        vfs = null
        val reopened = ZipExtractVirtualFileSystem(zipPath)
        vfs = reopened
        assertFalse("deleted entry must not come back", Files.exists(reopened.getPath("/del.txt")))
        val keep = reopened.getPath("/keep.txt")
        reopened.materialize(keep)
        assertEquals("kept", Files.readString(keep))
    }

    @Test
    fun `entries are still materializable after a clean flush`() = runBlocking {
        // A clean flush with an unchanged archive keeps the temp dir (and the open
        // reader) as is — pending stubs must remain materializable afterwards.
        createZip("f.txt" to "flush-me")
        val extractVfs = ZipExtractVirtualFileSystem(zipPath)
        vfs = extractVfs

        extractVfs.flush()

        val path = extractVfs.getPath("/f.txt")
        extractVfs.materialize(path)
        assertEquals("flush-me", Files.readString(path))
    }

    @Test
    fun `moving an unmaterialized entry out of the archive carries real bytes`() = runBlocking {
        // Lazy-VFS temp dirs live on the default filesystem, so a move out of the archive
        // takes the same-filesystem Files.move fast path — which must materialize first
        // instead of relocating the zero-filled stub.
        createZip("move-me.txt" to "real bytes")
        val extractVfs = ZipExtractVirtualFileSystem(zipPath)
        vfs = extractVfs

        val destDir = Files.createTempDirectory("zip-extract-move-out-")
        try {
            val service = FileOperationService(CoroutineScope(Dispatchers.Unconfined))
            service.moveFilesWithProgress(
                sources = listOf(extractVfs.getPath("/move-me.txt")),
                destination = destDir,
                initialPolicy = OverwritePolicy.OVERWRITE_ALL,
                onProgress = { _, _ -> },
                onOverwriteConfirm = { OverwriteResponse.OVERWRITE_ALL },
                onError = { _, e -> fail("Unexpected error: $e") },
                isCancelled = { false },
            )
            assertEquals("real bytes", Files.readString(destDir.resolve("move-me.txt")))
        } finally {
            destDir.toFile().deleteRecursively()
        }
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
