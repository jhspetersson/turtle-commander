package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileOperationService
import io.github.jhspetersson.turtlecommander.service.OverwritePolicy
import io.github.jhspetersson.turtlecommander.service.OverwriteResponse
import io.github.jhspetersson.turtlecommander.util.countFiles
import io.github.jhspetersson.turtlecommander.vfs.ZipVirtualFileSystem
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { count, _ -> progressCount = count },
            onOverwriteConfirm = { OverwriteResponse.SKIP },
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
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.SKIP },
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
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.OVERWRITE },
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
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.SKIP },
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
            initialPolicy = OverwritePolicy.OVERWRITE_ALL,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { fail("Should not prompt when overwriteAll=true"); OverwriteResponse.SKIP },
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
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.SKIP },
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
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.SKIP },
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
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.SKIP },
            onError = { _, _ -> },
            isCancelled = { false },
        )

        // Source should still exist because move was skipped
        assertTrue("Source should still exist when overwrite declined", Files.exists(source))
        assertEquals("old", Files.readString(dest.resolve("x.txt")))
    }

    // --- Overwrite policies ---
    //
    // These cover the seven OverwriteResponse values and the four OverwritePolicy values added
    // when the per-file prompt grew from the original Yes/No/Yes-to-All/No-to-All to the
    // richer Total-Commander-style button set. mtime is set explicitly with
    // Files.setLastModifiedTime so the IF_OLDER comparison is deterministic regardless of how
    // close together the writes happen.

    private fun writeWithMtime(path: Path, content: String, millis: Long): Path {
        val p = Files.writeString(path, content)
        Files.setLastModifiedTime(p, FileTime.fromMillis(millis))
        return p
    }

    fun testCopyIfOlderPolicyOverwritesNewerSource() = runBlocking {
        val source = writeWithMtime(tempDir.resolve("a.txt"), "new", 20_000)
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        writeWithMtime(dest.resolve("a.txt"), "old", 10_000)

        fileOps.copyFilesWithProgress(
            sources = listOf(source),
            destination = dest,
            initialPolicy = OverwritePolicy.IF_OLDER,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { fail("IF_OLDER policy must not prompt"); OverwriteResponse.SKIP },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals("Newer source must overwrite older target", "new", Files.readString(dest.resolve("a.txt")))
    }

    fun testCopyIfOlderPolicySkipsOlderSource() = runBlocking {
        val source = writeWithMtime(tempDir.resolve("a.txt"), "stale", 10_000)
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        writeWithMtime(dest.resolve("a.txt"), "fresh", 20_000)

        fileOps.copyFilesWithProgress(
            sources = listOf(source),
            destination = dest,
            initialPolicy = OverwritePolicy.IF_OLDER,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { fail("IF_OLDER policy must not prompt"); OverwriteResponse.SKIP },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals("Older source must not clobber newer target", "fresh", Files.readString(dest.resolve("a.txt")))
    }

    fun testCopyIfOlderPolicySkipsEqualMtime() = runBlocking {
        // Equal mtime is treated as "not strictly newer" → skip. Matches Total Commander's
        // "Overwrite all older and of the same age" being the *separate* option in the original
        // dialog; the plain "if older" rule deliberately excludes ties.
        val source = writeWithMtime(tempDir.resolve("a.txt"), "src", 15_000)
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        writeWithMtime(dest.resolve("a.txt"), "tgt", 15_000)

        fileOps.copyFilesWithProgress(
            sources = listOf(source),
            destination = dest,
            initialPolicy = OverwritePolicy.IF_OLDER,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { fail("IF_OLDER policy must not prompt"); OverwriteResponse.SKIP },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals("Equal mtime → skip", "tgt", Files.readString(dest.resolve("a.txt")))
    }

    fun testCopySkipAllPolicyDoesNotPromptAndDoesNotClobber() = runBlocking {
        val sourceA = Files.writeString(tempDir.resolve("a.txt"), "newA")
        val sourceB = Files.writeString(tempDir.resolve("b.txt"), "newB")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        Files.writeString(dest.resolve("a.txt"), "oldA")
        Files.writeString(dest.resolve("b.txt"), "oldB")

        fileOps.copyFilesWithProgress(
            sources = listOf(sourceA, sourceB),
            destination = dest,
            initialPolicy = OverwritePolicy.SKIP_ALL,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { fail("SKIP_ALL policy must not prompt"); OverwriteResponse.SKIP },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals("oldA", Files.readString(dest.resolve("a.txt")))
        assertEquals("oldB", Files.readString(dest.resolve("b.txt")))
    }

    fun testCopyOverwriteIfOlderResponseAppliesPerFile() = runBlocking {
        // ASK policy + per-file OVERWRITE_IF_OLDER response. The policy must stay ASK after the
        // response — subsequent files trigger the prompt again.
        val sourceA = writeWithMtime(tempDir.resolve("a.txt"), "newA", 20_000)
        val sourceB = writeWithMtime(tempDir.resolve("b.txt"), "staleB", 10_000)
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        writeWithMtime(dest.resolve("a.txt"), "oldA", 10_000)
        writeWithMtime(dest.resolve("b.txt"), "freshB", 20_000)

        var prompts = 0
        fileOps.copyFilesWithProgress(
            sources = listOf(sourceA, sourceB),
            destination = dest,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { prompts++; OverwriteResponse.OVERWRITE_IF_OLDER },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals("Both existing files should prompt", 2, prompts)
        assertEquals("Newer source overwrites older target", "newA", Files.readString(dest.resolve("a.txt")))
        assertEquals("Older source leaves newer target alone", "freshB", Files.readString(dest.resolve("b.txt")))
    }

    fun testCopyOverwriteAllIfOlderResponseUpgradesPolicy() = runBlocking {
        // ASK policy + OVERWRITE_ALL_IF_OLDER response. After the first prompt, the policy
        // should upgrade to IF_OLDER so the second file is decided without prompting.
        val sourceA = writeWithMtime(tempDir.resolve("a.txt"), "newA", 20_000)
        val sourceB = writeWithMtime(tempDir.resolve("b.txt"), "staleB", 10_000)
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        writeWithMtime(dest.resolve("a.txt"), "oldA", 10_000)
        writeWithMtime(dest.resolve("b.txt"), "freshB", 20_000)

        var prompts = 0
        fileOps.copyFilesWithProgress(
            sources = listOf(sourceA, sourceB),
            destination = dest,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { prompts++; OverwriteResponse.OVERWRITE_ALL_IF_OLDER },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals("Policy upgrade after first response should silence further prompts", 1, prompts)
        assertEquals("newA", Files.readString(dest.resolve("a.txt")))
        assertEquals("freshB", Files.readString(dest.resolve("b.txt")))
    }

    fun testCopyOverwriteAllResponseUpgradesPolicy() = runBlocking {
        // ASK + OVERWRITE_ALL: first prompt sets policy to OVERWRITE_ALL, subsequent files
        // overwrite without prompting.
        val sourceA = Files.writeString(tempDir.resolve("a.txt"), "newA")
        val sourceB = Files.writeString(tempDir.resolve("b.txt"), "newB")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        Files.writeString(dest.resolve("a.txt"), "oldA")
        Files.writeString(dest.resolve("b.txt"), "oldB")

        var prompts = 0
        fileOps.copyFilesWithProgress(
            sources = listOf(sourceA, sourceB),
            destination = dest,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { prompts++; OverwriteResponse.OVERWRITE_ALL },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals(1, prompts)
        assertEquals("newA", Files.readString(dest.resolve("a.txt")))
        assertEquals("newB", Files.readString(dest.resolve("b.txt")))
    }

    fun testCopySkipAllResponseUpgradesPolicy() = runBlocking {
        // ASK + SKIP_ALL: first prompt sets policy to SKIP_ALL, second file skipped silently.
        val sourceA = Files.writeString(tempDir.resolve("a.txt"), "newA")
        val sourceB = Files.writeString(tempDir.resolve("b.txt"), "newB")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        Files.writeString(dest.resolve("a.txt"), "oldA")
        Files.writeString(dest.resolve("b.txt"), "oldB")

        var prompts = 0
        fileOps.copyFilesWithProgress(
            sources = listOf(sourceA, sourceB),
            destination = dest,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { prompts++; OverwriteResponse.SKIP_ALL },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals(1, prompts)
        assertEquals("oldA", Files.readString(dest.resolve("a.txt")))
        assertEquals("oldB", Files.readString(dest.resolve("b.txt")))
    }

    fun testCopyCancelResponseAbortsLoop() = runBlocking {
        // CANCEL on the first existing file: subsequent files (existing OR not) must be untouched.
        val sourceA = Files.writeString(tempDir.resolve("a.txt"), "newA")
        val sourceB = Files.writeString(tempDir.resolve("b.txt"), "newB")
        val sourceC = Files.writeString(tempDir.resolve("c.txt"), "newC")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        Files.writeString(dest.resolve("a.txt"), "oldA")
        // b.txt and c.txt do NOT exist in dest — the cancel must still stop them being copied.

        var prompts = 0
        fileOps.copyFilesWithProgress(
            sources = listOf(sourceA, sourceB, sourceC),
            destination = dest,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { prompts++; OverwriteResponse.CANCEL },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals("Only one prompt before cancel aborts", 1, prompts)
        assertEquals("oldA", Files.readString(dest.resolve("a.txt")))
        assertFalse("CANCEL must stop processing later sources", Files.exists(dest.resolve("b.txt")))
        assertFalse("CANCEL must stop processing later sources", Files.exists(dest.resolve("c.txt")))
    }

    fun testMoveIfOlderPolicyOverwritesNewerSource() = runBlocking {
        val source = writeWithMtime(tempDir.resolve("m.txt"), "fresh", 20_000)
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        writeWithMtime(dest.resolve("m.txt"), "stale", 10_000)

        fileOps.moveFilesWithProgress(
            sources = listOf(source),
            destination = dest,
            initialPolicy = OverwritePolicy.IF_OLDER,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { fail("IF_OLDER must not prompt"); OverwriteResponse.SKIP },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertFalse("Source removed after successful move", Files.exists(source))
        assertEquals("fresh", Files.readString(dest.resolve("m.txt")))
    }

    fun testMoveIfOlderPolicyLeavesOlderSourceInPlace() = runBlocking {
        val source = writeWithMtime(tempDir.resolve("m.txt"), "stale", 10_000)
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        writeWithMtime(dest.resolve("m.txt"), "fresh", 20_000)

        fileOps.moveFilesWithProgress(
            sources = listOf(source),
            destination = dest,
            initialPolicy = OverwritePolicy.IF_OLDER,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { fail("IF_OLDER must not prompt"); OverwriteResponse.SKIP },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        // Skipped — source must still be at its original location, target unchanged.
        assertTrue("Source left in place when skipped", Files.exists(source))
        assertEquals("fresh", Files.readString(dest.resolve("m.txt")))
    }

    fun testMoveCancelResponseAbortsLoop() = runBlocking {
        val sourceA = Files.writeString(tempDir.resolve("a.txt"), "A")
        val sourceB = Files.writeString(tempDir.resolve("b.txt"), "B")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        Files.writeString(dest.resolve("a.txt"), "oldA")

        fileOps.moveFilesWithProgress(
            sources = listOf(sourceA, sourceB),
            destination = dest,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.CANCEL },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertTrue("Cancelled source remains in place", Files.exists(sourceA))
        assertTrue("Subsequent source remains in place", Files.exists(sourceB))
        assertEquals("Target untouched after cancel", "oldA", Files.readString(dest.resolve("a.txt")))
        assertFalse("Later target never created", Files.exists(dest.resolve("b.txt")))
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

    fun testDeleteToRecycleBinUsesMoveToTrash() = runBlocking {
        val f1 = Files.writeString(tempDir.resolve("r1.txt"), "1")
        val f2 = Files.writeString(tempDir.resolve("r2.txt"), "2")
        val subDir = Files.createDirectory(tempDir.resolve("rdir"))
        Files.writeString(subDir.resolve("inner.txt"), "inner")

        val trashed = mutableListOf<Path>()
        fileOps.isMoveToTrashSupported = { true }
        fileOps.moveToTrash = { path ->
            trashed.add(path)
            // Simulate OS trash by deleting (so the file no longer appears at the original
            // location) — real moveToTrash does the same from the filesystem's perspective.
            if (Files.isDirectory(path)) path.toFile().deleteRecursively()
            else Files.deleteIfExists(path)
            true
        }

        val progressCounts = mutableListOf<Int>()
        fileOps.deleteFilesWithProgress(
            paths = listOf(f1, f2, subDir),
            onProgress = { count, _ -> progressCounts.add(count) },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
            useRecycleBin = true,
        )

        assertEquals("moveToTrash called per top-level path", 3, trashed.size)
        assertEquals(listOf(1, 2, 3), progressCounts)
        assertFalse(Files.exists(f1))
        assertFalse(Files.exists(f2))
        assertFalse(Files.exists(subDir))
    }

    fun testDeleteToRecycleBinReportsErrorWhenMoveFails() = runBlocking {
        val f1 = Files.writeString(tempDir.resolve("ok.txt"), "1")
        val f2 = Files.writeString(tempDir.resolve("bad.txt"), "2")

        fileOps.isMoveToTrashSupported = { true }
        fileOps.moveToTrash = { path ->
            if (path.fileName.toString() == "bad.txt") {
                false
            } else {
                Files.deleteIfExists(path)
                true
            }
        }

        val errors = mutableListOf<Path>()
        fileOps.deleteFilesWithProgress(
            paths = listOf(f1, f2),
            onProgress = { _, _ -> },
            onError = { path, _ -> errors.add(path) },
            isCancelled = { false },
            useRecycleBin = true,
        )

        assertFalse("Successful path was trashed", Files.exists(f1))
        assertTrue("Failing path is left alone", Files.exists(f2))
        assertEquals(listOf(f2), errors)
    }

    fun testDeleteToRecycleBinRefusesWhenUnsupported() = runBlocking {
        // The user confirmed a *reversible* operation; if the platform has no trash the
        // service must refuse and report errors, never silently downgrade to a permanent
        // delete. (The UI checks availability before showing the recycle-bin dialog, so
        // this is the defense-in-depth layer.)
        val f1 = Files.writeString(tempDir.resolve("fallback.txt"), "x")

        var moveCalled = false
        fileOps.isMoveToTrashSupported = { false }
        fileOps.moveToTrash = { _ -> moveCalled = true; true }

        val errors = mutableListOf<Path>()
        fileOps.deleteFilesWithProgress(
            paths = listOf(f1),
            onProgress = { _, _ -> },
            onError = { path, _ -> errors.add(path) },
            isCancelled = { false },
            useRecycleBin = true,
        )

        assertFalse("moveToTrash should not be invoked when unsupported", moveCalled)
        assertTrue("File must NOT be deleted permanently", Files.exists(f1))
        assertEquals("Every path should be reported as an error", listOf(f1), errors)
    }

    fun testDeleteDefaultIsPermanent() = runBlocking {
        // Default (no useRecycleBin flag) must not touch the trash hook, so it continues to
        // behave exactly like the pre-feature implementation.
        val f1 = Files.writeString(tempDir.resolve("perm.txt"), "x")

        var moveCalled = false
        fileOps.isMoveToTrashSupported = { true }
        fileOps.moveToTrash = { _ -> moveCalled = true; true }

        fileOps.deleteFilesWithProgress(
            paths = listOf(f1),
            onProgress = { _, _ -> },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertFalse("Default delete must not hit the trash hook", moveCalled)
        assertFalse(Files.exists(f1))
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

    // --- Listing cache ---

    fun testListingCacheIsPopulatedOnFirstCall() = runBlocking {
        val dir = Files.createDirectory(tempDir.resolve("cache-a"))
        Files.writeString(dir.resolve("x.txt"), "x")

        fileOps.clearListingCache()
        assertFalse(fileOps.isListingCached(dir))

        fileOps.listFiles(dir)

        assertTrue("Directory should be cached after first call", fileOps.isListingCached(dir))
    }

    fun testListingCacheDetectsExternalMtimeChange() = runBlocking {
        val dir = Files.createDirectory(tempDir.resolve("cache-b"))
        Files.writeString(dir.resolve("before.txt"), "x")

        fileOps.clearListingCache()
        val first = fileOps.listFiles(dir).map { it.name }.toSet()
        assertTrue("before.txt" in first)

        // Bump mtime explicitly so the test isn't sensitive to filesystem timer resolution
        // (some filesystems round mtime to the nearest second, so two writes in the same
        // second would produce identical mtimes).
        val bumped = FileTime.fromMillis(Files.getLastModifiedTime(dir).toMillis() + 2000)
        Files.writeString(dir.resolve("after.txt"), "y")
        Files.setLastModifiedTime(dir, bumped)

        val second = fileOps.listFiles(dir).map { it.name }.toSet()
        assertTrue("Cache should auto-invalidate when dir mtime changes", "after.txt" in second)
        assertTrue("before.txt" in second)
    }

    fun testListingCacheFreshnessReflectsMtime() = runBlocking {
        val dir = Files.createDirectory(tempDir.resolve("cache-fresh"))
        Files.writeString(dir.resolve("a.txt"), "a")

        fileOps.clearListingCache()
        fileOps.listFiles(dir)
        assertTrue("Should be fresh right after listing", fileOps.isListingCacheFresh(dir))

        val bumped = FileTime.fromMillis(Files.getLastModifiedTime(dir).toMillis() + 2000)
        Files.writeString(dir.resolve("b.txt"), "b")
        Files.setLastModifiedTime(dir, bumped)

        assertFalse("Should be stale after external mtime change", fileOps.isListingCacheFresh(dir))
    }

    fun testListingCacheRefreshedOnForceRefresh() = runBlocking {
        val dir = Files.createDirectory(tempDir.resolve("cache-c"))
        Files.writeString(dir.resolve("before.txt"), "x")

        fileOps.clearListingCache()
        fileOps.listFiles(dir)

        Files.writeString(dir.resolve("after.txt"), "y")

        val refreshed = fileOps.listFiles(dir, forceRefresh = true).map { it.name }.toSet()
        assertTrue("forceRefresh=true should re-read", "after.txt" in refreshed)
        assertTrue("before.txt" in refreshed)
    }

    fun testListingCacheInvalidatedByInvalidateListingCache() = runBlocking {
        val dir = Files.createDirectory(tempDir.resolve("cache-d"))
        Files.writeString(dir.resolve("before.txt"), "x")

        fileOps.clearListingCache()
        fileOps.listFiles(dir)
        assertTrue(fileOps.isListingCached(dir))

        Files.writeString(dir.resolve("after.txt"), "y")
        fileOps.invalidateListingCache(dir)
        assertFalse(fileOps.isListingCached(dir))

        val refreshed = fileOps.listFiles(dir).map { it.name }.toSet()
        assertTrue("after invalidation, new file should be seen", "after.txt" in refreshed)
    }

    fun testListingCacheRespectsMaxSize() = runBlocking {
        fileOps.clearListingCache()

        val dirs = mutableListOf<Path>()
        for (i in 0 until FileOperationService.CACHE_MAX_SIZE + 5) {
            val d = Files.createDirectory(tempDir.resolve("cache-cap-$i"))
            dirs.add(d)
            fileOps.listFiles(d)
        }

        assertEquals(
            "Cache should never exceed max size",
            FileOperationService.CACHE_MAX_SIZE,
            fileOps.listingCacheSize(),
        )

        // The oldest entries should have been evicted (LRU); the most recently listed should still be cached.
        assertFalse("Oldest (eldest) entry should have been evicted", fileOps.isListingCached(dirs[0]))
        assertTrue("Most recent entry should still be cached", fileOps.isListingCached(dirs.last()))
    }

    fun testListingCacheLruOrderingKeepsRecentlyUsed() = runBlocking {
        fileOps.clearListingCache()

        val dirs = mutableListOf<Path>()
        for (i in 0 until FileOperationService.CACHE_MAX_SIZE) {
            val d = Files.createDirectory(tempDir.resolve("cache-lru-$i"))
            dirs.add(d)
            fileOps.listFiles(d)
        }

        // Touch the first entry so it becomes most-recently-used
        fileOps.listFiles(dirs[0])

        // Add one more entry — should evict index 1 (now the eldest), not index 0
        val extra = Files.createDirectory(tempDir.resolve("cache-lru-extra"))
        fileOps.listFiles(extra)

        assertTrue("Touched entry should still be cached", fileOps.isListingCached(dirs[0]))
        assertFalse("Untouched eldest entry should be evicted", fileOps.isListingCached(dirs[1]))
        assertTrue("Newly added entry should be cached", fileOps.isListingCached(extra))
    }

    fun testListingCacheExpiresAfterTtl() = runBlocking {
        val dir = Files.createDirectory(tempDir.resolve("cache-ttl"))
        Files.writeString(dir.resolve("before.txt"), "x")

        fileOps.clearListingCache()

        // Control the clock: start at T=0, advance past TTL. The test only checks TTL-based
        // expiry via `isListingCached` (which is timestamp-only); mtime-based invalidation is
        // covered separately so we don't need to perturb the directory here.
        var now = 1_000_000L
        fileOps.clock = { now }
        try {
            fileOps.listFiles(dir)
            assertTrue(fileOps.isListingCached(dir))

            // Just under TTL — still cached
            now += FileOperationService.CACHE_TTL_MS - 1
            assertTrue("Within TTL, entry should remain cached", fileOps.isListingCached(dir))

            // Past TTL — expired
            now += 2
            assertFalse("After TTL, entry should be expired", fileOps.isListingCached(dir))
        } finally {
            fileOps.clock = System::currentTimeMillis
        }
    }

    fun testListingCacheReturnsEquivalentContent() = runBlocking {
        val dir = Files.createDirectory(tempDir.resolve("cache-eq"))
        Files.createDirectory(dir.resolve("dir1"))
        Files.createDirectory(dir.resolve("dir2"))
        Files.writeString(dir.resolve("a.txt"), "a")
        Files.writeString(dir.resolve("b.txt"), "b")

        fileOps.clearListingCache()
        val first = fileOps.listFiles(dir).map { it.name }
        val second = fileOps.listFiles(dir).map { it.name }

        assertEquals("Cached and uncached listings should match", first, second)
        // Parent link, dir1, dir2, a.txt, b.txt
        assertEquals(5, first.size)
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

    // --- Bugfix regressions ---

    fun testDeleteProgressCountMatchesActualDeletions() = runBlocking {
        val f1 = Files.writeString(tempDir.resolve("f1.txt"), "1")
        val f2 = Files.writeString(tempDir.resolve("f2.txt"), "2")
        val f3 = Files.writeString(tempDir.resolve("f3.txt"), "3")

        val progressCounts = mutableListOf<Int>()
        fileOps.deleteFilesWithProgress(
            paths = listOf(f1, f2, f3),
            onProgress = { count, _ -> progressCounts.add(count) },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        // Progress should increment monotonically: 1, 2, 3
        assertEquals("Should report 3 progress updates", 3, progressCounts.size)
        assertEquals(listOf(1, 2, 3), progressCounts)
    }

    fun testDeleteProgressNotReportedOnError() = runBlocking {
        // Delete a directory that contains files — recursive walk deletes children,
        // then the directory itself. Each successful deletion increments count.
        val dir = Files.createDirectory(tempDir.resolve("delDir"))
        Files.writeString(dir.resolve("a.txt"), "a")
        Files.writeString(dir.resolve("b.txt"), "b")

        val progressCounts = mutableListOf<Int>()
        var errorCount = 0
        fileOps.deleteFilesWithProgress(
            paths = listOf(dir),
            onProgress = { count, _ -> progressCounts.add(count) },
            onError = { _, _ -> errorCount++ },
            isCancelled = { false },
        )

        // All progress counts should be sequential with no gaps
        for (i in progressCounts.indices) {
            assertEquals("Progress should be sequential", i + 1, progressCounts[i])
        }
        assertEquals("No errors expected", 0, errorCount)
    }

    fun testMoveProgressDoesNotCountSkipped() = runBlocking {
        val source1 = Files.writeString(tempDir.resolve("skip.txt"), "new")
        val source2 = Files.writeString(tempDir.resolve("move.txt"), "data")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        // Pre-create conflict for source1
        Files.writeString(dest.resolve("skip.txt"), "old")

        var progressCount = 0
        fileOps.moveFilesWithProgress(
            sources = listOf(source1, source2),
            destination = dest,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { count, _ -> progressCount = count },
            onOverwriteConfirm = { OverwriteResponse.SKIP },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals("Only actually moved files should be counted", 1, progressCount)
        assertTrue("Skipped file should still exist at source", Files.exists(source1))
        assertFalse("Moved file should not exist at source", Files.exists(source2))
    }

    fun testMoveProgressDoesNotCountAutoSkipped() = runBlocking {
        val source1 = Files.writeString(tempDir.resolve("a.txt"), "new1")
        val source2 = Files.writeString(tempDir.resolve("b.txt"), "new2")
        val source3 = Files.writeString(tempDir.resolve("c.txt"), "new3")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        // Pre-create conflicts for all
        Files.writeString(dest.resolve("a.txt"), "old1")
        Files.writeString(dest.resolve("b.txt"), "old2")
        Files.writeString(dest.resolve("c.txt"), "old3")

        var progressCount = 0
        fileOps.moveFilesWithProgress(
            sources = listOf(source1, source2, source3),
            destination = dest,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { count, _ -> progressCount = count },
            onOverwriteConfirm = { OverwriteResponse.SKIP_ALL },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )

        assertEquals("No files should be counted when all skipped", 0, progressCount)
        assertTrue("All source files should still exist", Files.exists(source1))
        assertTrue(Files.exists(source2))
        assertTrue(Files.exists(source3))
    }

    fun testRenameRootPathThrows() {
        val root = tempDir.root
        try {
            runBlocking { fileOps.renameFile(root, "impossible") }
            fail("Should throw for root path")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("root"))
        }
    }

    fun testCountFilesEmptyListReturnsZero() = runBlocking {
        val count = countFiles(emptyList())
        assertEquals("Empty source list should count 0", 0, count)
    }

    fun testCountFilesEmptyDirectoryReturnsOne() = runBlocking {
        val emptyDir = Files.createDirectory(tempDir.resolve("emptycount"))
        val count = countFiles(listOf(emptyDir))
        // Directory itself is counted (preVisitDirectory)
        assertEquals("Empty directory should count as 1", 1, count)
    }

    fun testDeleteEmptyListDoesNotCrash() = runBlocking {
        var progressCount = 0
        fileOps.deleteFilesWithProgress(
            paths = emptyList(),
            onProgress = { count, _ -> progressCount = count },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )
        assertEquals("No progress for empty list", 0, progressCount)
    }

    fun testMoveEmptyListDoesNotCrash() = runBlocking {
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        var progressCount = 0
        fileOps.moveFilesWithProgress(
            sources = emptyList(),
            destination = dest,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { count, _ -> progressCount = count },
            onOverwriteConfirm = { OverwriteResponse.SKIP },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )
        assertEquals("No progress for empty list", 0, progressCount)
    }

    fun testCopyEmptyListDoesNotCrash() = runBlocking {
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        var progressCount = 0
        fileOps.copyFilesWithProgress(
            sources = emptyList(),
            destination = dest,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { count, _ -> progressCount = count },
            onOverwriteConfirm = { OverwriteResponse.SKIP },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )
        assertEquals("No progress for empty list", 0, progressCount)
    }

    fun testCopyProgressDoesNotCountSkipped() = runBlocking {
        val src = Files.writeString(tempDir.resolve("file.txt"), "source")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        Files.writeString(dest.resolve("file.txt"), "existing")

        var progressCount = 0
        fileOps.copyFilesWithProgress(
            sources = listOf(src),
            destination = dest,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { count, _ -> progressCount = count },
            onOverwriteConfirm = { OverwriteResponse.SKIP },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )
        assertEquals("Skipped file should not count as copied", 0, progressCount)
        assertEquals("Original file should be preserved", "existing", Files.readString(dest.resolve("file.txt")))
    }

    fun testCopyProgressDoesNotCountAutoSkipped() = runBlocking {
        val src1 = Files.writeString(tempDir.resolve("a.txt"), "source1")
        val src2 = Files.writeString(tempDir.resolve("b.txt"), "source2")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        Files.writeString(dest.resolve("a.txt"), "existing1")
        Files.writeString(dest.resolve("b.txt"), "existing2")

        var progressCount = 0
        fileOps.copyFilesWithProgress(
            sources = listOf(src1, src2),
            destination = dest,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { count, _ -> progressCount = count },
            onOverwriteConfirm = { OverwriteResponse.SKIP_ALL },
            onError = { _, e -> fail("Unexpected error: $e") },
            isCancelled = { false },
        )
        assertEquals("Auto-skipped files should not count as copied", 0, progressCount)
    }

    fun testCopyDirectoryProgressNotCountedOnCreateFailure() = runBlocking {
        val srcDir = Files.createDirectory(tempDir.resolve("srcdir"))
        Files.writeString(srcDir.resolve("child.txt"), "data")
        val dest = Files.createDirectory(tempDir.resolve("dest"))
        // Create a file at the target path where a directory is expected,
        // so createDirectories will fail
        Files.writeString(dest.resolve("srcdir"), "blocker")

        var progressCount = 0
        var errorCount = 0
        fileOps.copyFilesWithProgress(
            sources = listOf(srcDir),
            destination = dest,
            initialPolicy = OverwritePolicy.ASK,
            onProgress = { count, _ -> progressCount = count },
            onOverwriteConfirm = { OverwriteResponse.OVERWRITE },
            onError = { _, _ -> errorCount++ },
            isCancelled = { false },
        )
        assertEquals("Failed directory creation should not count as progress", 0, progressCount)
        assertTrue("Should have reported an error", errorCount > 0)
    }

    // --- Copy into archive (VFS destination) ---

    fun testCopyFileIntoZipVfsDestination() = runBlocking {
        // Regression: copying a real file into a zip archive destination used to fail
        // because the archive path (e.g. Archive.zip) was used as the destination
        // instead of the VFS internal path, causing resolve() to produce
        // "Archive.zip/filename.txt" which doesn't exist on the real filesystem.
        val source = Files.writeString(tempDir.resolve("newfile.txt"), "new content")

        val zipPath = tempDir.resolve("archive.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(ZipEntry("existing.txt"))
            zos.write("existing".toByteArray())
            zos.closeEntry()
        }

        val vfs = ZipVirtualFileSystem(zipPath)
        vfs.use { vfs ->
            // Use the VFS root as the destination (the correct fix),
            // NOT the archive file path (which was the bug)
            var progressCount = 0
            fileOps.copyFilesWithProgress(
                sources = listOf(source),
                destination = vfs.root,
                initialPolicy = OverwritePolicy.ASK,
                onProgress = { count, _ -> progressCount = count },
                onOverwriteConfirm = { OverwriteResponse.SKIP },
                onError = { path, e -> fail("Copy into zip failed for $path: $e") },
                isCancelled = { false },
            )

            assertEquals("Should report 1 copied file", 1, progressCount)
            assertTrue("File should exist inside VFS", Files.exists(vfs.getPath("/newfile.txt")))
            assertEquals("new content", Files.readString(vfs.getPath("/newfile.txt")))

            // Flush and verify persistence
            vfs.flush()
            val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
            assertTrue("existing.txt should still be in archive", entries.any { it.name == "existing.txt" })
            assertTrue("newfile.txt should be in archive after flush", entries.any { it.name == "newfile.txt" })
        }
    }

    fun testCopyDirectoryIntoZipVfsDestination() = runBlocking {
        val srcDir = Files.createDirectory(tempDir.resolve("mydir"))
        Files.writeString(srcDir.resolve("child.txt"), "child content")

        val zipPath = tempDir.resolve("archive.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(ZipEntry("existing.txt"))
            zos.write("existing".toByteArray())
            zos.closeEntry()
        }

        val vfs = ZipVirtualFileSystem(zipPath)
        vfs.use { vfs ->
            var progressCount = 0
            fileOps.copyFilesWithProgress(
                sources = listOf(srcDir),
                destination = vfs.root,
                initialPolicy = OverwritePolicy.ASK,
                onProgress = { count, _ -> progressCount = count },
                onOverwriteConfirm = { OverwriteResponse.SKIP },
                onError = { path, e -> fail("Copy into zip failed for $path: $e") },
                isCancelled = { false },
            )

            assertTrue("Progress should have been reported", progressCount > 0)
            assertTrue("Directory should exist in VFS", Files.exists(vfs.getPath("/mydir")))
            assertTrue("Child file should exist in VFS", Files.exists(vfs.getPath("/mydir/child.txt")))
            assertEquals("child content", Files.readString(vfs.getPath("/mydir/child.txt")))
        }
    }

    fun testMoveFileIntoZipVfsDestination() = runBlocking {
        val source = Files.writeString(tempDir.resolve("moveme.txt"), "move content")

        val zipPath = tempDir.resolve("archive.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(ZipEntry("existing.txt"))
            zos.write("existing".toByteArray())
            zos.closeEntry()
        }

        val vfs = ZipVirtualFileSystem(zipPath)
        vfs.use { vfs ->
            var progressCount = 0
            fileOps.moveFilesWithProgress(
                sources = listOf(source),
                destination = vfs.root,
                initialPolicy = OverwritePolicy.ASK,
                onProgress = { count, _ -> progressCount = count },
                onOverwriteConfirm = { OverwriteResponse.SKIP },
                onError = { path, e -> fail("Move into zip failed for $path: $e") },
                isCancelled = { false },
            )

            assertEquals("Should report 1 moved file", 1, progressCount)
            assertTrue("File should exist inside VFS", Files.exists(vfs.getPath("/moveme.txt")))
            assertEquals("move content", Files.readString(vfs.getPath("/moveme.txt")))
            assertFalse("Source file should be removed after move", Files.exists(source))
        }
    }
}
