package io.github.jhspetersson.turtlecommander.operation

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SplitFileOperationTest {

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

    private fun createTempFile(content: ByteArray): Path {
        val file = Files.createTempFile("split-test-", ".dat")
        Files.write(file, content)
        tempFiles.add(file)
        return file
    }

    private fun createTempDir(): Path {
        val dir = Files.createTempDirectory("split-test-out-")
        tempFiles.add(dir)
        return dir
    }

    @Test
    fun `split file into multiple chunks`() {
        val data = ByteArray(1000) { it.toByte() }
        val source = createTempFile(data)
        val targetDir = createTempDir()

        SplitFileOperation.split(source, targetDir, 400, { _, _, _, _ -> }, { false })

        assertTrue(Files.exists(targetDir.resolve("${source.fileName}.001")))
        assertTrue(Files.exists(targetDir.resolve("${source.fileName}.002")))
        assertTrue(Files.exists(targetDir.resolve("${source.fileName}.003")))
        assertFalse(Files.exists(targetDir.resolve("${source.fileName}.004")))

        assertEquals(400, Files.size(targetDir.resolve("${source.fileName}.001")))
        assertEquals(400, Files.size(targetDir.resolve("${source.fileName}.002")))
        assertEquals(200, Files.size(targetDir.resolve("${source.fileName}.003")))
    }

    @Test
    fun `split creates CRC file`() {
        val data = ByteArray(100) { it.toByte() }
        val source = createTempFile(data)
        val targetDir = createTempDir()

        SplitFileOperation.split(source, targetDir, 50, { _, _, _, _ -> }, { false })

        val crcFile = targetDir.resolve("${source.fileName}.crc")
        assertTrue(Files.exists(crcFile))

        val crcContent = Files.readString(crcFile)
        assertTrue(crcContent.contains("filename=${source.fileName}"))
        assertTrue(crcContent.contains("size=100"))
        assertTrue(crcContent.contains("crc32="))
    }

    @Test
    fun `split file smaller than chunk size creates single chunk`() {
        val data = ByteArray(50) { it.toByte() }
        val source = createTempFile(data)
        val targetDir = createTempDir()

        SplitFileOperation.split(source, targetDir, 1000, { _, _, _, _ -> }, { false })

        assertTrue(Files.exists(targetDir.resolve("${source.fileName}.001")))
        assertFalse(Files.exists(targetDir.resolve("${source.fileName}.002")))
        assertEquals(50, Files.size(targetDir.resolve("${source.fileName}.001")))
    }

    @Test
    fun `split file exactly matching chunk size`() {
        val data = ByteArray(400) { it.toByte() }
        val source = createTempFile(data)
        val targetDir = createTempDir()

        SplitFileOperation.split(source, targetDir, 200, { _, _, _, _ -> }, { false })

        assertTrue(Files.exists(targetDir.resolve("${source.fileName}.001")))
        assertTrue(Files.exists(targetDir.resolve("${source.fileName}.002")))
        assertFalse(Files.exists(targetDir.resolve("${source.fileName}.003")))
        assertEquals(200, Files.size(targetDir.resolve("${source.fileName}.001")))
        assertEquals(200, Files.size(targetDir.resolve("${source.fileName}.002")))
    }

    @Test
    fun `split empty file progress does not produce NaN`() {
        val source = createTempFile(ByteArray(0))
        val targetDir = createTempDir()
        val fractions = mutableListOf<Double>()

        SplitFileOperation.split(source, targetDir, 100, { _, _, bytesWritten, totalBytes ->
            val fraction = if (totalBytes > 0) bytesWritten.toDouble() / totalBytes else 1.0
            fractions.add(fraction)
        }, { false })

        assertTrue("Should have progress callbacks", fractions.isNotEmpty())
        for (f in fractions) {
            assertFalse("Fraction should not be NaN", f.isNaN())
            assertFalse("Fraction should not be infinite", f.isInfinite())
        }
    }

    @Test
    fun `split empty file creates one empty chunk and CRC file`() {
        val source = createTempFile(ByteArray(0))
        val targetDir = createTempDir()

        SplitFileOperation.split(source, targetDir, 100, { _, _, _, _ -> }, { false })

        // Zero-byte file should still produce a single empty chunk
        assertTrue(Files.exists(targetDir.resolve("${source.fileName}.001")))
        assertEquals(0, Files.size(targetDir.resolve("${source.fileName}.001")))
        assertFalse(Files.exists(targetDir.resolve("${source.fileName}.002")))
        // CRC file is still written
        assertTrue(Files.exists(targetDir.resolve("${source.fileName}.crc")))
    }

    @Test
    fun `split and combine empty file round-trip`() {
        val source = createTempFile(ByteArray(0))
        val targetDir = createTempDir()

        SplitFileOperation.split(source, targetDir, 100, { _, _, _, _ -> }, { false })

        val crcInfo = CombineFilesOperation.parseCrcFile(targetDir.resolve("${source.fileName}.crc"))
        val chunks = CombineFilesOperation.findChunkFiles(targetDir, crcInfo.filename)
        assertEquals(1, chunks.size)
        val combined = targetDir.resolve(crcInfo.filename)

        CombineFilesOperation.combine(chunks, combined, crcInfo.size, crcInfo.crc32, { _, _, _, _ -> }, { false })

        assertArrayEquals(ByteArray(0), Files.readAllBytes(combined))
    }

    @Test
    fun `split reports progress`() {
        val data = ByteArray(300) { it.toByte() }
        val source = createTempFile(data)
        val targetDir = createTempDir()
        val progressCalls = mutableListOf<Pair<Int, Int>>()

        SplitFileOperation.split(source, targetDir, 100, { chunkIndex, totalChunks, _, _ ->
            progressCalls.add(chunkIndex to totalChunks)
        }, { false })

        assertTrue(progressCalls.isNotEmpty())
        assertTrue(progressCalls.all { it.second == 3 })
        assertTrue(progressCalls.any { it.first == 1 })
        assertTrue(progressCalls.any { it.first == 2 })
        assertTrue(progressCalls.any { it.first == 3 })
    }

    @Test
    fun `split can be cancelled`() {
        val data = ByteArray(10000) { it.toByte() }
        val source = createTempFile(data)
        val targetDir = createTempDir()
        var chunksSeen = 0

        SplitFileOperation.split(source, targetDir, 100, { chunkIndex, _, _, _ ->
            chunksSeen = chunkIndex
        }, { chunksSeen >= 2 })

        // Should have stopped before writing all 100 chunks
        assertTrue(chunksSeen < 100)
    }

    @Test
    fun `split cancellation cleans up partial chunk files`() {
        val data = ByteArray(10000) { it.toByte() }
        val source = createTempFile(data)
        val targetDir = createTempDir()
        var chunksSeen = 0

        SplitFileOperation.split(source, targetDir, 100, { chunkIndex, _, _, _ ->
            chunksSeen = chunkIndex
        }, { chunksSeen >= 2 })

        // All chunk files and CRC file should be cleaned up on cancellation
        val remainingFiles = Files.list(targetDir).use { it.toList() }
        assertEquals("Cancelled split should clean up partial files", 0, remainingFiles.size)
    }

    @Test
    fun `split uses 4-digit extensions for more than 999 chunks`() {
        val data = ByteArray(1001) { it.toByte() }
        val source = createTempFile(data)
        val targetDir = createTempDir()

        SplitFileOperation.split(source, targetDir, 1, { _, _, _, _ -> }, { false })

        assertTrue(Files.exists(targetDir.resolve("${source.fileName}.0001")))
        assertTrue(Files.exists(targetDir.resolve("${source.fileName}.1001")))
    }

    @Test
    fun `split and combine round-trip preserves data`() {
        val data = ByteArray(2500) { (it * 37).toByte() }
        val source = createTempFile(data)
        val targetDir = createTempDir()

        SplitFileOperation.split(source, targetDir, 800, { _, _, _, _ -> }, { false })

        val crcInfo = CombineFilesOperation.parseCrcFile(targetDir.resolve("${source.fileName}.crc"))
        val chunks = CombineFilesOperation.findChunkFiles(targetDir, crcInfo.filename)
        val combined = targetDir.resolve(crcInfo.filename)

        CombineFilesOperation.combine(chunks, combined, crcInfo.size, crcInfo.crc32, { _, _, _, _ -> }, { false })

        assertArrayEquals(data, Files.readAllBytes(combined))
    }

    @Test
    fun `cancelled split preserves a pre-existing chunk set`() {
        val data = ByteArray(10000) { it.toByte() }
        val source = createTempFile(data)
        val targetDir = createTempDir()
        val name = source.fileName.toString()
        val old1 = "old chunk one".toByteArray()
        val old2 = "old chunk two".toByteArray()
        val oldCrc = "filename=$name\nsize=26\ncrc32=DEADBEEF\n".toByteArray()
        Files.write(targetDir.resolve("$name.001"), old1)
        Files.write(targetDir.resolve("$name.002"), old2)
        Files.write(targetDir.resolve("$name.crc"), oldCrc)
        var chunksSeen = 0

        SplitFileOperation.split(source, targetDir, 100, { chunkIndex, _, _, _ ->
            chunksSeen = chunkIndex
        }, { chunksSeen >= 3 })

        assertArrayEquals(old1, Files.readAllBytes(targetDir.resolve("$name.001")))
        assertArrayEquals(old2, Files.readAllBytes(targetDir.resolve("$name.002")))
        assertArrayEquals(oldCrc, Files.readAllBytes(targetDir.resolve("$name.crc")))
        val names = Files.list(targetDir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(listOf("$name.001", "$name.002", "$name.crc"), names)
    }

    @Test
    fun `successful split replaces a pre-existing chunk set`() {
        val data = ByteArray(1000) { it.toByte() }
        val source = createTempFile(data)
        val targetDir = createTempDir()
        val name = source.fileName.toString()
        Files.write(targetDir.resolve("$name.001"), "stale".toByteArray())
        Files.write(targetDir.resolve("$name.crc"), "stale".toByteArray())

        SplitFileOperation.split(source, targetDir, 400, { _, _, _, _ -> }, { false })

        assertArrayEquals(data.copyOfRange(0, 400), Files.readAllBytes(targetDir.resolve("$name.001")))
        assertArrayEquals(data.copyOfRange(400, 800), Files.readAllBytes(targetDir.resolve("$name.002")))
        assertArrayEquals(data.copyOfRange(800, 1000), Files.readAllBytes(targetDir.resolve("$name.003")))
        val crc = CombineFilesOperation.parseCrcFile(targetDir.resolve("$name.crc"))
        assertEquals(1000L, crc.size)
        val names = Files.list(targetDir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(listOf("$name.001", "$name.002", "$name.003", "$name.crc"), names)
    }

    @Test
    fun `successful split removes stale higher-numbered chunks of a previous larger set`() {
        val data = ByteArray(1000) { it.toByte() }
        val source = createTempFile(data)
        val targetDir = createTempDir()
        val name = source.fileName.toString()
        for (i in 1..10) {
            Files.write(targetDir.resolve("$name.${i.toString().padStart(3, '0')}"), "stale $i".toByteArray())
        }
        Files.write(targetDir.resolve("$name.1000"), "stale wide".toByteArray())
        Files.write(targetDir.resolve("$name.txt"), "unrelated".toByteArray())
        Files.write(targetDir.resolve("other.004"), "unrelated".toByteArray())

        SplitFileOperation.split(source, targetDir, 400, { _, _, _, _ -> }, { false })

        val names = Files.list(targetDir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(
            listOf("$name.001", "$name.002", "$name.003", "$name.crc", "$name.txt", "other.004").sorted(),
            names,
        )
        assertEquals(3, CombineFilesOperation.findChunkFiles(targetDir, name).size)
    }

    @Test
    fun `re-split of an empty file over an existing chunk succeeds`() {
        val source = createTempFile(ByteArray(0))
        val targetDir = createTempDir()
        val name = source.fileName.toString()
        Files.write(targetDir.resolve("$name.001"), "stale".toByteArray())

        SplitFileOperation.split(source, targetDir, 100, { _, _, _, _ -> }, { false })

        assertEquals(0L, Files.size(targetDir.resolve("$name.001")))
        assertTrue(Files.exists(targetDir.resolve("$name.crc")))
    }

    @Test
    fun `existingChunkFiles lists numbered chunks and crc of the base name only`() {
        val targetDir = createTempDir()
        val name = "archive.bin"
        Files.write(targetDir.resolve("$name.001"), byteArrayOf(1))
        Files.write(targetDir.resolve("$name.0002"), byteArrayOf(2))
        Files.write(targetDir.resolve("$name.crc"), byteArrayOf(3))
        Files.write(targetDir.resolve("$name.001.part"), byteArrayOf(4))
        Files.write(targetDir.resolve("$name.txt"), byteArrayOf(5))
        Files.write(targetDir.resolve("$name.1"), byteArrayOf(6))
        Files.write(targetDir.resolve("other.001"), byteArrayOf(7))
        Files.write(targetDir.resolve(name), byteArrayOf(8))

        val found = SplitFileOperation.existingChunkFiles(targetDir, name).map { it.fileName.toString() }.sorted()

        assertEquals(listOf("$name.0002", "$name.001", "$name.crc").sorted(), found)
    }

    @Test
    fun `existingChunkFiles is empty for a missing directory`() {
        val targetDir = createTempDir().resolve("missing")

        assertTrue(SplitFileOperation.existingChunkFiles(targetDir, "x").isEmpty())
    }
}
