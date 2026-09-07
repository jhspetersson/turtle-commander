package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.random.Random

class CompressedStreamsTest {

    private class CountingInputStream(source: InputStream) : FilterInputStream(source) {
        var singleByteReads = 0
        var closed = false
        override fun read(): Int { singleByteReads++; return super.read() }
        override fun close() { closed = true; super.close() }
    }

    private class CountingOutputStream(sink: OutputStream) : FilterOutputStream(sink) {
        var singleByteWrites = 0
        var closed = false
        override fun write(b: Int) { singleByteWrites++; out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
        override fun close() { closed = true; super.close() }
    }

    private val payload = ByteArray(300_000).also { Random(7).nextBytes(it) }

    private val codecs: List<Triple<String, (OutputStream) -> OutputStream, (InputStream) -> InputStream>> = listOf(
        Triple("gzip", { o: OutputStream -> GzipCompressorOutputStream(o) }, { i: InputStream -> GzipCompressorInputStream(i) }),
        Triple("bzip2", { o: OutputStream -> BZip2CompressorOutputStream(o) }, { i: InputStream -> BZip2CompressorInputStream(i) }),
        Triple("xz", { o: OutputStream -> XZCompressorOutputStream(o) }, { i: InputStream -> XZCompressorInputStream(i) }),
        Triple("zstd", { o: OutputStream -> ZstdCompressorOutputStream(o) }, { i: InputStream -> ZstdCompressorInputStream(i) }),
    )

    private fun compressed(compress: (OutputStream) -> OutputStream): ByteArray =
        ByteArrayOutputStream().also { bos -> compress(bos).use { it.write(payload) } }.toByteArray()

    @Test
    fun `wrapInput never issues single-byte reads against the raw stream`() {
        for ((name, compress, decompress) in codecs) {
            val raw = CountingInputStream(ByteArrayInputStream(compressed(compress)))
            val decoded = CompressedStreams.wrapInput(raw, decompress).use { it.readBytes() }
            assertArrayEquals("$name round-trip", payload, decoded)
            assertEquals("$name must read the raw stream in bulk", 0, raw.singleByteReads)
        }
    }

    @Test
    fun `wrapOutput never issues single-byte writes against the raw stream`() {
        for ((name, compress, decompress) in codecs) {
            val sink = ByteArrayOutputStream()
            val raw = CountingOutputStream(sink)
            CompressedStreams.wrapOutput(raw, compress).use { it.write(payload) }
            assertEquals("$name must write the raw stream in bulk", 0, raw.singleByteWrites)
            assertTrue("$name must close the raw stream", raw.closed)
            val decoded = decompress(ByteArrayInputStream(sink.toByteArray())).use { it.readBytes() }
            assertArrayEquals("$name round-trip", payload, decoded)
        }
    }

    @Test
    fun `wrapInput closes the raw stream when the decompressor constructor throws`() {
        val raw = CountingInputStream(ByteArrayInputStream(ByteArray(256) { it.toByte() }))
        try {
            CompressedStreams.wrapInput(raw) { GzipCompressorInputStream(it) }
            fail("expected corrupt gzip header to throw")
        } catch (_: IOException) {
        }
        assertTrue(raw.closed)
    }

    @Test
    fun `wrapOutput closes the raw stream when the compressor constructor throws`() {
        val raw = CountingOutputStream(ByteArrayOutputStream())
        try {
            CompressedStreams.wrapOutput(raw) { throw IOException("simulated") }
            fail("expected the wrap exception to propagate")
        } catch (_: IOException) {
        }
        assertTrue(raw.closed)
    }
}
