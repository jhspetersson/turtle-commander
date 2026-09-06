package io.github.jhspetersson.turtlecommander.operation

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

class CombineFilesOperationTest {

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

    private fun createTempDir(): Path {
        val dir = Files.createTempDirectory("combine-test-")
        tempFiles.add(dir)
        return dir
    }

    // --- parseCrcFile ---

    @Test
    fun `parseCrcFile reads valid CRC file`() {
        val dir = createTempDir()
        val crcFile = dir.resolve("test.dat.crc")
        Files.writeString(crcFile, "filename=test.dat\nsize=1024\ncrc32=ABCD1234\n")

        val info = CombineFilesOperation.parseCrcFile(crcFile)

        assertEquals("test.dat", info.filename)
        assertEquals(1024L, info.size)
        assertEquals(0xABCD1234L, info.crc32)
    }

    @Test
    fun `parseCrcFile trims whitespace around values`() {
        val dir = createTempDir()
        val crcFile = dir.resolve("test.crc")
        Files.writeString(crcFile, "filename = myfile.bin \n size = 500 \n crc32 = 00FF00FF \n")

        val info = CombineFilesOperation.parseCrcFile(crcFile)

        assertEquals("myfile.bin", info.filename)
        assertEquals(500L, info.size)
        assertEquals(0x00FF00FFL, info.crc32)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseCrcFile throws on missing filename`() {
        val dir = createTempDir()
        val crcFile = dir.resolve("test.crc")
        Files.writeString(crcFile, "size=100\ncrc32=00000000\n")

        CombineFilesOperation.parseCrcFile(crcFile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseCrcFile throws on missing size`() {
        val dir = createTempDir()
        val crcFile = dir.resolve("test.crc")
        Files.writeString(crcFile, "filename=test.dat\ncrc32=00000000\n")

        CombineFilesOperation.parseCrcFile(crcFile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseCrcFile throws on missing crc32`() {
        val dir = createTempDir()
        val crcFile = dir.resolve("test.crc")
        Files.writeString(crcFile, "filename=test.dat\nsize=100\n")

        CombineFilesOperation.parseCrcFile(crcFile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseCrcFile throws on invalid size`() {
        val dir = createTempDir()
        val crcFile = dir.resolve("test.crc")
        Files.writeString(crcFile, "filename=test.dat\nsize=abc\ncrc32=00000000\n")

        CombineFilesOperation.parseCrcFile(crcFile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseCrcFile rejects crc32 hex string longer than 8 digits`() {
        val dir = createTempDir()
        val crcFile = dir.resolve("test.crc")
        Files.writeString(crcFile, "filename=test.dat\nsize=100\ncrc32=1ABCD12345\n")

        CombineFilesOperation.parseCrcFile(crcFile)
    }

    // --- resolveBaseFileName ---

    @Test
    fun `resolveBaseFileName from crc file`() {
        assertEquals("video.mp4", CombineFilesOperation.resolveBaseFileName(Path.of("/dir/video.mp4.crc")))
    }

    @Test
    fun `resolveBaseFileName from numeric chunk`() {
        assertEquals("video.mp4", CombineFilesOperation.resolveBaseFileName(Path.of("/dir/video.mp4.001")))
    }

    @Test
    fun `resolveBaseFileName from 4-digit chunk`() {
        assertEquals("data.bin", CombineFilesOperation.resolveBaseFileName(Path.of("/dir/data.bin.0042")))
    }

    @Test
    fun `resolveBaseFileName returns null for non-chunk file`() {
        assertNull(CombineFilesOperation.resolveBaseFileName(Path.of("/dir/readme.txt")))
    }

    @Test
    fun `resolveBaseFileName returns null for extensionless file`() {
        // No dot in the file name → not a chunk. Without an explicit ext-non-empty guard
        // the vacuous-truth bug made `ext.all { isDigit }` return true for "" and
        // misidentified `Makefile` / `123` as chunks.
        assertNull(CombineFilesOperation.resolveBaseFileName(Path.of("/dir/Makefile")))
        assertNull(CombineFilesOperation.resolveBaseFileName(Path.of("/dir/123")))
    }

    // --- findChunkFiles ---

    @Test
    fun `findChunkFiles discovers chunks in order`() {
        val dir = createTempDir()
        Files.write(dir.resolve("data.bin.001"), ByteArray(10))
        Files.write(dir.resolve("data.bin.002"), ByteArray(10))
        Files.write(dir.resolve("data.bin.003"), ByteArray(10))

        val chunks = CombineFilesOperation.findChunkFiles(dir, "data.bin")

        assertEquals(3, chunks.size)
        assertEquals("data.bin.001", chunks[0].fileName.toString())
        assertEquals("data.bin.002", chunks[1].fileName.toString())
        assertEquals("data.bin.003", chunks[2].fileName.toString())
    }

    @Test
    fun `findChunkFiles stops at gap`() {
        val dir = createTempDir()
        Files.write(dir.resolve("data.bin.001"), ByteArray(10))
        Files.write(dir.resolve("data.bin.003"), ByteArray(10)) // gap at 002

        val chunks = CombineFilesOperation.findChunkFiles(dir, "data.bin")

        assertEquals(1, chunks.size)
    }

    @Test
    fun `findChunkFiles returns empty when no chunks exist`() {
        val dir = createTempDir()

        val chunks = CombineFilesOperation.findChunkFiles(dir, "data.bin")

        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `findChunkFiles discovers 4-digit chunks`() {
        val dir = createTempDir()
        Files.write(dir.resolve("data.bin.0001"), ByteArray(10))
        Files.write(dir.resolve("data.bin.0002"), ByteArray(10))

        val chunks = CombineFilesOperation.findChunkFiles(dir, "data.bin")

        assertEquals(2, chunks.size)
    }

    // --- combine ---

    @Test
    fun `combine reassembles chunks correctly`() {
        val dir = createTempDir()
        Files.write(dir.resolve("file.dat.001"), byteArrayOf(1, 2, 3))
        Files.write(dir.resolve("file.dat.002"), byteArrayOf(4, 5, 6))
        Files.write(dir.resolve("file.dat.003"), byteArrayOf(7, 8))

        val chunks = CombineFilesOperation.findChunkFiles(dir, "file.dat")
        val target = dir.resolve("file.dat")

        CombineFilesOperation.combine(chunks, target, null, null, { _, _, _, _ -> }, { false })

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), Files.readAllBytes(target))
    }

    @Test
    fun `combine validates size`() {
        val dir = createTempDir()
        Files.write(dir.resolve("file.dat.001"), byteArrayOf(1, 2, 3))

        val chunks = CombineFilesOperation.findChunkFiles(dir, "file.dat")
        val target = dir.resolve("file.dat")

        try {
            CombineFilesOperation.combine(chunks, target, 999L, null, { _, _, _, _ -> }, { false })
            fail("Expected exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Size mismatch"))
            assertFalse(Files.exists(target))
        }
    }

    @Test
    fun `combine validates CRC`() {
        val dir = createTempDir()
        Files.write(dir.resolve("file.dat.001"), byteArrayOf(1, 2, 3))

        val chunks = CombineFilesOperation.findChunkFiles(dir, "file.dat")
        val target = dir.resolve("file.dat")

        try {
            CombineFilesOperation.combine(chunks, target, 3L, 0xDEADBEEFL, { _, _, _, _ -> }, { false })
            fail("Expected exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("CRC mismatch"))
            assertFalse(Files.exists(target))
        }
    }

    @Test
    fun `combine reports progress`() {
        val dir = createTempDir()
        Files.write(dir.resolve("file.dat.001"), ByteArray(100))
        Files.write(dir.resolve("file.dat.002"), ByteArray(100))

        val chunks = CombineFilesOperation.findChunkFiles(dir, "file.dat")
        val target = dir.resolve("file.dat")
        val progressCalls = mutableListOf<Pair<Int, Int>>()

        CombineFilesOperation.combine(chunks, target, null, null, { chunkIndex, totalChunks, _, _ ->
            progressCalls.add(chunkIndex to totalChunks)
        }, { false })

        assertTrue(progressCalls.isNotEmpty())
        assertTrue(progressCalls.all { it.second == 2 })
        assertTrue(progressCalls.any { it.first == 1 })
        assertTrue(progressCalls.any { it.first == 2 })
    }

    @Test
    fun `combine can be cancelled and cleans up partial file`() {
        val dir = createTempDir()
        for (i in 1..10) {
            Files.write(dir.resolve("file.dat.${i.toString().padStart(3, '0')}"), ByteArray(100))
        }

        val chunks = CombineFilesOperation.findChunkFiles(dir, "file.dat")
        val target = dir.resolve("file.dat")
        var chunksSeen = 0

        CombineFilesOperation.combine(chunks, target, null, null, { chunkIndex, _, _, _ ->
            chunksSeen = chunkIndex
        }, { chunksSeen >= 2 })

        // Partial file should be deleted on cancellation
        assertFalse("Partial file should be deleted on cancel", Files.exists(target))
    }

    @Test
    fun `combine cancellation does not process extra chunks after cancel`() {
        val dir = createTempDir()
        for (i in 1..10) {
            Files.write(dir.resolve("file.dat.${i.toString().padStart(3, '0')}"), ByteArray(100))
        }

        val chunks = CombineFilesOperation.findChunkFiles(dir, "file.dat")
        val target = dir.resolve("file.dat")
        var maxChunkSeen = 0
        val cancelAfterChunk = 3

        CombineFilesOperation.combine(chunks, target, null, null, { chunkIndex, _, _, _ ->
            maxChunkSeen = chunkIndex
        }, { maxChunkSeen >= cancelAfterChunk })

        // Should stop at or very near the cancel point, not process further chunks
        assertTrue("Should not process chunks far beyond cancel point, but saw $maxChunkSeen",
            maxChunkSeen <= cancelAfterChunk + 1)
    }

    @Test
    fun `combine passes CRC validation with correct value`() {
        val dir = createTempDir()
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val crc = CRC32()
        crc.update(data)

        Files.write(dir.resolve("file.dat.001"), data.sliceArray(0..2))
        Files.write(dir.resolve("file.dat.002"), data.sliceArray(3..4))

        val chunks = CombineFilesOperation.findChunkFiles(dir, "file.dat")
        val target = dir.resolve("file.dat")

        CombineFilesOperation.combine(chunks, target, data.size.toLong(), crc.value, { _, _, _, _ -> }, { false })

        assertTrue(Files.exists(target))
        assertArrayEquals(data, Files.readAllBytes(target))
    }

    @Test
    fun `combine leaves no partial or temp file when a chunk read fails`() {
        val dir = createTempDir()
        Files.write(dir.resolve("file.dat.001"), byteArrayOf(1, 2, 3))
        val chunks = listOf(dir.resolve("file.dat.001"), dir.resolve("file.dat.002"))
        val target = dir.resolve("file.dat")

        try {
            CombineFilesOperation.combine(chunks, target, 6L, null, { _, _, _, _ -> }, { false })
            fail("Expected exception")
        } catch (e: java.io.IOException) {
            assertFalse(Files.exists(target))
            assertEquals(listOf("file.dat.001"), Files.list(dir).use { it.map { p -> p.fileName.toString() }.toList() })
        }
    }

    @Test
    fun `combine keeps existing target intact when verification fails`() {
        val dir = createTempDir()
        Files.write(dir.resolve("file.dat.001"), byteArrayOf(1, 2, 3))
        val chunks = CombineFilesOperation.findChunkFiles(dir, "file.dat")
        val target = dir.resolve("file.dat")
        Files.write(target, byteArrayOf(9, 9, 9, 9))

        try {
            CombineFilesOperation.combine(chunks, target, 3L, 0xDEADBEEFL, { _, _, _, _ -> }, { false })
            fail("Expected exception")
        } catch (e: IllegalStateException) {
            assertArrayEquals(byteArrayOf(9, 9, 9, 9), Files.readAllBytes(target))
        }
    }

    @Test
    fun `combine replaces existing target on success`() {
        val dir = createTempDir()
        Files.write(dir.resolve("file.dat.001"), byteArrayOf(1, 2, 3))
        val chunks = CombineFilesOperation.findChunkFiles(dir, "file.dat")
        val target = dir.resolve("file.dat")
        Files.write(target, byteArrayOf(9, 9, 9, 9))

        CombineFilesOperation.combine(chunks, target, null, null, { _, _, _, _ -> }, { false })

        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(target))
        assertEquals(2, Files.list(dir).use { it.count() })
    }
}
