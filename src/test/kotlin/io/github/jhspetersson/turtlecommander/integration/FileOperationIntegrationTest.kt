package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileOperationService
import io.github.jhspetersson.turtlecommander.service.OverwriteResponse
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests for file operations: copy, move, delete, rename, create directory/file.
 */
class FileOperationIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var fileOps: FileOperationService

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("turtle-test-ops-")
        fileOps = project.service()
    }

    override fun tearDown() {
        try {
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    // --- Copy ---

    fun testCopySingleFile() = runBlocking {
        val source = Files.writeString(tempDir.resolve("a.txt"), "hello")
        val dest = Files.createDirectory(tempDir.resolve("dest"))

        var progressCount = 0
        fileOps.copyFilesWithProgress(
            sources = listOf(source),
            destination = dest,
            overwriteAll = false,
            onProgress = { count, _ -> progressCount = count },
            onOverwriteConfirm = { OverwriteResponse.NO },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertTrue("Copied file should exist", Files.exists(dest.resolve("a.txt")))
        assertEquals("hello", Files.readString(dest.resolve("a.txt")))
        assertTrue("Progress should have been reported", progressCount > 0)
    }

    fun testCopyDirectory() = runBlocking {
        val srcDir = Files.createDirectory(tempDir.resolve("srcDir"))
        Files.writeString(srcDir.resolve("child.txt"), "content")
        Files.createDirectory(srcDir.resolve("sub"))
        Files.writeString(srcDir.resolve("sub").resolve("deep.txt"), "deep")
        val dest = Files.createDirectory(tempDir.resolve("dest"))

        fileOps.copyFilesWithProgress(
            sources = listOf(srcDir),
            destination = dest,
            overwriteAll = false,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.NO },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertTrue(Files.exists(dest.resolve("srcDir/child.txt")))
        assertTrue(Files.exists(dest.resolve("srcDir/sub/deep.txt")))
        assertEquals("deep", Files.readString(dest.resolve("srcDir/sub/deep.txt")))
    }

    fun testCopyOverwriteConfirmYes() = runBlocking {
        val source = Files.writeString(tempDir.resolve("a.txt"), "new")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        Files.writeString(dest.resolve("a.txt"), "old")

        fileOps.copyFilesWithProgress(
            sources = listOf(source),
            destination = dest,
            overwriteAll = false,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.YES },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals("new", Files.readString(dest.resolve("a.txt")))
    }

    fun testCopyOverwriteConfirmNo() = runBlocking {
        val source = Files.writeString(tempDir.resolve("a.txt"), "new")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        Files.writeString(dest.resolve("a.txt"), "old")

        fileOps.copyFilesWithProgress(
            sources = listOf(source),
            destination = dest,
            overwriteAll = false,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.NO },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals("old", Files.readString(dest.resolve("a.txt")))
    }

    fun testCopyOverwriteAll() = runBlocking {
        Files.writeString(tempDir.resolve("a.txt"), "newA")
        Files.writeString(tempDir.resolve("b.txt"), "newB")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        Files.writeString(dest.resolve("a.txt"), "oldA")
        Files.writeString(dest.resolve("b.txt"), "oldB")

        fileOps.copyFilesWithProgress(
            sources = listOf(tempDir.resolve("a.txt"), tempDir.resolve("b.txt")),
            destination = dest,
            overwriteAll = true,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { fail("Should not prompt when overwriteAll=true"); OverwriteResponse.NO },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals("newA", Files.readString(dest.resolve("a.txt")))
        assertEquals("newB", Files.readString(dest.resolve("b.txt")))
    }

    fun testCopyCancellation() = runBlocking {
        Files.writeString(tempDir.resolve("a.txt"), "a")
        Files.writeString(tempDir.resolve("b.txt"), "b")
        val dest = Files.createDirectory(tempDir.resolve("dest"))

        fileOps.copyFilesWithProgress(
            sources = listOf(tempDir.resolve("a.txt"), tempDir.resolve("b.txt")),
            destination = dest,
            overwriteAll = false,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.NO },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { true }, // immediately cancelled
        )

        // At least one file should not be copied since we cancelled immediately
        val copiedCount = listOf("a.txt", "b.txt").count { Files.exists(dest.resolve(it)) }
        assertTrue("Cancellation should prevent copying all files", copiedCount < 2)
    }

    // --- Move ---

    fun testMoveSingleFile() = runBlocking {
        val source = Files.writeString(tempDir.resolve("moveme.txt"), "data")
        val dest = Files.createDirectory(tempDir.resolve("dest"))

        fileOps.moveFilesWithProgress(
            sources = listOf(source),
            destination = dest,
            overwriteAll = false,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.NO },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertFalse("Source should no longer exist", Files.exists(source))
        assertTrue("Moved file should exist at destination", Files.exists(dest.resolve("moveme.txt")))
        assertEquals("data", Files.readString(dest.resolve("moveme.txt")))
    }

    fun testMoveOverwriteSkip() = runBlocking {
        val source = Files.writeString(tempDir.resolve("x.txt"), "new")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        Files.writeString(dest.resolve("x.txt"), "old")

        fileOps.moveFilesWithProgress(
            sources = listOf(source),
            destination = dest,
            overwriteAll = false,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.NO },
            onError = { _, _ -> },
            isCancelled = { false },
        )

        // Source should still exist because move was skipped
        assertTrue("Source should still exist when overwrite declined", Files.exists(source))
        assertEquals("old", Files.readString(dest.resolve("x.txt")))
    }

    // --- Delete ---

    fun testDeleteSingleFile() = runBlocking {
        val file = Files.writeString(tempDir.resolve("del.txt"), "bye")

        fileOps.deleteFilesWithProgress(
            paths = listOf(file),
            onProgress = { _, _ -> },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertFalse("File should be deleted", Files.exists(file))
    }

    fun testDeleteDirectoryRecursive() = runBlocking {
        val dir = Files.createDirectory(tempDir.resolve("delDir"))
        Files.writeString(dir.resolve("a.txt"), "a")
        val subDir = Files.createDirectory(dir.resolve("sub"))
        Files.writeString(subDir.resolve("b.txt"), "b")

        var deletedCount = 0
        fileOps.deleteFilesWithProgress(
            paths = listOf(dir),
            onProgress = { count, _ -> deletedCount = count },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertFalse("Directory should be deleted", Files.exists(dir))
        assertTrue("Should have deleted multiple items", deletedCount >= 3) // a.txt, b.txt, sub, delDir
    }

    fun testDeleteMultipleFiles() = runBlocking {
        val f1 = Files.writeString(tempDir.resolve("one.txt"), "1")
        val f2 = Files.writeString(tempDir.resolve("two.txt"), "2")
        val f3 = Files.writeString(tempDir.resolve("three.txt"), "3")

        fileOps.deleteFilesWithProgress(
            paths = listOf(f1, f2, f3),
            onProgress = { _, _ -> },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertFalse(Files.exists(f1))
        assertFalse(Files.exists(f2))
        assertFalse(Files.exists(f3))
    }

    // --- Rename ---

    fun testRenameFile() = runBlocking {
        val file = Files.writeString(tempDir.resolve("old.txt"), "content")

        val result = fileOps.renameFile(file, "new.txt")

        assertFalse(Files.exists(file))
        assertTrue(Files.exists(result))
        assertEquals("new.txt", result.fileName.toString())
        assertEquals("content", Files.readString(result))
    }

    fun testRenameDirectory() = runBlocking {
        val dir = Files.createDirectory(tempDir.resolve("oldDir"))
        Files.writeString(dir.resolve("child.txt"), "inside")

        val result = fileOps.renameFile(dir, "newDir")

        assertFalse(Files.exists(dir))
        assertTrue(Files.isDirectory(result))
        assertEquals("inside", Files.readString(result.resolve("child.txt")))
    }

    // --- Create Directory ---

    fun testCreateDirectory() = runBlocking {
        val result = fileOps.createDirectory(tempDir, "myFolder")

        assertTrue(Files.isDirectory(result))
        assertEquals("myFolder", result.fileName.toString())
    }

    // --- List Files ---

    fun testListFilesReturnsParentLink() = runBlocking {
        val subDir = Files.createDirectory(tempDir.resolve("sub"))
        Files.writeString(subDir.resolve("file.txt"), "x")

        val entries = fileOps.listFiles(subDir)

        val parentLink = entries.find { it.isParentLink }
        assertNotNull("Should include parent link", parentLink)
        assertEquals("..", parentLink!!.name)
        assertEquals(tempDir, parentLink.path)
    }

    fun testListFilesSeparatesDirectoriesAndFiles() = runBlocking {
        Files.createDirectory(tempDir.resolve("zDir"))
        Files.createDirectory(tempDir.resolve("aDir"))
        Files.writeString(tempDir.resolve("bFile.txt"), "x")
        Files.writeString(tempDir.resolve("aFile.txt"), "x")

        val entries = fileOps.listFiles(tempDir)
        val realEntries = entries.filter { !it.isParentLink }

        // Directories should come before files (default sortWithDirectories=false)
        val firstFileIndex = realEntries.indexOfFirst { !it.isDirectory }
        val lastDirIndex = realEntries.indexOfLast { it.isDirectory }

        if (firstFileIndex >= 0 && lastDirIndex >= 0) {
            assertTrue("Directories should come before files", lastDirIndex < firstFileIndex)
        }
    }

    fun testListFilesAlphabetical() = runBlocking {
        Files.writeString(tempDir.resolve("cherry.txt"), "x")
        Files.writeString(tempDir.resolve("apple.txt"), "x")
        Files.writeString(tempDir.resolve("banana.txt"), "x")

        val entries = fileOps.listFiles(tempDir)
        val fileNames = entries.filter { !it.isParentLink && !it.isDirectory }.map { it.name }

        assertEquals(listOf("apple.txt", "banana.txt", "cherry.txt"), fileNames)
    }

    fun testListEmptyDirectory() = runBlocking {
        val emptyDir = Files.createDirectory(tempDir.resolve("empty"))

        val entries = fileOps.listFiles(emptyDir)

        // Should only contain the parent link
        assertEquals(1, entries.size)
        assertTrue(entries[0].isParentLink)
    }

    // --- Get Roots ---

    fun testGetRootsReturnsNonEmpty() {
        val roots = fileOps.getRoots()
        assertTrue("Should return at least one root", roots.isNotEmpty())
    }

    fun testGetRootsContainsHome() {
        val roots = fileOps.getRoots()
        val home = System.getProperty("user.home")
        assertTrue("Roots should contain home directory", roots.any { it == home })
    }
}
