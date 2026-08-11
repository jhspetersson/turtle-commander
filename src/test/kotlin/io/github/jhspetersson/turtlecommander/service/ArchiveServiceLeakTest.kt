package io.github.jhspetersson.turtlecommander.service

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    @Test
    fun `countArchiveEntriesFast recognises aar and apkg as zip-format`() {
        val svc = ArchiveService()
        // .aar (Android library) and .apkg (Anki deck) are both zip-format. The fast-count
        // table used to omit them, forcing a full extract via the VFS fallback. Returning
        // the expected entry count here verifies they take the header-only path.
        for (suffix in listOf(".aar", ".aab", ".apkg", ".vsix", ".ipa", ".appx", ".appxbundle", ".msix", ".msixbundle", ".xps", ".oxps", ".kmz", ".usdz", ".oxt", ".pk3", ".pk4", ".xapk", ".apks", ".apkm")) {
            val zip = Files.createTempFile("fastcount-", suffix)
            tempFiles.add(zip)
            ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
                zos.putNextEntry(ZipEntry("a.txt")); zos.write("a".toByteArray()); zos.closeEntry()
                zos.putNextEntry(ZipEntry("b.txt")); zos.write("b".toByteArray()); zos.closeEntry()
            }
            assertEquals("expected fast-count to recognise $suffix", 2, svc.countArchiveEntriesFast(zip))
        }
    }

    @Test
    fun `countArchiveEntriesFast recognises jmod despite its 4-byte header`() {
        val svc = ArchiveService()
        val jmod = Files.createTempFile("fastcount-", ".jmod")
        tempFiles.add(jmod)
        Files.newOutputStream(jmod).use { os ->
            os.write(byteArrayOf('J'.code.toByte(), 'M'.code.toByte(), 1, 0))
            ZipOutputStream(os).use { zos ->
                zos.putNextEntry(ZipEntry("classes/module-info.class")); zos.write(1); zos.closeEntry()
                zos.putNextEntry(ZipEntry("conf/settings.properties")); zos.write(2); zos.closeEntry()
            }
        }
        assertEquals("expected fast-count to recognise .jmod", 2, svc.countArchiveEntriesFast(jmod))
    }

    @Test
    fun `countArchiveEntriesFast recognises cb7 and cbt comic archives`() {
        val svc = ArchiveService()

        val cb7 = Files.createTempFile("fastcount-", ".cb7")
        tempFiles.add(cb7)
        SevenZOutputFile(cb7.toFile()).use { out ->
            for (name in listOf("page1.jpg", "page2.jpg")) {
                out.putArchiveEntry(SevenZArchiveEntry().apply { this.name = name })
                out.write(byteArrayOf(1))
                out.closeArchiveEntry()
            }
        }
        assertEquals("expected fast-count to recognise .cb7", 2, svc.countArchiveEntriesFast(cb7))

        val cbt = Files.createTempFile("fastcount-", ".cbt")
        tempFiles.add(cbt)
        TarArchiveOutputStream(Files.newOutputStream(cbt)).use { tos ->
            for (name in listOf("page1.jpg", "page2.jpg")) {
                tos.putArchiveEntry(TarArchiveEntry(name).apply { size = 1 })
                tos.write(1)
                tos.closeArchiveEntry()
            }
        }
        assertEquals("expected fast-count to recognise .cbt", 2, svc.countArchiveEntriesFast(cbt))
    }
}
