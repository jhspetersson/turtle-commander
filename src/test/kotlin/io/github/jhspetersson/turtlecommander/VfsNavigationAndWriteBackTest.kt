package io.github.jhspetersson.turtlecommander

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ─── Zip flush path validity ────────────────────────────────────────────────
// After flush(), paths must remain absolute so that .parent works for navigation.

class ZipFlushPathValidityTest {

    private lateinit var zipPath: Path
    private lateinit var vfs: ZipVirtualFileSystem

    private fun createZip(vararg entries: Pair<String, String?>) {
        zipPath = Files.createTempFile("test-flush-", ".zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                if (content != null) zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        vfs = ZipVirtualFileSystem(zipPath)
    }

    @After
    fun tearDown() {
        vfs.close()
        Files.deleteIfExists(zipPath)
    }

    @Test
    fun `root resolve produces absolute path in subdir`() {
        createZip("subdir/" to null, "subdir/file.txt" to "data")
        vfs.flush()

        val path = vfs.root.resolve("subdir")
        assertNotNull("Absolute path in zip should have a parent", path.parent)
        assertEquals("/", path.parent.toString())
    }

    @Test
    fun `root resolve produces absolute path in nested subdir`() {
        createZip("a/" to null, "a/b/" to null, "a/b/file.txt" to "data")
        vfs.flush()

        val path = vfs.root.resolve("a/b")
        assertNotNull("Nested path should have a parent", path.parent)
        assertEquals("/a", path.parent.toString())
    }

    @Test
    fun `getPath with relative string has null parent for single component`() {
        createZip("subdir/" to null)

        // This demonstrates the bug: getPath("subdir") returns a relative path
        val relativePath = vfs.getPath("subdir")
        assertNull(
            "Relative single-component zip path has null parent — this is why root.resolve must be used instead",
            relativePath.parent,
        )
    }

    @Test
    fun `root resolve path parent navigates correctly after flush`() = runBlocking {
        createZip("subdir/" to null, "subdir/file.txt" to "data")
        vfs.flush()

        val subdirPath = vfs.root.resolve("subdir")
        val parentPath = subdirPath.parent!!

        // Should be able to list files at the parent (root)
        val entries = vfs.listFiles(parentPath)
        assertTrue("Should find subdir at root after navigating up", entries.any { it.name == "subdir" })
    }

    @Test
    fun `multiple flushes keep paths valid`() = runBlocking {
        createZip("subdir/" to null, "subdir/file.txt" to "data")

        repeat(3) {
            vfs.flush()
            val path = vfs.root.resolve("subdir")
            assertNotNull("Parent should not be null after flush #${it + 1}", path.parent)
            val entries = vfs.listFiles(path)
            assertTrue("Should find file.txt after flush #${it + 1}", entries.any { e -> e.name == "file.txt" })
        }
    }
}

// ─── VirtualFileSystem read-only flag ────────────────────────────────────────

class VfsReadOnlyFlagTest {

    private val tempFiles = mutableListOf<Path>()
    private val vfsList = mutableListOf<VirtualFileSystem>()

    @After
    fun tearDown() {
        vfsList.forEach { try { it.close() } catch (_: Exception) {} }
        tempFiles.forEach { try { Files.deleteIfExists(it) } catch (_: Exception) {} }
    }

    @Test
    fun `compressed single file VFS is read-only`() {
        val path = Files.createTempFile("test-ro-", ".fakegz")
        tempFiles.add(path)
        Files.write(path, "content".toByteArray())
        val vfs = CompressedSingleFileVirtualFileSystem(path, ".fakegz") { it }
        vfsList.add(vfs)

        assertTrue("CompressedSingleFileVirtualFileSystem should be read-only", vfs.isReadOnly)
    }

    @Test
    fun `zip VFS is not read-only`() {
        val path = Files.createTempFile("test-rw-", ".zip")
        tempFiles.add(path)
        ZipOutputStream(Files.newOutputStream(path)).use { zos ->
            zos.putNextEntry(ZipEntry("file.txt"))
            zos.write("data".toByteArray())
            zos.closeEntry()
        }
        val vfs = ZipVirtualFileSystem(path)
        vfsList.add(vfs)

        assertFalse("ZipVirtualFileSystem should not be read-only", vfs.isReadOnly)
    }

    @Test
    fun `tar VFS is not read-only`() {
        val path = Files.createTempFile("test-rw-", ".tar")
        tempFiles.add(path)
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(Files.newOutputStream(path)).use { tar ->
            val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry("file.txt")
            val bytes = "data".toByteArray()
            entry.size = bytes.size.toLong()
            tar.putArchiveEntry(entry)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
        val vfs = TarVirtualFileSystem(path, inputStreamFactory = { Files.newInputStream(it) }, outputStreamFactory = { Files.newOutputStream(it) })
        vfsList.add(vfs)

        assertFalse("TarVirtualFileSystem should not be read-only", vfs.isReadOnly)
    }
}

// ─── Nested archive write-back ──────────────────────────────────────────────
// Simulates the write-back logic: modifying a zip inside a zip and propagating
// changes to the outer archive.

class NestedArchiveWriteBackTest {

    private val tempFiles = mutableListOf<Path>()

    @After
    fun tearDown() {
        tempFiles.asReversed().forEach {
            try { Files.deleteIfExists(it) } catch (_: Exception) {}
        }
    }

