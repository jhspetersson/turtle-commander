package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
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

    /**
     * Regression: when a tar contains a symlink and the host filesystem cannot create one
     * (e.g. Windows without SeCreateSymbolicLinkPrivilege), repack must still re-emit the
     * symlink entry so round-tripping doesn't silently strip it.
     */
    @Test
    fun `symlink preserved on repack when local symlink creation fails`() = runBlocking {
        // Close the default vfs from setUp; we rebuild from scratch with a symlink entry.
        vfs.close()

        val withSymlink = Files.createTempFile("test-symlink-archive-", ".tar")
        try {
            TarArchiveOutputStream(Files.newOutputStream(withSymlink)).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

                val content = "regular".toByteArray()
                val fileEntry = TarArchiveEntry("regular.txt")
                fileEntry.size = content.size.toLong()
                tar.putArchiveEntry(fileEntry)
                tar.write(content)
                tar.closeArchiveEntry()

                val linkEntry = TarArchiveEntry("link-to-regular", TarArchiveEntry.LF_SYMLINK)
                linkEntry.linkName = "regular.txt"
                tar.putArchiveEntry(linkEntry)
                tar.closeArchiveEntry()
            }

            // Inject an extraction that skips actual symlink creation regardless of host OS —
            // mirrors the failure path taken on Windows without privileges.
            val innerFs = TarVirtualFileSystem(
                withSymlink,
                inputStreamFactory = { Files.newInputStream(it) },
                outputStreamFactory = { Files.newOutputStream(it) },
            )
            innerFs.use { innerFs ->
                // Force the "failed to materialize symlink" code path by clearing any that did
                // succeed, and re-injecting via the extract side effect. On Linux/macOS the
                // symlink *will* have been created; rename still works. We directly verify the
                // repack's unresolved-symlink fallback by removing the file and re-renaming.
                val link = innerFs.root.resolve("link-to-regular")
                if (Files.isSymbolicLink(link)) {
                    Files.delete(link)
                    // Mark as unresolved using the internal map so repack re-emits it.
                    innerFs.markUnresolvedSymlinkForTest("link-to-regular", "regular.txt")
                }

                // Rename the regular file to trigger a repack.
                innerFs.renameFile(innerFs.root.resolve("regular.txt"), "renamed.txt")
            }

            // Re-read the archive and assert the symlink entry is present with the original target.
            val entries = mutableListOf<Pair<String, String?>>()
            Files.newInputStream(withSymlink).use { raw ->
                TarArchiveInputStream(raw).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        entries.add(entry.name to if (entry.isSymbolicLink) entry.linkName else null)
                        entry = tar.nextEntry
                    }
                }
            }
            val symlinkEntry = entries.find { it.first == "link-to-regular" }
            assertNotNull("symlink must survive repack", symlinkEntry)
            assertEquals("regular.txt", symlinkEntry!!.second)
            assertTrue("renamed file must be present", entries.any { it.first == "renamed.txt" })
        } finally {
            Files.deleteIfExists(withSymlink)
            // Re-create the original vfs so tearDown can close it safely.
            vfs = TarVirtualFileSystem(
                tarPath,
                inputStreamFactory = { Files.newInputStream(it) },
                outputStreamFactory = { Files.newOutputStream(it) },
            )
        }
    }
}
