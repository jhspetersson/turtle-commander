package io.github.jhspetersson.turtlecommander.service

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

class ArchiveServiceMultiMemberCountTest {

    private val tempFiles = mutableListOf<Path>()

    @After
    fun cleanup() {
        tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun tar(count: Int): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            repeat(count) { i ->
                val bytes = "entry $i".toByteArray()
                val entry = TarArchiveEntry("file$i.txt")
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private fun compress(suffix: String, bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val wrapped: OutputStream = when (suffix) {
            "gz" -> GzipCompressorOutputStream(out)
            "bz2" -> BZip2CompressorOutputStream(out)
            "xz" -> XZCompressorOutputStream(out)
            else -> throw IllegalArgumentException(suffix)
        }
        wrapped.use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun splitArchive(suffix: String): Path {
        val whole = tar(4)
        val cut = whole.size / 2
        val path = Files.createTempFile("tc-count-", ".tar.$suffix")
        tempFiles.add(path)
        Files.write(path, compress(suffix, whole.copyOfRange(0, cut)) + compress(suffix, whole.copyOfRange(cut, whole.size)))
        return path
    }

    @Test
    fun `fast count of a two-member tar gz sees every entry`() {
        assertEquals(4, ArchiveService().countArchiveEntriesFast(splitArchive("gz")))
    }

    @Test
    fun `fast count of a two-member tar bz2 sees every entry`() {
        assertEquals(4, ArchiveService().countArchiveEntriesFast(splitArchive("bz2")))
    }

    @Test
    fun `fast count of a two-member tar xz sees every entry`() {
        assertEquals(4, ArchiveService().countArchiveEntriesFast(splitArchive("xz")))
    }
}