    private fun createZipBytes(vararg entries: Pair<String, String?>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                if (content != null) zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun createOuterZipWithInnerZip(innerEntries: Array<Pair<String, String?>>): Path {
        val innerBytes = createZipBytes(*innerEntries)
        val outerPath = Files.createTempFile("outer-", ".zip")
        tempFiles.add(outerPath)
        ZipOutputStream(Files.newOutputStream(outerPath)).use { zos ->
            zos.putNextEntry(ZipEntry("inner.zip"))
            zos.write(innerBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("outer-file.txt"))
            zos.write("outer content".toByteArray())
            zos.closeEntry()
        }
        return outerPath
    }

    @Test
    fun `copy file into inner zip and write back to outer zip`() = runBlocking {
        val outerPath = createOuterZipWithInnerZip(arrayOf("existing.txt" to "hello"))
        val outerVfs = ZipVirtualFileSystem(outerPath)

        outerVfs.use { outerVfs ->
            // Extract inner.zip to temp (simulating enterVfs for nested archive)
            val innerZipPathInOuter = outerVfs.getPath("/inner.zip")
            val tempDir = Files.createTempDirectory("turtle-vfs-test-")
            val tempInnerZip = tempDir.resolve("inner.zip")
            Files.copy(innerZipPathInOuter, tempInnerZip)
            tempFiles.add(tempInnerZip)
            tempFiles.add(tempDir)

            val innerVfs = ZipVirtualFileSystem(tempInnerZip)
            innerVfs.use { innerVfs ->
                // Copy a new file into the inner zip's root
                val newFilePath = innerVfs.getPath("/newfile.txt")
                Files.write(newFilePath, "new content".toByteArray())

                // Flush inner VFS to persist the new file in the temp zip
                innerVfs.flush()

                // Verify file exists in inner VFS
                val innerEntries = innerVfs.listFiles(innerVfs.root)
                assertTrue("Inner zip should contain newfile.txt", innerEntries.any { it.name == "newfile.txt" })
                assertTrue("Inner zip should still contain existing.txt", innerEntries.any { it.name == "existing.txt" })

                // Write back: copy temp inner zip back to outer VFS
                Files.copy(tempInnerZip, innerZipPathInOuter, StandardCopyOption.REPLACE_EXISTING)
                outerVfs.flush()

                // Verify the outer zip was updated by reopening everything
                val updatedInnerPath = outerVfs.getPath("/inner.zip")
                val verifyDir = Files.createTempDirectory("turtle-verify-")
                val verifyInner = verifyDir.resolve("inner.zip")
                Files.copy(updatedInnerPath, verifyInner)
                tempFiles.add(verifyInner)
                tempFiles.add(verifyDir)

                val verifyVfs = ZipVirtualFileSystem(verifyInner)
                try {
                    val verifyEntries = verifyVfs.listFiles(verifyVfs.root)
                    assertTrue("Propagated inner zip should contain newfile.txt", verifyEntries.any { it.name == "newfile.txt" })
                    assertTrue("Propagated inner zip should contain existing.txt", verifyEntries.any { it.name == "existing.txt" })
                } finally {
                    verifyVfs.close()
                }

                // Outer archive's other files should be intact
                val outerEntries = outerVfs.listFiles(outerVfs.root)
                assertTrue("Outer zip should still contain outer-file.txt", outerEntries.any { it.name == "outer-file.txt" })
            }
        }
    }

    @Test
    fun `write-back updates parentPath to valid path in reopened filesystem`() = runBlocking {
        val outerPath = createOuterZipWithInnerZip(arrayOf("file.txt" to "data"))
        val outerVfs = ZipVirtualFileSystem(outerPath)

        outerVfs.use { outerVfs ->
            // Get the initial path for inner.zip in outer VFS
            val innerPathBefore = outerVfs.getPath("/inner.zip")

            // Extract to temp
            val tempDir = Files.createTempDirectory("turtle-vfs-test-")
            val tempInnerZip = tempDir.resolve("inner.zip")
            Files.copy(innerPathBefore, tempInnerZip)
            tempFiles.add(tempInnerZip)
            tempFiles.add(tempDir)

            // Simulate write-back: copy and flush
            Files.copy(tempInnerZip, innerPathBefore, StandardCopyOption.REPLACE_EXISTING)

            // Save relative path before flushing
            val rootStr = outerVfs.root.toString().trimEnd('/')
            val pathStr = innerPathBefore.toString()
            val relPath = pathStr.removePrefix(rootStr).removePrefix("/")

            outerVfs.flush()

            // Reconstruct path in the new filesystem (same as writeBackNestedArchives does)
            val innerPathAfter = outerVfs.root.resolve(relPath)

            // The reconstructed path should be usable
            assertTrue("Reconstructed path should exist", Files.exists(innerPathAfter))
            assertNotNull("Reconstructed path should have a parent", innerPathAfter.parent)

            // Should be able to navigate to parent (root)
            val rootEntries = outerVfs.listFiles(innerPathAfter.parent!!)
            assertTrue("Should find inner.zip at root", rootEntries.any { it.name == "inner.zip" })
        }
    }

    @Test
    fun `modifying file content in inner zip propagates to outer zip`() = runBlocking {
        val outerPath = createOuterZipWithInnerZip(arrayOf("data.txt" to "original"))
        val outerVfs = ZipVirtualFileSystem(outerPath)

        outerVfs.use { outerVfs ->
            val innerPathInOuter = outerVfs.getPath("/inner.zip")
            val tempDir = Files.createTempDirectory("turtle-vfs-test-")
            val tempInnerZip = tempDir.resolve("inner.zip")
            Files.copy(innerPathInOuter, tempInnerZip)
            tempFiles.add(tempInnerZip)
            tempFiles.add(tempDir)

            val innerVfs = ZipVirtualFileSystem(tempInnerZip)
            innerVfs.use { innerVfs ->
                // Modify the existing file
                val filePath = innerVfs.getPath("/data.txt")
                Files.writeString(filePath, "modified")
                innerVfs.flush()

                // Write back
                Files.copy(tempInnerZip, innerPathInOuter, StandardCopyOption.REPLACE_EXISTING)
                outerVfs.flush()

                // Verify by reopening
                val verifyDir = Files.createTempDirectory("turtle-verify-")
                val verifyInner = verifyDir.resolve("inner.zip")
                Files.copy(outerVfs.getPath("/inner.zip"), verifyInner)
                tempFiles.add(verifyInner)
                tempFiles.add(verifyDir)

                val verifyVfs = ZipVirtualFileSystem(verifyInner)
                verifyVfs.use { verifyVfs ->
                    val entries = verifyVfs.listFiles(verifyVfs.root)
                    val dataFile = entries.find { it.name == "data.txt" }
                    assertNotNull("data.txt should exist", dataFile)
                    assertEquals("modified", Files.readString(dataFile!!.path))
                }
            }
        }
    }

    @Test
    fun `deleting file from inner zip propagates to outer zip`() = runBlocking {
        val outerPath = createOuterZipWithInnerZip(arrayOf("keep.txt" to "keep", "remove.txt" to "remove"))
        val outerVfs = ZipVirtualFileSystem(outerPath)

        outerVfs.use { outerVfs ->
            val innerPathInOuter = outerVfs.getPath("/inner.zip")
            val tempDir = Files.createTempDirectory("turtle-vfs-test-")
            val tempInnerZip = tempDir.resolve("inner.zip")
            Files.copy(innerPathInOuter, tempInnerZip)
            tempFiles.add(tempInnerZip)
            tempFiles.add(tempDir)

            val innerVfs = ZipVirtualFileSystem(tempInnerZip)
            innerVfs.use { innerVfs ->
                Files.delete(innerVfs.getPath("/remove.txt"))
                innerVfs.flush()

                Files.copy(tempInnerZip, innerPathInOuter, StandardCopyOption.REPLACE_EXISTING)
                outerVfs.flush()

                // Verify
                val verifyDir = Files.createTempDirectory("turtle-verify-")
                val verifyInner = verifyDir.resolve("inner.zip")
                Files.copy(outerVfs.getPath("/inner.zip"), verifyInner)
                tempFiles.add(verifyInner)
                tempFiles.add(verifyDir)

                val verifyVfs = ZipVirtualFileSystem(verifyInner)
                verifyVfs.use { verifyVfs ->
                    val entries = verifyVfs.listFiles(verifyVfs.root).filter { !it.isParentLink }
                    assertTrue("keep.txt should remain", entries.any { it.name == "keep.txt" })
                    assertFalse("remove.txt should be gone", entries.any { it.name == "remove.txt" })
                }
            }
        }
    }

    @Test
    fun `renaming file in inner zip propagates to outer zip`() = runBlocking {
        val outerPath = createOuterZipWithInnerZip(arrayOf("old.txt" to "content"))
        val outerVfs = ZipVirtualFileSystem(outerPath)

        outerVfs.use { outerVfs ->
            val innerPathInOuter = outerVfs.getPath("/inner.zip")
            val tempDir = Files.createTempDirectory("turtle-vfs-test-")
            val tempInnerZip = tempDir.resolve("inner.zip")
            Files.copy(innerPathInOuter, tempInnerZip)
            tempFiles.add(tempInnerZip)
            tempFiles.add(tempDir)

            val innerVfs = ZipVirtualFileSystem(tempInnerZip)
            innerVfs.use { innerVfs ->
                innerVfs.renameFile(innerVfs.getPath("/old.txt"), "new.txt")
                innerVfs.flush()

                Files.copy(tempInnerZip, innerPathInOuter, StandardCopyOption.REPLACE_EXISTING)
                outerVfs.flush()

                // Verify
                val verifyDir = Files.createTempDirectory("turtle-verify-")
                val verifyInner = verifyDir.resolve("inner.zip")
                Files.copy(outerVfs.getPath("/inner.zip"), verifyInner)
                tempFiles.add(verifyInner)
                tempFiles.add(verifyDir)

                val verifyVfs = ZipVirtualFileSystem(verifyInner)
                verifyVfs.use { verifyVfs ->
                    val entries = verifyVfs.listFiles(verifyVfs.root).filter { !it.isParentLink }
                    assertTrue("new.txt should exist", entries.any { it.name == "new.txt" })
                    assertFalse("old.txt should be gone", entries.any { it.name == "old.txt" })
                }
            }
        }
    }
}

// ─── Zip navigation after copy (go-up regression) ───────────────────────────
// Verifies that after copying into a zip subfolder and flushing, navigation
// via .parent still works — the original bug where relative paths broke goUp.

class ZipNavigationAfterFlushTest {

    private lateinit var zipPath: Path
    private lateinit var vfs: ZipVirtualFileSystem

    private fun createZip(vararg entries: Pair<String, String?>) {
        zipPath = Files.createTempFile("test-nav-", ".zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                if (content != null) zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        vfs = ZipVirtualFileSystem(zipPath)
    }

    @After
    fun tearDown() {
        vfs.close()
        Files.deleteIfExists(zipPath)
    }

    @Test
    fun `can navigate up from subdir after copy and flush`() = runBlocking {
        createZip("subdir/" to null, "subdir/existing.txt" to "data")

        // Simulate: copy a file into subdir, then flush (as refreshAfterVfsChange does)
        val subdirPath = vfs.getPath("/subdir")
        val newFile = subdirPath.resolve("copied.txt")
        Files.write(newFile, "copied".toByteArray())

        // Capture relative path, flush, reconstruct with root.resolve (the fix)
        val rootStr = vfs.root.toString().trimEnd('/')
        val relPath = subdirPath.toString().removePrefix(rootStr).removePrefix("/")
        vfs.flush()
        val newSubdirPath = vfs.root.resolve(relPath)

        // Verify we can navigate up
        val parent = newSubdirPath.parent
        assertNotNull("Should be able to get parent after flush", parent)

        val rootEntries = vfs.listFiles(parent!!)
        assertTrue("Root should contain subdir", rootEntries.any { it.name == "subdir" })
    }

    @Test
    fun `can navigate up multiple levels after flush`() = runBlocking {
        createZip("a/" to null, "a/b/" to null, "a/b/file.txt" to "data")

        vfs.flush()
        val deepPath = vfs.root.resolve("a/b")

        // Navigate up to "a"
        val aPath = deepPath.parent
        assertNotNull(aPath)
        val aEntries = vfs.listFiles(aPath!!)
        assertTrue("Should find dir 'b' in 'a'", aEntries.any { it.name == "b" })

        // Navigate up to root
        val rootPath = aPath.parent
        assertNotNull(rootPath)
        val rootEntries = vfs.listFiles(rootPath!!)
        assertTrue("Should find dir 'a' at root", rootEntries.any { it.name == "a" })
    }

    @Test
    fun `isRoot check works with root resolve path`() {
        createZip("subdir/" to null)
        vfs.flush()

        val subdirPath = vfs.root.resolve("subdir")
        assertFalse("subdir should not be root", vfs.isRoot(subdirPath))
        assertTrue("root should be root", vfs.isRoot(vfs.root))

        // Navigate up from subdir should reach root
        val parent = subdirPath.parent!!
        assertTrue("Parent of subdir should be root", vfs.isRoot(parent))
    }

    @Test
    fun `copy into root then flush preserves navigation`() = runBlocking {
        createZip("existing.txt" to "data")

        // Copy file into root
        val newFile = vfs.getPath("/newfile.txt")
        Files.write(newFile, "new".toByteArray())

        vfs.flush()

        // Root should still be navigable
        val entries = vfs.listFiles(vfs.root)
        assertTrue("Should contain existing.txt", entries.any { it.name == "existing.txt" })
        // The copied file was flushed (re-read from archive), so it persists
        assertTrue("Should contain newfile.txt", entries.any { it.name == "newfile.txt" })
    }
}

// ─── File creation inside archives ──────────────────────────────────────────

class ZipFileCreationTest {

