package io.github.jhspetersson.turtlecommander.operation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class TarOutputStreamTest {

    private val tempFiles = mutableListOf<Path>()

    @After
    fun cleanup() {
        for (path in tempFiles.reversed()) {
            if (Files.isDirectory(path)) {
                Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            } else {
                Files.deleteIfExists(path)
            }
        }
    }

    @Test
    fun `directory entry creates 512-byte header`() {
        val baos = ByteArrayOutputStream()
        TarOutputStream(baos).use { tar ->
            tar.putDirectoryEntry("mydir/", System.currentTimeMillis())
            tar.finish()
        }
        val data = baos.toByteArray()
        // Header (512) + 2 end-of-archive blocks (1024)
        assertEquals(512 + 1024, data.size)
    }

    @Test
    fun `directory entry has type flag 5`() {
        val baos = ByteArrayOutputStream()
        TarOutputStream(baos).use { tar ->
            tar.putDirectoryEntry("testdir/", 0)
            tar.finish()
        }
        val data = baos.toByteArray()
        assertEquals('5'.code.toByte(), data[156])
    }

    @Test
    fun `file entry has type flag 0`() {
        val dir = Files.createTempDirectory("tar-test-")
        tempFiles.add(dir)
        val file = dir.resolve("test.txt")
        Files.writeString(file, "hello")

        val baos = ByteArrayOutputStream()
        TarOutputStream(baos).use { tar ->
            tar.putFileEntry("test.txt", file, 5, 0)
            tar.finish()
        }
        val data = baos.toByteArray()
        assertEquals('0'.code.toByte(), data[156])
    }

    @Test
    fun `file entry contains file data`() {
        val dir = Files.createTempDirectory("tar-test-")
        tempFiles.add(dir)
        val file = dir.resolve("test.txt")
        Files.writeString(file, "hello")

        val baos = ByteArrayOutputStream()
        TarOutputStream(baos).use { tar ->
            tar.putFileEntry("test.txt", file, 5, 0)
            tar.finish()
        }
        val data = baos.toByteArray()
        // File content starts at offset 512
        val content = String(data, 512, 5, StandardCharsets.UTF_8)
        assertEquals("hello", content)
    }

    @Test
    fun `file data is padded to 512 block boundary`() {
        val dir = Files.createTempDirectory("tar-test-")
        tempFiles.add(dir)
        val file = dir.resolve("test.txt")
        Files.writeString(file, "hello") // 5 bytes

        val baos = ByteArrayOutputStream()
        TarOutputStream(baos).use { tar ->
            tar.putFileEntry("test.txt", file, 5, 0)
            tar.finish()
        }
        val data = baos.toByteArray()
        // Header (512) + padded data (512) + end (1024)
        assertEquals(512 + 512 + 1024, data.size)
    }

    @Test
    fun `header contains ustar magic`() {
        val baos = ByteArrayOutputStream()
        TarOutputStream(baos).use { tar ->
            tar.putDirectoryEntry("dir/", 0)
            tar.finish()
        }
        val data = baos.toByteArray()
        val magic = String(data, 257, 5, StandardCharsets.UTF_8)
        assertEquals("ustar", magic)
    }

    @Test
    fun `header contains name`() {
        val baos = ByteArrayOutputStream()
        TarOutputStream(baos).use { tar ->
            tar.putDirectoryEntry("mydir/", 0)
            tar.finish()
        }
        val data = baos.toByteArray()
        val name = String(data, 0, 6, StandardCharsets.UTF_8)
        assertEquals("mydir/", name)
    }

    @Test
    fun `header checksum is valid`() {
        val baos = ByteArrayOutputStream()
        TarOutputStream(baos).use { tar ->
            tar.putDirectoryEntry("test/", 0)
            tar.finish()
        }
        val header = baos.toByteArray().copyOfRange(0, 512)

        // Calculate expected checksum: sum of all bytes, treating checksum field (148-155) as spaces
        var checksum = 0L
        for (i in header.indices) {
            checksum += if (i in 148..155) {
                ' '.code
            } else {
                (header[i].toInt() and 0xFF)
            }
        }

        // Parse stored checksum (octal at offset 148, 7 bytes)
        val checksumStr = String(header, 148, 7, StandardCharsets.UTF_8).trim().trimEnd('\u0000')
        val storedChecksum = checksumStr.toLong(8)

        assertEquals(checksum, storedChecksum)
    }

    @Test
    fun `long file name uses GNU LongLink extension`() {
        val dir = Files.createTempDirectory("tar-test-")
        tempFiles.add(dir)
        val file = dir.resolve("test.txt")
        Files.writeString(file, "data")

        val longName = "a".repeat(150) + ".txt"

        val baos = ByteArrayOutputStream()
        TarOutputStream(baos).use { tar ->
            tar.putFileEntry(longName, file, 4, 0)
            tar.finish()
        }
        val data = baos.toByteArray()

        // First header should be @LongLink
        val longLinkName = String(data, 0, 13, StandardCharsets.UTF_8)
        assertEquals("././@LongLink", longLinkName)
        assertEquals('L'.code.toByte(), data[156])
    }

    @Test
    fun `long name truncation uses first 100 chars not last`() {
        val dir = Files.createTempDirectory("tar-test-")
        tempFiles.add(dir)
        val file = dir.resolve("test.txt")
        Files.writeString(file, "data")

        // Name where first 100 and last 100 chars are different
        val prefix = "START_"
        val suffix = "_END.txt"
        val longName = prefix + "x".repeat(150 - prefix.length - suffix.length) + suffix

        val baos = ByteArrayOutputStream()
        TarOutputStream(baos).use { tar ->
            tar.putFileEntry(longName, file, 4, 0)
            tar.finish()
        }
        val data = baos.toByteArray()

        // Skip the @LongLink header (512) + padded name data (512) to reach the actual file header
        val actualHeaderOffset = 512 + 512
        val truncatedName = String(data, actualHeaderOffset, 6, StandardCharsets.UTF_8)
        assertEquals("START_", truncatedName)
    }

    @Test
    fun `long directory name truncation uses first 100 chars`() {
        val prefix = "START_"
        val longName = prefix + "d".repeat(100 - prefix.length) + "/"

        val baos = ByteArrayOutputStream()
        TarOutputStream(baos).use { tar ->
            tar.putDirectoryEntry(longName, 0)
            tar.finish()
        }
        val data = baos.toByteArray()

        // Skip the @LongLink header (512) + padded name data (512)
        val actualHeaderOffset = 512 + 512
        val truncatedName = String(data, actualHeaderOffset, 6, StandardCharsets.UTF_8)
        assertEquals("START_", truncatedName)
    }

    @Test
    fun `finish writes two zero blocks`() {
        val baos = ByteArrayOutputStream()
        TarOutputStream(baos).use { tar ->
            tar.finish()
        }
        val data = baos.toByteArray()
        assertEquals(1024, data.size)
        assertTrue(data.all { it == 0.toByte() })
    }

    @Test
    fun `multiple entries produce valid tar`() {
        val dir = Files.createTempDirectory("tar-test-")
        tempFiles.add(dir)
        val file1 = dir.resolve("a.txt")
        Files.writeString(file1, "aaa")
        val file2 = dir.resolve("b.txt")
        Files.writeString(file2, "bbb")

        val baos = ByteArrayOutputStream()
        TarOutputStream(baos).use { tar ->
            tar.putDirectoryEntry("mydir/", 0)
            tar.putFileEntry("mydir/a.txt", file1, 3, 0)
            tar.putFileEntry("mydir/b.txt", file2, 3, 0)
            tar.finish()
        }
        val data = baos.toByteArray()
        // dir header (512) + file1 header+data (512+512) + file2 header+data (512+512) + end (1024) = 3584
        assertEquals(3584, data.size)
    }
}
