package io.github.jhspetersson.turtlecommander

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
    fun `split empty file creates CRC file but no chunks`() {
        val source = createTempFile(ByteArray(0))
        val targetDir = createTempDir()

        SplitFileOperation.split(source, targetDir, 100, { _, _, _, _ -> }, { false })

        // The while loop doesn't execute for 0-byte files, so no chunk files are created
        assertFalse(Files.exists(targetDir.resolve("${source.fileName}.001")))
        // But CRC file is still written
        assertTrue(Files.exists(targetDir.resolve("${source.fileName}.crc")))
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
}
