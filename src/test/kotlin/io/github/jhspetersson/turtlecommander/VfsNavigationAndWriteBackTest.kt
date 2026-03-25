package io.github.jhspetersson.turtlecommander

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
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
        val baos = java.io.ByteArrayOutputStream()
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

        try {
            // Extract inner.zip to temp (simulating enterVfs for nested archive)
            val innerZipPathInOuter = outerVfs.getPath("/inner.zip")
            val tempDir = Files.createTempDirectory("turtle-vfs-test-")
            val tempInnerZip = tempDir.resolve("inner.zip")
            Files.copy(innerZipPathInOuter, tempInnerZip)
            tempFiles.add(tempInnerZip)
            tempFiles.add(tempDir)

            val innerVfs = ZipVirtualFileSystem(tempInnerZip)
            try {
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
            } finally {
                innerVfs.close()
            }
        } finally {
            outerVfs.close()
        }
    }

    @Test
    fun `write-back updates parentPath to valid path in reopened filesystem`() = runBlocking {
        val outerPath = createOuterZipWithInnerZip(arrayOf("file.txt" to "data"))
        val outerVfs = ZipVirtualFileSystem(outerPath)

        try {
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
        } finally {
            outerVfs.close()
        }
    }

    @Test
    fun `modifying file content in inner zip propagates to outer zip`() = runBlocking {
        val outerPath = createOuterZipWithInnerZip(arrayOf("data.txt" to "original"))
        val outerVfs = ZipVirtualFileSystem(outerPath)

        try {
            val innerPathInOuter = outerVfs.getPath("/inner.zip")
            val tempDir = Files.createTempDirectory("turtle-vfs-test-")
            val tempInnerZip = tempDir.resolve("inner.zip")
            Files.copy(innerPathInOuter, tempInnerZip)
            tempFiles.add(tempInnerZip)
            tempFiles.add(tempDir)

            val innerVfs = ZipVirtualFileSystem(tempInnerZip)
            try {
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
                try {
                    val entries = verifyVfs.listFiles(verifyVfs.root)
                    val dataFile = entries.find { it.name == "data.txt" }
                    assertNotNull("data.txt should exist", dataFile)
                    assertEquals("modified", Files.readString(dataFile!!.path))
                } finally {
                    verifyVfs.close()
                }
            } finally {
                innerVfs.close()
            }
        } finally {
            outerVfs.close()
        }
    }

    @Test
    fun `deleting file from inner zip propagates to outer zip`() = runBlocking {
        val outerPath = createOuterZipWithInnerZip(arrayOf("keep.txt" to "keep", "remove.txt" to "remove"))
        val outerVfs = ZipVirtualFileSystem(outerPath)

        try {
            val innerPathInOuter = outerVfs.getPath("/inner.zip")
            val tempDir = Files.createTempDirectory("turtle-vfs-test-")
            val tempInnerZip = tempDir.resolve("inner.zip")
            Files.copy(innerPathInOuter, tempInnerZip)
            tempFiles.add(tempInnerZip)
            tempFiles.add(tempDir)

            val innerVfs = ZipVirtualFileSystem(tempInnerZip)
            try {
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
                try {
                    val entries = verifyVfs.listFiles(verifyVfs.root).filter { !it.isParentLink }
                    assertTrue("keep.txt should remain", entries.any { it.name == "keep.txt" })
                    assertFalse("remove.txt should be gone", entries.any { it.name == "remove.txt" })
                } finally {
                    verifyVfs.close()
                }
            } finally {
                innerVfs.close()
            }
        } finally {
            outerVfs.close()
        }
    }

    @Test
    fun `renaming file in inner zip propagates to outer zip`() = runBlocking {
        val outerPath = createOuterZipWithInnerZip(arrayOf("old.txt" to "content"))
        val outerVfs = ZipVirtualFileSystem(outerPath)

        try {
            val innerPathInOuter = outerVfs.getPath("/inner.zip")
            val tempDir = Files.createTempDirectory("turtle-vfs-test-")
            val tempInnerZip = tempDir.resolve("inner.zip")
            Files.copy(innerPathInOuter, tempInnerZip)
            tempFiles.add(tempInnerZip)
            tempFiles.add(tempDir)

            val innerVfs = ZipVirtualFileSystem(tempInnerZip)
            try {
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
                try {
                    val entries = verifyVfs.listFiles(verifyVfs.root).filter { !it.isParentLink }
                    assertTrue("new.txt should exist", entries.any { it.name == "new.txt" })
                    assertFalse("old.txt should be gone", entries.any { it.name == "old.txt" })
                } finally {
                    verifyVfs.close()
                }
            } finally {
                innerVfs.close()
            }
        } finally {
            outerVfs.close()
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
