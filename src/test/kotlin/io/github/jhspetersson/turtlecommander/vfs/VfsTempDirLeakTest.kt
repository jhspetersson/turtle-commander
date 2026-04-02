package io.github.jhspetersson.turtlecommander.vfs

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.nio.file.Files

class VfsTempDirLeakTest {

    @Test
    fun `tar vfs cleans up temp dir when input stream throws`() {
        val fakePath = Files.createTempFile("fake-archive-", ".tar")
        Files.write(fakePath, byteArrayOf(0, 1, 2)) // corrupt content

        val tempDirsBefore = listTurtleTempDirs("turtle-tar-")

        try {
            TarVirtualFileSystem(
                fakePath,
                inputStreamFactory = { throw java.io.IOException("simulated stream failure") },
            )
            fail("Expected exception")
        } catch (_: java.io.IOException) {
            // expected
        }

        val tempDirsAfter = listTurtleTempDirs("turtle-tar-")
        assertEquals(
            "Temp directory should be cleaned up on extraction failure",
            tempDirsBefore.size, tempDirsAfter.size,
        )

        Files.deleteIfExists(fakePath)
    }

    @Test
    fun `compressed single file vfs cleans up temp dir when decompressor throws`() {
        val fakePath = Files.createTempFile("fake-archive-", ".gz")
        Files.write(fakePath, byteArrayOf(0, 1, 2))

        val tempDirsBefore = listTurtleTempDirs("turtle-decompress-")

        try {
            CompressedSingleFileVirtualFileSystem(
                fakePath, ".gz"
            ) { _: InputStream -> throw java.io.IOException("simulated decompress failure") }
            fail("Expected exception")
        } catch (_: java.io.IOException) {
            // expected
        }

        val tempDirsAfter = listTurtleTempDirs("turtle-decompress-")
        assertEquals(
            "Temp directory should be cleaned up on extraction failure",
            tempDirsBefore.size, tempDirsAfter.size,
        )

        Files.deleteIfExists(fakePath)
    }

    private fun listTurtleTempDirs(prefix: String): List<java.nio.file.Path> {
        val tmpDir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
        return Files.list(tmpDir).use { stream ->
            stream.filter { Files.isDirectory(it) && it.fileName.toString().startsWith(prefix) }
                .toList()
        }
    }
}