    private lateinit var zipPath: Path
    private lateinit var vfs: ZipVirtualFileSystem

    private fun createZip(vararg entries: Pair<String, String?>) {
        zipPath = Files.createTempFile("test-create-", ".zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                if (content != null) zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        vfs = ZipVirtualFileSystem(zipPath)
    }

    @After
    fun tearDown() {
        vfs.close()
        Files.deleteIfExists(zipPath)
    }

    @Test
    fun `create file at zip root persists after flush`() = runBlocking {
        createZip("existing.txt" to "data")

        val newFile = vfs.getPath("/newfile.txt")
        Files.write(newFile, "new content".toByteArray())
        vfs.flush()

        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        assertTrue("Should contain existing.txt", entries.any { it.name == "existing.txt" })
        assertTrue("Should contain newfile.txt", entries.any { it.name == "newfile.txt" })
    }

    @Test
    fun `create file in zip subdir persists after flush`() = runBlocking {
        createZip("subdir/" to null, "subdir/existing.txt" to "data")

        val newFile = vfs.getPath("/subdir/newfile.txt")
        Files.write(newFile, "new content".toByteArray())
        vfs.flush()

        val subdirPath = vfs.root.resolve("subdir")
        val entries = vfs.listFiles(subdirPath).filter { !it.isParentLink }
        assertTrue("Should contain existing.txt", entries.any { it.name == "existing.txt" })
        assertTrue("Should contain newfile.txt", entries.any { it.name == "newfile.txt" })
    }

    @Test
    fun `create file content is correct after flush`() = runBlocking {
        createZip("placeholder.txt" to "x")

        val newFile = vfs.getPath("/created.txt")
        Files.writeString(newFile, "hello world")
        vfs.flush()

        val entries = vfs.listFiles(vfs.root)
        val created = entries.find { it.name == "created.txt" }
        assertNotNull("created.txt should exist", created)
        assertEquals("hello world", Files.readString(created!!.path))
    }

    @Test
    fun `create file persists across close and reopen`() = runBlocking {
        createZip("original.txt" to "data")

        Files.write(vfs.getPath("/added.txt"), "added".toByteArray())
        vfs.close()

        // Reopen
        vfs = ZipVirtualFileSystem(zipPath)
        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        assertTrue("Should contain original.txt", entries.any { it.name == "original.txt" })
        assertTrue("Should contain added.txt", entries.any { it.name == "added.txt" })
    }

    @Test
    fun `create directory at zip root persists after flush`() = runBlocking {
        createZip("existing.txt" to "data")

        Files.createDirectory(vfs.getPath("/newdir"))
        vfs.flush()

        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        val newdir = entries.find { it.name == "newdir" }
        assertNotNull("newdir should exist", newdir)
        assertTrue("newdir should be a directory", newdir!!.isDirectory)
    }

    @Test
    fun `create file in new directory persists after flush`() = runBlocking {
        createZip("existing.txt" to "data")

        Files.createDirectory(vfs.getPath("/newdir"))
        Files.write(vfs.getPath("/newdir/file.txt"), "inside new dir".toByteArray())
        vfs.flush()

        val subdirPath = vfs.root.resolve("newdir")
        val entries = vfs.listFiles(subdirPath).filter { !it.isParentLink }
        assertEquals(1, entries.size)
        assertEquals("file.txt", entries[0].name)
    }

    @Test
    fun `navigation works after creating file and flushing`() = runBlocking {
        createZip("subdir/" to null)

        Files.write(vfs.getPath("/subdir/newfile.txt"), "data".toByteArray())
        vfs.flush()

        val subdirPath = vfs.root.resolve("subdir")
        val parent = subdirPath.parent
        assertNotNull("Should be able to navigate up after create+flush", parent)
        assertTrue("Parent should be root", vfs.isRoot(parent!!))
    }
}

class TarFileCreationTest {

    private lateinit var tarPath: Path
    private lateinit var vfs: TarVirtualFileSystem

    private fun createTar(vararg entries: Triple<String, Boolean, String?>) {
        tarPath = Files.createTempFile("test-create-", ".tar")
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, isDir, content) in entries) {
                val entry = TarArchiveEntry(name)
                if (isDir) {
                    tar.putArchiveEntry(entry)
                } else {
                    val bytes = content?.toByteArray() ?: ByteArray(0)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                }
                tar.closeArchiveEntry()
            }
        }
        vfs = TarVirtualFileSystem(tarPath, inputStreamFactory = { Files.newInputStream(it) }, outputStreamFactory = { Files.newOutputStream(it) })
    }

    @After
    fun tearDown() {
        vfs.close()
        Files.deleteIfExists(tarPath)
    }

    @Test
    fun `create file at tar root persists after flush`() = runBlocking {
        createTar(Triple("existing.txt", false, "data"))

        Files.writeString(vfs.root.resolve("newfile.txt"), "new content")
        vfs.flush()

        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        assertTrue("Should contain existing.txt", entries.any { it.name == "existing.txt" })
        assertTrue("Should contain newfile.txt", entries.any { it.name == "newfile.txt" })
    }

    @Test
    fun `create file in tar subdir persists after flush`() = runBlocking {
        createTar(Triple("subdir/", true, null), Triple("subdir/existing.txt", false, "data"))

        Files.writeString(vfs.root.resolve("subdir/newfile.txt"), "new content")
        vfs.flush()

        val entries = vfs.listFiles(vfs.root.resolve("subdir")).filter { !it.isParentLink }
        assertTrue("Should contain existing.txt", entries.any { it.name == "existing.txt" })
        assertTrue("Should contain newfile.txt", entries.any { it.name == "newfile.txt" })
    }

    @Test
    fun `create file persists across flush close and reopen`() = runBlocking {
        createTar(Triple("original.txt", false, "data"))

        Files.writeString(vfs.root.resolve("added.txt"), "added")
        vfs.flush()
        vfs.close()

        // Reopen
        vfs = TarVirtualFileSystem(tarPath, inputStreamFactory = { Files.newInputStream(it) }, outputStreamFactory = { Files.newOutputStream(it) })
        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        assertTrue("Should contain original.txt", entries.any { it.name == "original.txt" })
        assertTrue("Should contain added.txt", entries.any { it.name == "added.txt" })
    }

    @Test
    fun `create directory at tar root persists after flush`() = runBlocking {
        createTar(Triple("existing.txt", false, "data"))

        Files.createDirectory(vfs.root.resolve("newdir"))
        vfs.flush()

        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        val newdir = entries.find { it.name == "newdir" }
        assertNotNull("newdir should exist", newdir)
        assertTrue("newdir should be a directory", newdir!!.isDirectory)
    }
}

// ─── File creation inside nested archives ───────────────────────────────────

class NestedArchiveFileCreationTest {

    private val tempFiles = mutableListOf<Path>()
    private val vfsList = mutableListOf<VirtualFileSystem>()

    @After
    fun tearDown() {
        vfsList.asReversed().forEach { try { it.close() } catch (_: Exception) {} }
        tempFiles.asReversed().forEach { try { Files.deleteIfExists(it) } catch (_: Exception) {} }
    }

