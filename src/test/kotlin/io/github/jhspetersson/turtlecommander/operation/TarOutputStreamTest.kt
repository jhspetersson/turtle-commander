package io.github.jhspetersson.turtlecommander.operation

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class TarOutputStreamTest {

    private fun readEntries(archive: ByteArray): List<Pair<TarArchiveEntry, ByteArray>> {
        val result = mutableListOf<Pair<TarArchiveEntry, ByteArray>>()
        TarArchiveInputStream(ByteArrayInputStream(archive), "UTF-8").use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                result.add(entry to tar.readBytes())
                entry = tar.nextEntry
            }
        }
        return result
    }

    @Test
    fun `packed files round-trip through commons compress`() {
        val fileA = Files.createTempFile("tar-test-", ".bin")
        val fileB = Files.createTempFile("tar-test-", ".bin")
        try {
            val contentA = ByteArray(1000) { it.toByte() }
            Files.write(fileA, contentA)
            Files.write(fileB, "hello".toByteArray())

            val out = ByteArrayOutputStream()
            TarOutputStream(out).use { tar ->
                tar.putDirectoryEntry("dir/", 0)
                tar.putFileEntry("dir/a.bin", fileA, 1_600_000_000_000)
                tar.putFileEntry("b.txt", fileB, 1_600_000_000_000)
                tar.finish()
            }

            val entries = readEntries(out.toByteArray())
            assertEquals(listOf("dir/", "dir/a.bin", "b.txt"), entries.map { it.first.name })
            assertArrayEquals(contentA, entries[1].second)
            assertEquals("hello", entries[2].second.toString(Charsets.UTF_8))
        } finally {
            Files.deleteIfExists(fileA)
            Files.deleteIfExists(fileB)
        }
    }

    @Test
    fun `long names get a longlink entry`() {
        val file = Files.createTempFile("tar-test-", ".bin")
        try {
            Files.write(file, "x".toByteArray())
            val longName = "d/".repeat(60) + "leaf.txt"
            val out = ByteArrayOutputStream()
            TarOutputStream(out).use { tar ->
                tar.putFileEntry(longName, file, 0)
                tar.finish()
            }
            val entries = readEntries(out.toByteArray())
            assertEquals(listOf(longName), entries.map { it.first.name })
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `name under 100 chars but over 100 utf8 bytes round-trips intact`() {
        val file = Files.createTempFile("tar-test-", ".bin")
        try {
            Files.write(file, "x".toByteArray())
            val name = "漢".repeat(60) + ".txt"
            val out = ByteArrayOutputStream()
            TarOutputStream(out).use { tar ->
                tar.putFileEntry(name, file, 0)
                tar.finish()
            }
            val entries = readEntries(out.toByteArray())
            assertEquals(listOf(name), entries.map { it.first.name })
            assertEquals("x", entries[0].second.toString(Charsets.UTF_8))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `multibyte directory name over 100 bytes round-trips intact`() {
        val out = ByteArrayOutputStream()
        val name = "é".repeat(80) + "/"
        TarOutputStream(out).use { tar ->
            tar.putDirectoryEntry(name, 0)
            tar.finish()
        }
        val entries = readEntries(out.toByteArray())
        assertEquals(listOf(name), entries.map { it.first.name })
        assertTrue(entries[0].first.isDirectory)
    }

    @Test
    fun `exactly 100 byte ascii name stays a plain ustar entry`() {
        val file = Files.createTempFile("tar-test-", ".bin")
        try {
            Files.write(file, "x".toByteArray())
            val name = "a".repeat(96) + ".txt"
            val out = ByteArrayOutputStream()
            TarOutputStream(out).use { tar ->
                tar.putFileEntry(name, file, 0)
                tar.finish()
            }
            val archive = out.toByteArray()
            assertEquals("no @LongLink header expected", 4 * 512, archive.size)
            val entries = readEntries(archive)
            assertEquals(listOf(name), entries.map { it.first.name })
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `short source is zero-padded and later entries stay readable`() {
        val fileB = Files.createTempFile("tar-test-", ".bin")
        try {
            Files.write(fileB, "survivor".toByteArray())
            val out = ByteArrayOutputStream()
            TarOutputStream(out).use { tar ->
                val partial = ByteArray(400) { 7 }
                try {
                    tar.putFileEntry("truncated.bin", ByteArrayInputStream(partial), 1000, 0, "truncated.bin")
                    fail("Expected IOException for a source shorter than its declared size")
                } catch (_: IOException) {
                }
                tar.putFileEntry("b.txt", fileB, 0)
                tar.finish()
            }

            val entries = readEntries(out.toByteArray())
            assertEquals(listOf("truncated.bin", "b.txt"), entries.map { it.first.name })
            assertEquals(1000, entries[0].second.size)
            assertTrue(entries[0].second.take(400).all { it == 7.toByte() })
            assertTrue(entries[0].second.drop(400).all { it == 0.toByte() })
            assertEquals("survivor", entries[1].second.toString(Charsets.UTF_8))
        } finally {
            Files.deleteIfExists(fileB)
        }
    }

    @Test
    fun `unreadable file writes nothing and later entries stay readable`() {
        val fileB = Files.createTempFile("tar-test-", ".bin")
        try {
            Files.write(fileB, "survivor".toByteArray())
            val out = ByteArrayOutputStream()
            TarOutputStream(out).use { tar ->
                try {
                    tar.putFileEntry("missing.bin", Path.of("definitely-missing-", "no-such-file.bin"), 0)
                    fail("Expected an exception for a nonexistent source file")
                } catch (_: Exception) {
                }
                assertEquals(0, out.size())
                tar.putFileEntry("b.txt", fileB, 0)
                tar.finish()
            }

            val entries = readEntries(out.toByteArray())
            assertEquals(listOf("b.txt"), entries.map { it.first.name })
            assertEquals("survivor", entries[0].second.toString(Charsets.UTF_8))
        } finally {
            Files.deleteIfExists(fileB)
        }
    }
}
