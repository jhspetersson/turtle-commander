package io.github.jhspetersson.turtlecommander.service

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression test for a file-handle leak in ArchiveService.countArchiveEntriesFast:
 * when a compressor constructor (Gzip/BZip2/XZ) threw on a corrupt archive, the
 * underlying Files.newInputStream was never closed, leaking a file descriptor.
 *
 * We verify the fix by observing whether the raw stream gets closed when the
 * compressor constructor throws — testing the refactored countTarCompressed helper.
 */
class ArchiveServiceLeakTest {

    private val tempFiles = mutableListOf<Path>()

    @After
    fun cleanup() {
        for (path in tempFiles.reversed()) {
            runCatching { Files.deleteIfExists(path) }
        }
    }

    private class TrackingInputStream(source: InputStream) : FilterInputStream(source) {
        @Volatile var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    @Test
    fun `corrupt stream fed directly to GzipCompressorInputStream leaks without use block`() {
        // Demonstrates the bug pattern: constructor throws, source stays open.
        val source = TrackingInputStream(Files.newInputStream(writeCorrupt(".tar.gz")))
        try {
            GzipCompressorInputStream(source)
            fail("expected the constructor to throw on corrupt gzip header")
        } catch (_: IOException) {
            // expected
        }
        assertFalse("buggy pattern: source stream was NOT closed", source.closed)
        source.close()
    }

    @Test
    fun `countTarCompressed closes the raw stream when wrap throws`() {
        val svc = ArchiveService()
        val tracker = TrackingInputStream(Files.newInputStream(writeCorrupt(".tar.gz")))
        try {
            svc.countTarCompressed(tracker) { throw IOException("simulated bad header") }
            fail("expected the helper to propagate the wrap exception")
        } catch (_: IOException) {
            // expected
        }
        assertTrue("raw source stream must be closed when wrap throws", tracker.closed)
    }

    @Test
    fun `countTarCompressed closes the raw stream on real corrupt gzip`() {
        val svc = ArchiveService()
        val tracker = TrackingInputStream(Files.newInputStream(writeCorrupt(".tar.gz")))
        try {
            svc.countTarCompressed(tracker) { GzipCompressorInputStream(it) }
            fail("expected GzipCompressorInputStream to throw on corrupt header")
        } catch (_: IOException) {
            // expected
        }
        assertTrue("raw source stream must be closed when real compressor throws", tracker.closed)
    }

    private fun writeCorrupt(suffix: String): Path {
        val path = Files.createTempFile("corrupt-", suffix)
        tempFiles.add(path)
        Files.write(path, ByteArray(256) { it.toByte() })
        return path
    }
}