    private fun createZipBytes(vararg entries: Pair<String, String?>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                if (content != null) zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun createOuterZipWithInnerZip(
        innerEntries: Array<Pair<String, String?>>,
        outerExtraEntries: Array<Pair<String, String?>> = emptyArray(),
    ): Path {
        val innerBytes = createZipBytes(*innerEntries)
        val outerPath = Files.createTempFile("outer-", ".zip")
        tempFiles.add(outerPath)
        ZipOutputStream(Files.newOutputStream(outerPath)).use { zos ->
            zos.putNextEntry(ZipEntry("inner.zip"))
            zos.write(innerBytes)
            zos.closeEntry()
            for ((name, content) in outerExtraEntries) {
                zos.putNextEntry(ZipEntry(name))
                if (content != null) zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return outerPath
    }

    private fun extractInnerZip(outerVfs: ZipVirtualFileSystem): Pair<ZipVirtualFileSystem, java.io.File> {
        val innerZipPathInOuter = outerVfs.getPath("/inner.zip")
        val tempDir = Files.createTempDirectory("turtle-vfs-test-")
        val tempInnerZip = tempDir.resolve("inner.zip")
        Files.copy(innerZipPathInOuter, tempInnerZip)
        tempFiles.add(tempInnerZip)
        tempFiles.add(tempDir)
        val innerVfs = ZipVirtualFileSystem(tempInnerZip)
        vfsList.add(innerVfs)
        return innerVfs to tempInnerZip.toFile()
    }

    private suspend fun writeBackAndVerifyInner(
        outerVfs: ZipVirtualFileSystem,
        innerVfs: ZipVirtualFileSystem,
        tempInnerZip: java.io.File,
    ): List<FileEntry> {
        innerVfs.flush()
        val innerZipPathInOuter = outerVfs.getPath("/inner.zip")
        Files.copy(tempInnerZip.toPath(), innerZipPathInOuter, StandardCopyOption.REPLACE_EXISTING)
        outerVfs.flush()

        // Reopen inner from outer to verify propagation
        val verifyDir = Files.createTempDirectory("turtle-verify-")
        val verifyInner = verifyDir.resolve("inner.zip")
        Files.copy(outerVfs.getPath("/inner.zip"), verifyInner)
        tempFiles.add(verifyInner)
        tempFiles.add(verifyDir)

        val verifyVfs = ZipVirtualFileSystem(verifyInner)
        vfsList.add(verifyVfs)
        return verifyVfs.listFiles(verifyVfs.root).filter { !it.isParentLink }
    }

    @Test
    fun `create file at inner zip root propagates to outer zip`() = runBlocking {
        val outerPath = createOuterZipWithInnerZip(arrayOf("existing.txt" to "hello"))
        val outerVfs = ZipVirtualFileSystem(outerPath)
        vfsList.add(outerVfs)

        val (innerVfs, tempInnerZip) = extractInnerZip(outerVfs)
        Files.write(innerVfs.getPath("/newfile.txt"), "created".toByteArray())

        val verified = writeBackAndVerifyInner(outerVfs, innerVfs, tempInnerZip)
        assertTrue("Should contain existing.txt", verified.any { it.name == "existing.txt" })
        assertTrue("Should contain newfile.txt", verified.any { it.name == "newfile.txt" })
    }

    @Test
    fun `create file in inner zip subdir propagates to outer zip`() = runBlocking {
        val outerPath = createOuterZipWithInnerZip(arrayOf("subdir/" to null, "subdir/existing.txt" to "data"))
        val outerVfs = ZipVirtualFileSystem(outerPath)
        vfsList.add(outerVfs)

        val (innerVfs, tempInnerZip) = extractInnerZip(outerVfs)
        Files.write(innerVfs.getPath("/subdir/newfile.txt"), "created".toByteArray())

        innerVfs.flush()
        Files.copy(tempInnerZip.toPath(), outerVfs.getPath("/inner.zip"), StandardCopyOption.REPLACE_EXISTING)
        outerVfs.flush()

        // Verify subdir contents
        val verifyDir = Files.createTempDirectory("turtle-verify-")
        val verifyInner = verifyDir.resolve("inner.zip")
        Files.copy(outerVfs.getPath("/inner.zip"), verifyInner)
        tempFiles.add(verifyInner)
        tempFiles.add(verifyDir)
        val verifyVfs = ZipVirtualFileSystem(verifyInner)
        vfsList.add(verifyVfs)

        val subdirEntries = verifyVfs.listFiles(verifyVfs.getPath("/subdir")).filter { !it.isParentLink }
        assertTrue("Should contain existing.txt", subdirEntries.any { it.name == "existing.txt" })
        assertTrue("Should contain newfile.txt", subdirEntries.any { it.name == "newfile.txt" })
    }

    @Test
    fun `create directory in inner zip propagates to outer zip`() = runBlocking {
        val outerPath = createOuterZipWithInnerZip(arrayOf("existing.txt" to "data"))
        val outerVfs = ZipVirtualFileSystem(outerPath)
        vfsList.add(outerVfs)

        val (innerVfs, tempInnerZip) = extractInnerZip(outerVfs)
        Files.createDirectory(innerVfs.getPath("/newdir"))

        val verified = writeBackAndVerifyInner(outerVfs, innerVfs, tempInnerZip)
        val newdir = verified.find { it.name == "newdir" }
        assertNotNull("newdir should exist", newdir)
        assertTrue("newdir should be a directory", newdir!!.isDirectory)
    }
}

// ─── Edit write-back simulation ─────────────────────────────────────────────
// Simulates the VfsEditService.writeBack flow without requiring IntelliJ services.

class EditWriteBackTest {

    private val tempFiles = mutableListOf<Path>()
    private val vfsList = mutableListOf<VirtualFileSystem>()

    @After
    fun tearDown() {
        vfsList.asReversed().forEach { try { it.close() } catch (_: Exception) {} }
        tempFiles.asReversed().forEach { try { Files.deleteIfExists(it) } catch (_: Exception) {} }
    }

    private fun vfsRelativePath(vfs: VirtualFileSystem, path: Path): String {
        val rootStr = vfs.root.toString().trimEnd('/').trimEnd('\\')
        val pathStr = path.toString()
        return if (pathStr.startsWith(rootStr)) {
            pathStr.removePrefix(rootStr).removePrefix("/").removePrefix("\\")
        } else {
            pathStr
        }
    }

    @Test
    fun `edit file at zip root via temp extraction and write-back`() = runBlocking {
        val zipPath = Files.createTempFile("test-edit-", ".zip")
        tempFiles.add(zipPath)
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(ZipEntry("file.txt"))
            zos.write("original".toByteArray())
            zos.closeEntry()
        }
        val vfs = ZipVirtualFileSystem(zipPath)
        vfsList.add(vfs)

        // Extract to temp (simulating openVfsFileEditable)
        val vfsFilePath = vfs.getPath("/file.txt")
        val tempDir = Files.createTempDirectory("turtle-vfs-edit-test-")
        val tempPath = tempDir.resolve("file.txt")
        Files.copy(vfsFilePath, tempPath)
        tempFiles.add(tempPath)
        tempFiles.add(tempDir)

        // Simulate editor save
        Files.writeString(tempPath, "edited")

        // Simulate writeBack
        Files.copy(tempPath, vfsFilePath, StandardCopyOption.REPLACE_EXISTING)
        vfs.flush()

        // Verify
        val entries = vfs.listFiles(vfs.root)
        val file = entries.find { it.name == "file.txt" }
        assertNotNull(file)
        assertEquals("edited", Files.readString(file!!.path))
    }

    @Test
    fun `edit file in zip subdir via temp extraction and write-back`() = runBlocking {
        val zipPath = Files.createTempFile("test-edit-", ".zip")
        tempFiles.add(zipPath)
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(ZipEntry("subdir/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("subdir/file.txt"))
            zos.write("original".toByteArray())
            zos.closeEntry()
        }
        val vfs = ZipVirtualFileSystem(zipPath)
        vfsList.add(vfs)

        val vfsFilePath = vfs.getPath("/subdir/file.txt")
        val tempDir = Files.createTempDirectory("turtle-vfs-edit-test-")
        val tempPath = tempDir.resolve("file.txt")
        Files.copy(vfsFilePath, tempPath)
        tempFiles.add(tempPath)
        tempFiles.add(tempDir)

        Files.writeString(tempPath, "edited in subdir")

        Files.copy(tempPath, vfsFilePath, StandardCopyOption.REPLACE_EXISTING)
        vfs.flush()

        val entries = vfs.listFiles(vfs.root.resolve("subdir"))
        val file = entries.find { it.name == "file.txt" }
        assertNotNull(file)
        assertEquals("edited in subdir", Files.readString(file!!.path))
    }

    @Test
    fun `vfsFilePath reconstructed after flush is still usable for second edit`() = runBlocking {
        val zipPath = Files.createTempFile("test-edit-", ".zip")
        tempFiles.add(zipPath)
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(ZipEntry("file.txt"))
            zos.write("v1".toByteArray())
            zos.closeEntry()
        }
        val vfs = ZipVirtualFileSystem(zipPath)
        vfsList.add(vfs)

        var vfsFilePath = vfs.getPath("/file.txt")
        val tempDir = Files.createTempDirectory("turtle-vfs-edit-test-")
        val tempPath = tempDir.resolve("file.txt")
        Files.copy(vfsFilePath, tempPath)
        tempFiles.add(tempPath)
        tempFiles.add(tempDir)

        // First edit
        Files.writeString(tempPath, "v2")
        Files.copy(tempPath, vfsFilePath, StandardCopyOption.REPLACE_EXISTING)
        val relPath = vfsRelativePath(vfs, vfsFilePath)
        vfs.flush()
        vfsFilePath = vfs.root.resolve(relPath)

        // Second edit (vfsFilePath was reconstructed after first flush)
        Files.writeString(tempPath, "v3")
        Files.copy(tempPath, vfsFilePath, StandardCopyOption.REPLACE_EXISTING)
        vfs.flush()

        val entries = vfs.listFiles(vfs.root)
        val file = entries.find { it.name == "file.txt" }
        assertNotNull(file)
        assertEquals("v3", Files.readString(file!!.path))
    }

    @Test
    fun `edit file in nested zip with full write-back chain`() = runBlocking {
        // Create outer zip containing inner zip containing file.txt
        val innerBytes = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("file.txt"))
                zos.write("original".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()

        val outerPath = Files.createTempFile("outer-edit-", ".zip")
        tempFiles.add(outerPath)
        ZipOutputStream(Files.newOutputStream(outerPath)).use { zos ->
            zos.putNextEntry(ZipEntry("inner.zip"))
            zos.write(innerBytes)
            zos.closeEntry()
        }

        val outerVfs = ZipVirtualFileSystem(outerPath)
        vfsList.add(outerVfs)

        // Extract inner zip to temp
        val tempDir = Files.createTempDirectory("turtle-vfs-test-")
        val tempInnerZip = tempDir.resolve("inner.zip")
        Files.copy(outerVfs.getPath("/inner.zip"), tempInnerZip)
        tempFiles.add(tempInnerZip)
        tempFiles.add(tempDir)

        val innerVfs = ZipVirtualFileSystem(tempInnerZip)
        vfsList.add(innerVfs)

        // Extract file from inner zip to edit temp
        var vfsFilePath = innerVfs.getPath("/file.txt")
        val editDir = Files.createTempDirectory("turtle-vfs-edit-test-")
        val editPath = editDir.resolve("file.txt")
        Files.copy(vfsFilePath, editPath)
        tempFiles.add(editPath)
        tempFiles.add(editDir)

        // Simulate editor save
        Files.writeString(editPath, "edited")

        // Simulate full writeBack:
        // 1. Copy temp back to inner VFS path
        Files.copy(editPath, vfsFilePath, StandardCopyOption.REPLACE_EXISTING)
        // 2. Flush inner VFS
        val innerRelPath = vfsRelativePath(innerVfs, vfsFilePath)
        innerVfs.flush()
        vfsFilePath = innerVfs.root.resolve(innerRelPath)
        // 3. Copy inner temp zip back to outer VFS
        val outerInnerPath = outerVfs.getPath("/inner.zip")
        Files.copy(tempInnerZip, outerInnerPath, StandardCopyOption.REPLACE_EXISTING)
        // 4. Flush outer VFS
        outerVfs.flush()

        // Verify by reopening everything from the outer zip
        val verifyDir = Files.createTempDirectory("turtle-verify-")
        val verifyInner = verifyDir.resolve("inner.zip")
        Files.copy(outerVfs.getPath("/inner.zip"), verifyInner)
        tempFiles.add(verifyInner)
        tempFiles.add(verifyDir)

        val verifyVfs = ZipVirtualFileSystem(verifyInner)
        vfsList.add(verifyVfs)
        val entries = verifyVfs.listFiles(verifyVfs.root)
        val file = entries.find { it.name == "file.txt" }
        assertNotNull(file)
        assertEquals("edited", Files.readString(file!!.path))
    }

    @Test
    fun `multiple edits to same file in nested zip all propagate`() = runBlocking {
        val innerBytes = ByteArrayOutputStream().also { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("file.txt"))
                zos.write("v1".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()

        val outerPath = Files.createTempFile("outer-multi-", ".zip")
        tempFiles.add(outerPath)
        ZipOutputStream(Files.newOutputStream(outerPath)).use { zos ->
            zos.putNextEntry(ZipEntry("inner.zip"))
            zos.write(innerBytes)
            zos.closeEntry()
        }

        val outerVfs = ZipVirtualFileSystem(outerPath)
        vfsList.add(outerVfs)

        val tempDir = Files.createTempDirectory("turtle-vfs-test-")
        val tempInnerZip = tempDir.resolve("inner.zip")
        Files.copy(outerVfs.getPath("/inner.zip"), tempInnerZip)
        tempFiles.add(tempInnerZip)
        tempFiles.add(tempDir)

        val innerVfs = ZipVirtualFileSystem(tempInnerZip)
        vfsList.add(innerVfs)

        var vfsFilePath = innerVfs.getPath("/file.txt")
        val editDir = Files.createTempDirectory("turtle-vfs-edit-test-")
        val editPath = editDir.resolve("file.txt")
        Files.copy(vfsFilePath, editPath)
        tempFiles.add(editPath)
        tempFiles.add(editDir)

        // We need to track parentPath for the inner VFS entry to simulate writeBackNestedArchives
        var innerParentPath = outerVfs.getPath("/inner.zip")

        // Perform 3 edit cycles
        for (version in listOf("v2", "v3", "v4")) {
            Files.writeString(editPath, version)

            // writeBack simulation
            Files.copy(editPath, vfsFilePath, StandardCopyOption.REPLACE_EXISTING)
            val relPath = vfsRelativePath(innerVfs, vfsFilePath)
            innerVfs.flush()
            vfsFilePath = innerVfs.root.resolve(relPath)

            Files.copy(tempInnerZip, innerParentPath, StandardCopyOption.REPLACE_EXISTING)
            val parentRelPath = vfsRelativePath(outerVfs, innerParentPath)
            outerVfs.flush()
            innerParentPath = outerVfs.root.resolve(parentRelPath)
        }

        // Verify final content
        val verifyDir = Files.createTempDirectory("turtle-verify-")
        val verifyInner = verifyDir.resolve("inner.zip")
        Files.copy(outerVfs.getPath("/inner.zip"), verifyInner)
        tempFiles.add(verifyInner)
        tempFiles.add(verifyDir)

        val verifyVfs = ZipVirtualFileSystem(verifyInner)
        vfsList.add(verifyVfs)
        val file = verifyVfs.listFiles(verifyVfs.root).find { it.name == "file.txt" }
        assertNotNull(file)
        assertEquals("v4", Files.readString(file!!.path))
    }

    @Test
    fun `navigation works after edit write-back in zip`() = runBlocking {
        val zipPath = Files.createTempFile("test-edit-nav-", ".zip")
        tempFiles.add(zipPath)
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(ZipEntry("subdir/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("subdir/file.txt"))
            zos.write("original".toByteArray())
            zos.closeEntry()
        }
        val vfs = ZipVirtualFileSystem(zipPath)
        vfsList.add(vfs)

        // Edit a file in subdir
        val vfsFilePath = vfs.getPath("/subdir/file.txt")
        val tempDir = Files.createTempDirectory("turtle-vfs-edit-test-")
        val tempPath = tempDir.resolve("file.txt")
        Files.copy(vfsFilePath, tempPath)
        tempFiles.add(tempPath)
        tempFiles.add(tempDir)

        Files.writeString(tempPath, "edited")
        Files.copy(tempPath, vfsFilePath, StandardCopyOption.REPLACE_EXISTING)
        vfs.flush()

        // After flush, navigate up from subdir using root.resolve
        val subdirPath = vfs.root.resolve("subdir")
        val parent = subdirPath.parent
        assertNotNull("Should be able to navigate up after edit+flush", parent)
        assertTrue("Parent should be root", vfs.isRoot(parent!!))

        val rootEntries = vfs.listFiles(parent)
        assertTrue("Root should contain subdir", rootEntries.any { it.name == "subdir" })
    }

    @Test
    fun `edit file in tar via temp extraction and write-back`() = runBlocking {
        val tarPath = Files.createTempFile("test-edit-", ".tar")
        tempFiles.add(tarPath)
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val entry = TarArchiveEntry("file.txt")
            val bytes = "original".toByteArray()
            entry.size = bytes.size.toLong()
            tar.putArchiveEntry(entry)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
        val vfs = TarVirtualFileSystem(tarPath, inputStreamFactory = { Files.newInputStream(it) }, outputStreamFactory = { Files.newOutputStream(it) })
        vfsList.add(vfs)

        // Extract to temp
        val filePath = vfs.listFiles(vfs.root).find { it.name == "file.txt" }!!.path
        val tempDir = Files.createTempDirectory("turtle-vfs-edit-test-")
        val tempPath = tempDir.resolve("file.txt")
        Files.copy(filePath, tempPath)
        tempFiles.add(tempPath)
        tempFiles.add(tempDir)

        // Edit
        Files.writeString(tempPath, "edited")

        // Write-back
        Files.copy(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING)
        vfs.flush()

        val entries = vfs.listFiles(vfs.root)
        val file = entries.find { it.name == "file.txt" }
        assertNotNull(file)
        assertEquals("edited", Files.readString(file!!.path))

        // Verify persisted to tar archive
        vfs.close()
        val vfs2 = TarVirtualFileSystem(tarPath, inputStreamFactory = { Files.newInputStream(it) }, outputStreamFactory = { Files.newOutputStream(it) })
        vfsList.add(vfs2)
        val file2 = vfs2.listFiles(vfs2.root).find { it.name == "file.txt" }
        assertNotNull(file2)
        assertEquals("edited", Files.readString(file2!!.path))
    }
}

// ─── Shared VfsStackEntry write-back ─────────────────────────────────────────
// Verifies that VfsStackEntry.parentPath is updated in-place during write-back,
// so that the same objects shared with FileTab remain valid for navigation.

class SharedVfsStackEntryTest {

    private val tempFiles = mutableListOf<Path>()
    private val vfsList = mutableListOf<VirtualFileSystem>()

    @After
    fun tearDown() {
        vfsList.asReversed().forEach { try { it.close() } catch (_: Exception) {} }
        tempFiles.asReversed().forEach { try { Files.deleteIfExists(it) } catch (_: Exception) {} }
    }

    private fun vfsRelativePath(vfs: VirtualFileSystem, path: Path): String {
        val rootStr = vfs.root.toString().trimEnd('/').trimEnd('\\')
        val pathStr = path.toString()
        return if (pathStr.startsWith(rootStr)) {
            pathStr.removePrefix(rootStr).removePrefix("/").removePrefix("\\")
        } else {
            pathStr
        }
    }

    private fun createZipBytes(vararg entries: Pair<String, String?>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                if (content != null) zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun `VfsStackEntry parentPath is mutable`() {
        val zipPath = Files.createTempFile("test-", ".zip")
        tempFiles.add(zipPath)
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(ZipEntry("file.txt"))
            zos.write("data".toByteArray())
            zos.closeEntry()
        }
        val vfs = ZipVirtualFileSystem(zipPath)
        vfsList.add(vfs)

        val entry = VfsStackEntry(vfs, vfs.getPath("/file.txt"))

        // parentPath should be mutable — assign a different path
        val differentPath = vfs.root.resolve("other.txt")
        entry.parentPath = differentPath
        assertEquals(differentPath, entry.parentPath)
        assertEquals("other.txt", entry.parentPath.fileName.toString())
    }

    @Test
    fun `shared VfsStackEntry is updated when write-back modifies parentPath`() = runBlocking {
        // Create outer zip with inner zip
        val innerBytes = createZipBytes("file.txt" to "original")
        val outerPath = Files.createTempFile("outer-shared-", ".zip")
        tempFiles.add(outerPath)
        ZipOutputStream(Files.newOutputStream(outerPath)).use { zos ->
            zos.putNextEntry(ZipEntry("inner.zip"))
            zos.write(innerBytes)
            zos.closeEntry()
        }

        val outerVfs = ZipVirtualFileSystem(outerPath)
        vfsList.add(outerVfs)

        // Extract inner zip
        val tempDir = Files.createTempDirectory("turtle-vfs-test-")
        val tempInnerZip = tempDir.resolve("inner.zip")
        Files.copy(outerVfs.getPath("/inner.zip"), tempInnerZip)
        tempFiles.add(tempInnerZip)
        tempFiles.add(tempDir)

        val innerVfs = ZipVirtualFileSystem(tempInnerZip)
        vfsList.add(innerVfs)

        // Create shared VfsStackEntry objects (simulating FileTab.vfsStack)
        val outerEntry = VfsStackEntry(outerVfs, outerPath)
        val innerEntry = VfsStackEntry(innerVfs, outerVfs.getPath("/inner.zip"), tempInnerZip.toFile())
        val sharedStack = mutableListOf(outerEntry, innerEntry)

        // Remember the old parentPath
        val oldParentPath = innerEntry.parentPath

        // Simulate edit + writeBack: modify file, flush inner, copy back, flush outer
        val filePath = innerVfs.getPath("/file.txt")
        Files.writeString(filePath, "edited")
        innerVfs.flush()

        Files.copy(tempInnerZip, innerEntry.parentPath, StandardCopyOption.REPLACE_EXISTING)
        val relPath = vfsRelativePath(outerVfs, innerEntry.parentPath)
        outerVfs.flush()
        // Update parentPath in-place (what VfsEditService.writeBack does)
        innerEntry.parentPath = if (relPath.isEmpty()) outerVfs.root else outerVfs.root.resolve(relPath)

        // The shared entry should be updated
        assertNotEquals("parentPath should have changed after flush", oldParentPath, sharedStack[1].parentPath)
        // The entry in the shared list IS the same object
        assertSame("Stack entry should be the same object", innerEntry, sharedStack[1])
        // New parentPath should be valid
        assertTrue("New parentPath should exist", Files.exists(sharedStack[1].parentPath))
    }

    @Test
    fun `navigation via parentPath works after write-back updates shared entry`() = runBlocking {
        val innerBytes = createZipBytes("file.txt" to "original")
        val outerPath = Files.createTempFile("outer-nav-", ".zip")
        tempFiles.add(outerPath)
        ZipOutputStream(Files.newOutputStream(outerPath)).use { zos ->
            zos.putNextEntry(ZipEntry("inner.zip"))
            zos.write(innerBytes)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("other.txt"))
            zos.write("other".toByteArray())
            zos.closeEntry()
        }

        val outerVfs = ZipVirtualFileSystem(outerPath)
        vfsList.add(outerVfs)

        val tempDir = Files.createTempDirectory("turtle-vfs-test-")
        val tempInnerZip = tempDir.resolve("inner.zip")
        Files.copy(outerVfs.getPath("/inner.zip"), tempInnerZip)
        tempFiles.add(tempInnerZip)
        tempFiles.add(tempDir)

        val innerVfs = ZipVirtualFileSystem(tempInnerZip)
        vfsList.add(innerVfs)

        // Shared stack entries
        val outerEntry = VfsStackEntry(outerVfs, outerPath)
        val innerEntry = VfsStackEntry(innerVfs, outerVfs.getPath("/inner.zip"), tempInnerZip.toFile())

        // Edit and write back
        Files.writeString(innerVfs.getPath("/file.txt"), "edited")
        innerVfs.flush()
        Files.copy(tempInnerZip, innerEntry.parentPath, StandardCopyOption.REPLACE_EXISTING)
        val relPath = vfsRelativePath(outerVfs, innerEntry.parentPath)
        outerVfs.flush()
        innerEntry.parentPath = outerVfs.root.resolve(relPath)

        // Simulate "go up" from inner VFS: use parentPath to navigate to the outer VFS
        val parentOfInner = innerEntry.parentPath.parent
        assertNotNull("parentPath.parent should not be null after write-back", parentOfInner)

        // Should be able to list files at the parent path in the outer VFS
        val outerEntries = outerVfs.listFiles(parentOfInner!!)
        assertTrue("Should find inner.zip at outer root", outerEntries.any { it.name == "inner.zip" })
        assertTrue("Should find other.txt at outer root", outerEntries.any { it.name == "other.txt" })
    }

    @Test
    fun `multiple edit cycles keep shared parentPath valid`() = runBlocking {
        val innerBytes = createZipBytes("file.txt" to "v1")
        val outerPath = Files.createTempFile("outer-multi-", ".zip")
        tempFiles.add(outerPath)
        ZipOutputStream(Files.newOutputStream(outerPath)).use { zos ->
            zos.putNextEntry(ZipEntry("inner.zip"))
            zos.write(innerBytes)
            zos.closeEntry()
        }

        val outerVfs = ZipVirtualFileSystem(outerPath)
        vfsList.add(outerVfs)

        val tempDir = Files.createTempDirectory("turtle-vfs-test-")
        val tempInnerZip = tempDir.resolve("inner.zip")
        Files.copy(outerVfs.getPath("/inner.zip"), tempInnerZip)
        tempFiles.add(tempInnerZip)
        tempFiles.add(tempDir)

        val innerVfs = ZipVirtualFileSystem(tempInnerZip)
        vfsList.add(innerVfs)

        val innerEntry = VfsStackEntry(innerVfs, outerVfs.getPath("/inner.zip"), tempInnerZip.toFile())
        var vfsFilePath = innerVfs.getPath("/file.txt")

        val editDir = Files.createTempDirectory("turtle-vfs-edit-test-")
        val editPath = editDir.resolve("file.txt")
        Files.copy(vfsFilePath, editPath)
        tempFiles.add(editPath)
        tempFiles.add(editDir)

        // Perform 3 edit+writeBack cycles
        for (version in listOf("v2", "v3", "v4")) {
            Files.writeString(editPath, version)

            // Copy back to inner VFS
            Files.copy(editPath, vfsFilePath, StandardCopyOption.REPLACE_EXISTING)
            val innerRelPath = vfsRelativePath(innerVfs, vfsFilePath)
            innerVfs.flush()
            vfsFilePath = innerVfs.root.resolve(innerRelPath)

            // Copy inner temp back to outer VFS
            Files.copy(tempInnerZip, innerEntry.parentPath, StandardCopyOption.REPLACE_EXISTING)
            val outerRelPath = vfsRelativePath(outerVfs, innerEntry.parentPath)
            outerVfs.flush()
            innerEntry.parentPath = outerVfs.root.resolve(outerRelPath)
        }

        // parentPath should still be valid for navigation
        assertTrue("parentPath should exist after multiple cycles", Files.exists(innerEntry.parentPath))
        val parent = innerEntry.parentPath.parent
        assertNotNull("parentPath.parent should not be null", parent)
        val outerEntries = outerVfs.listFiles(parent!!)
        assertTrue("Should find inner.zip", outerEntries.any { it.name == "inner.zip" })

        // Verify final content
        val verifyDir = Files.createTempDirectory("turtle-verify-")
        val verifyInner = verifyDir.resolve("inner.zip")
        Files.copy(innerEntry.parentPath, verifyInner)
        tempFiles.add(verifyInner)
        tempFiles.add(verifyDir)
        val verifyVfs = ZipVirtualFileSystem(verifyInner)
        vfsList.add(verifyVfs)
        val file = verifyVfs.listFiles(verifyVfs.root).find { it.name == "file.txt" }
        assertNotNull(file)
        assertEquals("v4", Files.readString(file!!.path))
    }

    @Test
    fun `tar root changes after flush but relative path reconstruction works`() = runBlocking {
        val tarPath = Files.createTempFile("test-tar-root-", ".tar")
        tempFiles.add(tarPath)
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val entry = TarArchiveEntry("subdir/")
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
            val fileEntry = TarArchiveEntry("subdir/file.txt")
            val bytes = "original".toByteArray()
            fileEntry.size = bytes.size.toLong()
            tar.putArchiveEntry(fileEntry)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
        val vfs = TarVirtualFileSystem(tarPath,
            inputStreamFactory = { Files.newInputStream(it) },
            outputStreamFactory = { Files.newOutputStream(it) })
        vfsList.add(vfs)

        val oldRoot = vfs.root
        val subdirPath = vfs.root.resolve("subdir")
        val relPath = vfsRelativePath(vfs, subdirPath)

        // Modify a file and flush
        Files.writeString(vfs.root.resolve("subdir/file.txt"), "edited")
        vfs.flush()

        // Root should have changed (new temp dir)
        assertNotEquals("Tar root should change after flush", oldRoot, vfs.root)

        // But relative path can be used to reconstruct valid path
        val newSubdirPath = vfs.root.resolve(relPath)
        assertTrue("Reconstructed subdir should exist", Files.exists(newSubdirPath))

        // Navigation up should work
        val parent = newSubdirPath.parent
        assertNotNull("Parent should not be null", parent)
        assertTrue("Parent should be root", vfs.isRoot(parent!!))

        val entries = vfs.listFiles(newSubdirPath)
        val file = entries.find { it.name == "file.txt" }
        assertNotNull(file)
        assertEquals("edited", Files.readString(file!!.path))
    }

    @Test
    fun `edit write-back in nested tar updates shared parentPath`() = runBlocking {
        // Create outer zip containing inner tar
        val tarPath = Files.createTempFile("inner-", ".tar")
        tempFiles.add(tarPath)
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val entry = TarArchiveEntry("file.txt")
            val bytes = "original".toByteArray()
            entry.size = bytes.size.toLong()
            tar.putArchiveEntry(entry)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }

        val outerPath = Files.createTempFile("outer-", ".zip")
        tempFiles.add(outerPath)
        ZipOutputStream(Files.newOutputStream(outerPath)).use { zos ->
            zos.putNextEntry(ZipEntry("inner.tar"))
            zos.write(Files.readAllBytes(tarPath))
            zos.closeEntry()
        }

        val outerVfs = ZipVirtualFileSystem(outerPath)
        vfsList.add(outerVfs)

        // Extract inner tar
        val tempDir = Files.createTempDirectory("turtle-vfs-test-")
        val tempInnerTar = tempDir.resolve("inner.tar")
        Files.copy(outerVfs.getPath("/inner.tar"), tempInnerTar)
        tempFiles.add(tempInnerTar)
        tempFiles.add(tempDir)

        val innerVfs = TarVirtualFileSystem(tempInnerTar,
            inputStreamFactory = { Files.newInputStream(it) },
            outputStreamFactory = { Files.newOutputStream(it) })
        vfsList.add(innerVfs)

        val innerEntry = VfsStackEntry(innerVfs, outerVfs.getPath("/inner.tar"), tempInnerTar.toFile())

        // Edit file in inner tar
        val filePath = innerVfs.listFiles(innerVfs.root).find { it.name == "file.txt" }!!.path
        Files.writeString(filePath, "edited")
        innerVfs.flush()

        // Write back to outer zip
        Files.copy(tempInnerTar, innerEntry.parentPath, StandardCopyOption.REPLACE_EXISTING)
        val relPath = vfsRelativePath(outerVfs, innerEntry.parentPath)
        outerVfs.flush()
        innerEntry.parentPath = outerVfs.root.resolve(relPath)

        // Navigation via parentPath should work
        assertTrue("Updated parentPath should exist", Files.exists(innerEntry.parentPath))
        val parent = innerEntry.parentPath.parent
        assertNotNull("parentPath.parent should not be null", parent)
        val outerEntries = outerVfs.listFiles(parent!!)
        assertTrue("Should find inner.tar", outerEntries.any { it.name == "inner.tar" })

        // Verify content propagated
        val verifyDir = Files.createTempDirectory("turtle-verify-")
        val verifyTar = verifyDir.resolve("inner.tar")
        Files.copy(innerEntry.parentPath, verifyTar)
        tempFiles.add(verifyTar)
        tempFiles.add(verifyDir)

        val verifyVfs = TarVirtualFileSystem(verifyTar,
            inputStreamFactory = { Files.newInputStream(it) },
            outputStreamFactory = { Files.newOutputStream(it) })
        vfsList.add(verifyVfs)
        val file = verifyVfs.listFiles(verifyVfs.root).find { it.name == "file.txt" }
        assertNotNull(file)
        assertEquals("edited", Files.readString(file!!.path))
    }

    @Test
    fun `write-back for zip inside tar reconstructs parentPath correctly`() = runBlocking {
        // Simulates the bug: zip inside tar.gz where vfsRelativePath must handle
        // Windows backslash separators in tar temp dir paths
        val tarPath = Files.createTempFile("outer-", ".tar")
        tempFiles.add(tarPath)

        // Create a tar containing src.zip
        val zipBytes = createZipBytes("file.txt" to "original")
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val entry = TarArchiveEntry("src.zip")
            entry.size = zipBytes.size.toLong()
            tar.putArchiveEntry(entry)
            tar.write(zipBytes)
            tar.closeArchiveEntry()
        }

        val tarVfs = TarVirtualFileSystem(tarPath,
            inputStreamFactory = { Files.newInputStream(it) },
            outputStreamFactory = { Files.newOutputStream(it) })
        vfsList.add(tarVfs)

        // Extract src.zip from tar to temp (simulating enterVfs for nested archive)
        val srcZipInTar = tarVfs.root.resolve("src.zip")
        assertTrue("src.zip should exist in tar", Files.exists(srcZipInTar))

        val tempDir = Files.createTempDirectory("turtle-vfs-")
        val tempZip = tempDir.resolve("src.zip")
        Files.copy(srcZipInTar, tempZip)
        tempFiles.add(tempZip)
        tempFiles.add(tempDir)

        val zipVfs = ZipVirtualFileSystem(tempZip)
        vfsList.add(zipVfs)

        // Shared VfsStackEntry objects
        val tarEntry = VfsStackEntry(tarVfs, tarPath)
        val zipEntry = VfsStackEntry(zipVfs, srcZipInTar, tempZip.toFile())

        // Edit file inside zip
        Files.writeString(zipVfs.getPath("/file.txt"), "edited")
        zipVfs.flush()

        // Write back: copy modified zip to tar temp dir, flush tar
        Files.copy(tempZip, zipEntry.parentPath, StandardCopyOption.REPLACE_EXISTING)
        val relPath = vfsRelativePath(tarVfs, zipEntry.parentPath)
        assertEquals("src.zip", relPath)  // must NOT be "\\src.zip" or contain backslash prefix
        tarVfs.flush()

        // Reconstruct parentPath — this was the bug: \src.zip resolved to C:\src.zip
        val newParentPath = tarVfs.root.resolve(relPath)
        zipEntry.parentPath = newParentPath

        // Verify parentPath is valid and inside the tar temp dir
        assertTrue("New parentPath should exist", Files.exists(zipEntry.parentPath))
        assertTrue("New parentPath should be under tar root",
            zipEntry.parentPath.toString().startsWith(tarVfs.root.toString()))

        // Navigation up should work
        val parent = zipEntry.parentPath.parent
        assertNotNull("parentPath.parent should not be null", parent)
        assertTrue("Parent should be tar root", tarVfs.isRoot(parent!!))

        // Verify content propagated to tar
        val entries = tarVfs.listFiles(tarVfs.root).filter { !it.isParentLink }
        assertTrue("tar should contain src.zip", entries.any { it.name == "src.zip" })

        // Verify the zip content inside tar was updated
        val verifyDir = Files.createTempDirectory("turtle-verify-")
        val verifyZip = verifyDir.resolve("src.zip")
        Files.copy(newParentPath, verifyZip)
        tempFiles.add(verifyZip)
        tempFiles.add(verifyDir)
        val verifyVfs = ZipVirtualFileSystem(verifyZip)
        vfsList.add(verifyVfs)
        val file = verifyVfs.listFiles(verifyVfs.root).find { it.name == "file.txt" }
        assertNotNull(file)
        assertEquals("edited", Files.readString(file!!.path))
    }

    @Test
    fun `write-back for zip inside tar with subdir reconstructs correctly`() = runBlocking {
        // zip inside tar, with files in a subdirectory within the tar
        val tarPath = Files.createTempFile("outer-sub-", ".tar")
        tempFiles.add(tarPath)

        val zipBytes = createZipBytes("data.txt" to "content")
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val dirEntry = TarArchiveEntry("subdir/")
            tar.putArchiveEntry(dirEntry)
            tar.closeArchiveEntry()
            val entry = TarArchiveEntry("subdir/archive.zip")
            entry.size = zipBytes.size.toLong()
            tar.putArchiveEntry(entry)
            tar.write(zipBytes)
            tar.closeArchiveEntry()
        }

        val tarVfs = TarVirtualFileSystem(tarPath,
            inputStreamFactory = { Files.newInputStream(it) },
            outputStreamFactory = { Files.newOutputStream(it) })
        vfsList.add(tarVfs)

        val zipInTar = tarVfs.root.resolve("subdir/archive.zip")
        val tempDir = Files.createTempDirectory("turtle-vfs-")
        val tempZip = tempDir.resolve("archive.zip")
        Files.copy(zipInTar, tempZip)
        tempFiles.add(tempZip)
        tempFiles.add(tempDir)

        val zipVfs = ZipVirtualFileSystem(tempZip)
        vfsList.add(zipVfs)

        val zipEntry = VfsStackEntry(zipVfs, zipInTar, tempZip.toFile())

        // Edit and write back
        Files.writeString(zipVfs.getPath("/data.txt"), "modified")
        zipVfs.flush()

        Files.copy(tempZip, zipEntry.parentPath, StandardCopyOption.REPLACE_EXISTING)
        val relPath = vfsRelativePath(tarVfs, zipEntry.parentPath)
        // On Windows: must strip backslash, producing "subdir\archive.zip" or "subdir/archive.zip"
        assertFalse("relPath should not start with separator", relPath.startsWith("/") || relPath.startsWith("\\"))
        tarVfs.flush()

        zipEntry.parentPath = tarVfs.root.resolve(relPath)
        assertTrue("Updated parentPath should exist", Files.exists(zipEntry.parentPath))
        assertTrue("parentPath should be under tar root",
            zipEntry.parentPath.toString().startsWith(tarVfs.root.toString()))
    }

