package io.github.jhspetersson.turtlecommander.action

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.model.FileEntry
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Tests for [pruneMovedEntriesFromCutBuffer] — settling the cut buffer after a cut-paste
 * move finishes: moved (gone) entries leave the buffer, still-present ones (failed, skipped,
 * or unreached after a cancel) stay cut for a retry, and a buffer refilled during the
 * transfer is left alone.
 */
class FileCopyBufferPruneTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path

    // The prune hops to Dispatchers.EDT internally; running the test off the EDT keeps
    // runBlocking from deadlocking against it.
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("turtle-test-cutbuffer-")
        FileCopyBuffer.entries = emptyList()
        FileCopyBuffer.isCut = false
    }

    override fun tearDown() {
        try {
            FileCopyBuffer.entries = emptyList()
            FileCopyBuffer.isCut = false
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun entryFor(name: String, create: Boolean = true): FileEntry {
        val path = tempDir.resolve(name)
        if (create) Files.writeString(path, name)
        return FileEntry(
            name = name,
            path = path,
            isDirectory = false,
            size = name.length.toLong(),
            lastModified = FileTime.fromMillis(1700000000000L),
            permissions = "rw-r--r--",
        )
    }

    fun testMovedEntriesLeaveBufferFailedOnesStay() = runBlocking {
        val moved = entryFor("moved.txt")
        val failed = entryFor("failed.txt")
        val consumed = listOf(moved, failed)
        FileCopyBuffer.isCut = true
        FileCopyBuffer.entries = consumed

        Files.delete(moved.path) // the move succeeded for this one

        pruneMovedEntriesFromCutBuffer(consumed)

        assertEquals("Only the still-present entry should stay cut", listOf(failed), FileCopyBuffer.entries)
        assertTrue(FileCopyBuffer.isCut)
    }

    fun testFullyMovedCutEmptiesBuffer() = runBlocking {
        val a = entryFor("a.txt")
        val b = entryFor("b.txt")
        val consumed = listOf(a, b)
        FileCopyBuffer.isCut = true
        FileCopyBuffer.entries = consumed

        Files.delete(a.path)
        Files.delete(b.path)

        pruneMovedEntriesFromCutBuffer(consumed)

        assertTrue("Buffer should be empty after a fully successful move", FileCopyBuffer.entries.isEmpty())
    }

    fun testBufferRefilledDuringTransferIsLeftAlone() = runBlocking {
        val moved = entryFor("moved.txt")
        val consumed = listOf(moved)
        FileCopyBuffer.isCut = true
        FileCopyBuffer.entries = consumed

        Files.delete(moved.path)
        // The user cut something else while the move was running.
        val newCut = listOf(entryFor("fresh.txt"))
        FileCopyBuffer.entries = newCut

        pruneMovedEntriesFromCutBuffer(consumed)

        assertEquals("A buffer refilled mid-transfer must win", newCut, FileCopyBuffer.entries)
    }

    fun testCopyBufferIsLeftAlone() = runBlocking {
        val entry = entryFor("copied.txt")
        val consumed = listOf(entry)
        FileCopyBuffer.isCut = false
        FileCopyBuffer.entries = consumed

        Files.delete(entry.path)

        pruneMovedEntriesFromCutBuffer(consumed)

        assertEquals("A copy buffer must never be pruned", consumed, FileCopyBuffer.entries)
    }
}
