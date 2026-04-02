package io.github.jhspetersson.turtlecommander.operation

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CombineBufferReuseTest {

    @Test
    fun `combine produces correct output with multiple small chunks`() {
        val tmpDir = Files.createTempDirectory("combine-test-")
        val chunks = (1..10).map { i ->
            val chunk = tmpDir.resolve("file.${"%03d".format(i)}")
            Files.write(chunk, "chunk$i-".toByteArray())
            chunk
        }
        val target = tmpDir.resolve("combined.bin")

        CombineFilesOperation.combine(
            chunkFiles = chunks,
            targetFile = target,
            expectedSize = null,
            expectedCrc32 = null,
            onProgress = { _, _, _, _ -> },
            isCancelled = { false },
        )

        val result = Files.readString(target)
        val expected = (1..10).joinToString("") { "chunk$it-" }
        assertEquals(expected, result)

        tmpDir.toFile().deleteRecursively()
    }
}