    @Test
    fun `before-flush callback captures valid relative path for tar VFS`() = runBlocking {
        // The key bug: capturing relative path AFTER flush fails for tar/7z
        // because root changes to a new temp dir. Must capture BEFORE flush.
        val tarPath = Files.createTempFile("test-callback-", ".tar")
        tempFiles.add(tarPath)
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val dirEntry = TarArchiveEntry("subdir/")
            tar.putArchiveEntry(dirEntry)
            tar.closeArchiveEntry()
            val entry = TarArchiveEntry("subdir/file.txt")
            val bytes = "original".toByteArray()
            entry.size = bytes.size.toLong()
            tar.putArchiveEntry(entry)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }

        val vfs = TarVirtualFileSystem(tarPath,
            inputStreamFactory = { Files.newInputStream(it) },
            outputStreamFactory = { Files.newOutputStream(it) })
        vfsList.add(vfs)

        // Simulate user browsing in subdir
        val currentPath = vfs.root.resolve("subdir")

        // Capture relative path BEFORE flush (simulates onBeforeFlush callback)
        val relPathBefore = vfsRelativePath(vfs, currentPath)
        assertEquals("subdir", relPathBefore)

        // Edit and flush — root changes
        Files.writeString(vfs.root.resolve("subdir/file.txt"), "edited")
        val oldRoot = vfs.root
        vfs.flush()
        assertNotEquals("Root should change after flush", oldRoot, vfs.root)

