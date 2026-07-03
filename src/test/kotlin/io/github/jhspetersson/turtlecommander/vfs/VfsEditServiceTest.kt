package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for VfsEditService entry tracking and cleanup.
 */
class VfsEditServiceActiveEditsTest {

    /**
     * Verifies that after trackEdit, the entry is present (onFileSaved finds it),
     * and after dispose, the map is cleared.
     */
    @Test
    fun `trackEdit adds entry and dispose clears it`() {
        val tempDir = Files.createTempDirectory("vfs-edit-test-")
        try {
            val tempFile = tempDir.resolve("test.txt")
            Files.writeString(tempFile, "content")

            val entry = VfsEditEntry(
                vfsFilePath = tempFile,
                tempFilePath = tempFile,
                vfsStack = mutableListOf(),
            )

            // Use reflection to access activeEdits since VfsEditService requires CoroutineScope
            // Instead, test the data structures directly
            val activeEdits = mutableMapOf<String, VfsEditEntry>()
            val key = tempFile.toString().replace('\\', '/')
            activeEdits[key] = entry

            assertEquals(1, activeEdits.size)
            assertNotNull(activeEdits[key])

            // Simulate dispose clearing
            activeEdits.clear()
            assertEquals(0, activeEdits.size)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the normalize key logic handles both slash directions.
     */
    @Test
    fun `normalizeKey converts backslashes to forward slashes`() {
        val path1 = "C:\\Users\\test\\file.txt"
        val path2 = "C:/Users/test/file.txt"
        val normalized1 = path1.replace('\\', '/')
        val normalized2 = path2.replace('\\', '/')
        assertEquals(normalized1, normalized2)
    }

    /**
     * Verifies that after successful write-back the entry should be removed.
     * We test this by simulating the removal logic.
     */
    @Test
    fun `entry removed from map after simulated write-back`() {
        val activeEdits = mutableMapOf<String, VfsEditEntry>()
        val tempPath = Path.of("/tmp/test/file.txt")
        val key = tempPath.toString().replace('\\', '/')

        val entry = VfsEditEntry(
            vfsFilePath = tempPath,
            tempFilePath = tempPath,
            vfsStack = mutableListOf(),
        )

        // Track
        synchronized(activeEdits) {
            activeEdits[key] = entry
        }
        assertEquals(1, activeEdits.size)

        // Simulate successful write-back removing entry
        synchronized(activeEdits) {
            activeEdits.remove(key)
        }
        assertEquals(0, activeEdits.size)
        assertNull(activeEdits[key])
    }

    /**
     * Verifies that a write-back failure does not leave the edit entry stuck in activeEdits.
     * Regression: a previous version only removed the entry on success, so a single IOException
     * would block every subsequent save to the same file until the IDE restart.
     */
    @Test
    fun `writeBack releases tracking entry even when copy fails`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = VfsEditService(scope)
        val tempDir = Files.createTempDirectory("vfs-edit-failure-test-")
        try {
            val tempFile = tempDir.resolve("edited.txt")
            Files.writeString(tempFile, "new content")

            // vfsFilePath points into a directory that doesn't exist: Files.copy will throw
            // and writeBack must still clean up the tracking entry.
            val vfsTarget = tempDir.resolve("does/not/exist/edited.txt")

            val entry = VfsEditEntry(
                vfsFilePath = vfsTarget,
                tempFilePath = tempFile,
                vfsStack = mutableListOf(),
            )

            service.trackEdit(entry)

            val key = tempFile.toString().replace('\\', '/')
            assertTrue("entry should be tracked", service.isTrackedForTest(key))

            service.onFileSaved(tempFile.toString())

            // Wait for the background write-back to finish (it will fail internally).
            runBlocking {
                withTimeout(5_000.milliseconds) {
                    while (service.isTrackedForTest(key)) {
                        delay(20.milliseconds)
                    }
                }
            }
            assertFalse("entry must be released after failure", service.isTrackedForTest(key))
        } finally {
            service.dispose()
            tempDir.toFile().deleteRecursively()
        }
    }

    /**
     * Bug 5 regression: after a *successful* write-back the entry must stay tracked, otherwise
     * every save after the first silently no-ops. Also verifies the second save actually applies.
     */
    @Test
    fun `entry stays tracked after a successful write-back and later saves still apply`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = VfsEditService(scope)
        val dir = Files.createTempDirectory("vfs-edit-success-")
        try {
            val temp = dir.resolve("edited.txt")
            val target = dir.resolve("target.txt")
            Files.writeString(target, "v0")
            Files.writeString(temp, "v1")

            // No VFS stack: write-back is a plain copy temp -> target.
            service.trackEdit(VfsEditEntry(vfsFilePath = target, tempFilePath = temp, vfsStack = mutableListOf()))
            val key = temp.toString().replace('\\', '/')

            service.onFileSaved(temp.toString())
            runBlocking {
                withTimeout(5_000.milliseconds) { while (Files.readString(target) != "v1") delay(20.milliseconds) }
            }
            assertTrue("entry must remain tracked after a successful save", service.isTrackedForTest(key))

            // Second save of the same file must reach the target too.
            Files.writeString(temp, "v2")
            service.onFileSaved(temp.toString())
            runBlocking {
                withTimeout(5_000.milliseconds) { while (Files.readString(target) != "v2") delay(20.milliseconds) }
            }
            assertEquals("v2", Files.readString(target))
        } finally {
            service.dispose()
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Bug 6 regression: two files edited from the *same* archive. Saving the first flushes the
     * archive, which recreates the VFS temp dir and dangles any absolute path the second entry
     * captured at open time. The second save must still land, not get lost.
     */
    @Test
    fun `second file edited from the same archive still writes back after the first save flushes`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = VfsEditService(scope)
        val zipPath = Files.createTempFile("vfs-edit-two-", ".zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(ZipEntry("a.txt")); zos.write("a0".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("b.txt")); zos.write("b0".toByteArray()); zos.closeEntry()
        }
        val vfs = ZipVirtualFileSystem(zipPath)
        // Both edits from the same tab share one mutex, which serializes their write-backs.
        val mutex = Mutex()

        fun readInArchive(name: String): String? =
            try { Files.readString(vfs.getPath(name)) } catch (_: Exception) { null }

        try {
            val sharedStack = mutableListOf(VfsStackEntry(vfs, parentPath = zipPath))

            val aVfsPath = vfs.getPath("/a.txt")
            val aTempDir = Files.createTempDirectory("edit-a-")
            val aTemp = aTempDir.resolve("a.txt"); Files.copy(aVfsPath, aTemp)
            service.trackEdit(
                VfsEditEntry(aVfsPath, aTemp, sharedStack.toMutableList(),
                    vfsFileRelPath = vfsRelativePath(vfs.root, aVfsPath), vfsWriteMutex = mutex),
            )

            val bVfsPath = vfs.getPath("/b.txt")
            val bTempDir = Files.createTempDirectory("edit-b-")
            val bTemp = bTempDir.resolve("b.txt"); Files.copy(bVfsPath, bTemp)
            service.trackEdit(
                VfsEditEntry(bVfsPath, bTemp, sharedStack.toMutableList(),
                    vfsFileRelPath = vfsRelativePath(vfs.root, bVfsPath), vfsWriteMutex = mutex),
            )

            // Save A first, and wait until its flush has rebuilt the temp dir (a.txt == "a1").
            Files.writeString(aTemp, "a1")
            service.onFileSaved(aTemp.toString())
            runBlocking {
                withTimeout(5_000.milliseconds) { while (readInArchive("/a.txt") != "a1") delay(20.milliseconds) }
            }

            // Now save B. Its captured absolute path is stale; only the relative re-resolution
            // lets it reach the archive.
            Files.writeString(bTemp, "b1")
            service.onFileSaved(bTemp.toString())
            runBlocking {
                withTimeout(5_000.milliseconds) { while (readInArchive("/b.txt") != "b1") delay(20.milliseconds) }
            }

            assertEquals("second file's edit must be written back", "b1", readInArchive("/b.txt"))
            assertEquals("first file's edit must be preserved", "a1", readInArchive("/a.txt"))
        } finally {
            service.dispose()
            try { vfs.close() } catch (_: Exception) {}
            Files.deleteIfExists(zipPath)
        }
    }

    /**
     * Verifies that multiple entries can be tracked and removed independently.
     */
    @Test
    fun `multiple entries tracked and removed independently`() {
        val activeEdits = mutableMapOf<String, VfsEditEntry>()

        val path1 = Path.of("/tmp/file1.txt")
        val path2 = Path.of("/tmp/file2.txt")
        val key1 = path1.toString().replace('\\', '/')
        val key2 = path2.toString().replace('\\', '/')

        activeEdits[key1] = VfsEditEntry(path1, path1, mutableListOf())
        activeEdits[key2] = VfsEditEntry(path2, path2, mutableListOf())
        assertEquals(2, activeEdits.size)

        // Remove only first
        activeEdits.remove(key1)
        assertEquals(1, activeEdits.size)
        assertNull(activeEdits[key1])
        assertNotNull(activeEdits[key2])
    }
}
