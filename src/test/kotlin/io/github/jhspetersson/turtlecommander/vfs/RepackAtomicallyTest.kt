package io.github.jhspetersson.turtlecommander.vfs

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies the atomic-repack guarantee: a repack that fails partway must leave the original
 * archive intact (the temp dir it was extracted from is deleted on close, so the on-disk archive
 * is often the only surviving copy of the data) and must not leave temp files behind.
 */
class RepackAtomicallyTest {

    private val dir: Path = Files.createTempDirectory("repack-atomic-")

    @After
    fun cleanup() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun repackTempLeftovers(): Long =
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().startsWith("tc-repack-") }.count()
        }

    @Test
    fun `failure mid-repack preserves the original archive and leaves no temp file`() {
        val archive = dir.resolve("archive.bin")
        Files.writeString(archive, "ORIGINAL")

        val thrown = try {
            repackAtomically(archive) { target ->
                // Write a partial/garbage archive, then fail — exactly the "disk full / unreadable
                // entry mid-repack" scenario.
                Files.writeString(target, "HALF-WRITTEN-GARBAGE")
                throw IOException("boom")
            }
            null
        } catch (e: IOException) {
            e
        }

        assertNotNull("the failure must propagate to the caller", thrown)
        assertEquals("boom", thrown!!.message)
        assertEquals("the original archive must be untouched", "ORIGINAL", Files.readString(archive))
        assertEquals("the repack temp file must be cleaned up", 0, repackTempLeftovers())
    }

    @Test
    fun `successful repack replaces the archive atomically`() {
        val archive = dir.resolve("archive.bin")
        Files.writeString(archive, "ORIGINAL")

        repackAtomically(archive) { target ->
            Files.writeString(target, "NEW CONTENT")
        }

        assertEquals("NEW CONTENT", Files.readString(archive))
        assertEquals("no repack temp file should survive a success", 0, repackTempLeftovers())
    }
}