        // After flush, trying to get relative path from currentPath FAILS
        val relPathAfter = vfsRelativePath(vfs, currentPath)
        // This returns the full old path string because old root doesn't match new root
        assertNotEquals("subdir", relPathAfter)

        // But the pre-captured relative path can reconstruct a valid path
        val newPath = vfs.root.resolve(relPathBefore)
        assertTrue("Reconstructed path should exist", Files.exists(newPath))
        assertTrue("Should be able to list files", vfs.listFiles(newPath).any { it.name == "file.txt" })

        // Navigate up should work
        val parent = newPath.parent
        assertNotNull(parent)
        assertTrue("Parent should be root", vfs.isRoot(parent!!))
    }

    @Test
    fun `edit write-back in tar gz uses before-after flush pattern`() = runBlocking {
        // Simulates the full VfsEditService flow for editing a file in a .tar.gz
        // Single VFS level (no nesting), tar root changes on flush
        val tarPath = Files.createTempFile("test-targz-edit-", ".tar")
        tempFiles.add(tarPath)
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val dirEntry = TarArchiveEntry("src/")
            tar.putArchiveEntry(dirEntry)
            tar.closeArchiveEntry()
            val entry = TarArchiveEntry("src/main.txt")
            val bytes = "original".toByteArray()
            entry.size = bytes.size.toLong()
            tar.putArchiveEntry(entry)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }

        val vfs = TarVirtualFileSystem(tarPath,
            inputStreamFactory = { Files.newInputStream(it) },
            outputStreamFactory = { Files.newOutputStream(it) })
        vfsList.add(vfs)

        // Simulate: user is browsing in src/ directory
        var currentPath = vfs.root.resolve("src")

        // Extract file to temp for editing
        val filePath = vfs.root.resolve("src/main.txt")
        val editDir = Files.createTempDirectory("turtle-vfs-edit-test-")
        val editPath = editDir.resolve("main.txt")
        Files.copy(filePath, editPath)
        tempFiles.add(editPath)
        tempFiles.add(editDir)

        // Simulate VfsEditService.writeBack with before/after flush:

        // 1. Editor save — modify temp file
        Files.writeString(editPath, "edited")

        // 2. Copy back to VFS path
        var vfsFilePath = vfs.root.resolve("src/main.txt")
        Files.copy(editPath, vfsFilePath, StandardCopyOption.REPLACE_EXISTING)

        // 3. onBeforeFlush: capture relative path while root is valid
        val currentPathRel = vfsRelativePath(vfs, currentPath)
        assertEquals("src", currentPathRel)

        // 4. Flush VFS (root changes)
        val relFilePath = vfsRelativePath(vfs, vfsFilePath)
        vfs.flush()
        vfsFilePath = vfs.root.resolve(relFilePath)

        // 5. onAfterFlush: reconstruct currentPath from new root + captured relative path
        currentPath = if (currentPathRel.isEmpty()) vfs.root else vfs.root.resolve(currentPathRel)

        // Verify everything works
        assertTrue("Reconstructed currentPath should exist", Files.exists(currentPath))
        val entries = vfs.listFiles(currentPath)
        assertTrue("Should find main.txt in src/", entries.any { it.name == "main.txt" })
        val file = entries.find { it.name == "main.txt" }
        assertEquals("edited", Files.readString(file!!.path))

        // Navigation up should work
        val parent = currentPath.parent
        assertNotNull(parent)
        assertTrue("Parent should be root", vfs.isRoot(parent!!))
        val rootEntries = vfs.listFiles(parent)
        assertTrue("Root should contain src/", rootEntries.any { it.name == "src" })
    }

    @Test
    fun `concurrent-safe flush and write-back in same context prevents stale paths`() = runBlocking {
        // Simulates the race condition fix: flush + writeBack must happen atomically
        val innerBytes = createZipBytes("file.txt" to "original")
        val outerPath = Files.createTempFile("outer-race-", ".zip")
        tempFiles.add(outerPath)
        ZipOutputStream(Files.newOutputStream(outerPath)).use { zos ->
            zos.putNextEntry(ZipEntry("inner.zip"))
            zos.write(innerBytes)
            zos.closeEntry()
        }

        val outerVfs = ZipVirtualFileSystem(outerPath)
        vfsList.add(outerVfs)

        val tempDir = Files.createTempDirectory("turtle-vfs-test-")
        val tempInnerZip = tempDir.resolve("inner.zip")
        Files.copy(outerVfs.getPath("/inner.zip"), tempInnerZip)
        tempFiles.add(tempInnerZip)
        tempFiles.add(tempDir)

        val innerVfs = ZipVirtualFileSystem(tempInnerZip)
        vfsList.add(innerVfs)

        val innerEntry = VfsStackEntry(innerVfs, outerVfs.getPath("/inner.zip"), tempInnerZip.toFile())

        // Edit the file
        Files.writeString(innerVfs.getPath("/file.txt"), "edited")

        // Simulate the fixed refresh() flow: flush + writeBack in same context (no gap)
        // Step 1: flush innermost VFS
        val currentPathRel = "file.txt"  // relative path where user is browsing
        innerVfs.flush()

        // Step 2: immediately writeBack (no gap for another flush to intervene)
        Files.copy(tempInnerZip, innerEntry.parentPath, StandardCopyOption.REPLACE_EXISTING)
        val relPath = vfsRelativePath(outerVfs, innerEntry.parentPath)
        outerVfs.flush()
        innerEntry.parentPath = outerVfs.root.resolve(relPath)

        // Step 3: reconstruct navigation path
        val newRoot = innerVfs.root
        val reconstructedPath = newRoot.resolve(currentPathRel)

        // Everything should be consistent
        assertTrue("innerEntry.parentPath should exist", Files.exists(innerEntry.parentPath))
        assertNotNull("parentPath.parent should not be null", innerEntry.parentPath.parent)
        assertTrue("Reconstructed path should exist", Files.exists(reconstructedPath))
    }
}
