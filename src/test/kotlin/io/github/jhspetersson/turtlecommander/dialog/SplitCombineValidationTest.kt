package io.github.jhspetersson.turtlecommander.dialog

import io.github.jhspetersson.turtlecommander.operation.SplitFileOperation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SplitCombineValidationTest {

    private val base: Path = Files.createTempDirectory("split-combine-validation-").toAbsolutePath()

    @After
    fun tearDown() {
        base.toFile().deleteRecursively()
    }

    @Test
    fun targetDirectoryRejectsBlankAndInvalidInput() {
        assertNotNull(validateTargetDirectory("  ", base))
        assertNotNull(validateTargetDirectory("bad\u0000dir", base))
    }

    @Test
    fun targetDirectoryRejectsAnExistingFile() {
        val file = Files.writeString(base.resolve("file.txt"), "x")

        assertNotNull(validateTargetDirectory(file.toString(), base))
        assertNotNull(validateTargetDirectory("file.txt", base))
    }

    @Test
    fun targetDirectoryAcceptsExistingAndNewDirectories() {
        assertNull(validateTargetDirectory(base.toString(), base))
        assertNull(validateTargetDirectory("new/sub", base))
    }

    @Test
    fun targetFileNameRejectsPathsAndInvalidNames() {
        assertNotNull(validateTargetFileName(" "))
        assertNotNull(validateTargetFileName("sub/out.bin"))
        assertNotNull(validateTargetFileName("sub\\out.bin"))
        assertNotNull(validateTargetFileName(".."))
        assertNotNull(validateTargetFileName("bad\u0000name"))
    }

    @Test
    fun targetFileNameAcceptsAPlainName() {
        assertNull(validateTargetFileName("out.bin"))
    }

    @Test
    fun chunkSizeRejectsNonPositiveAndTooManyParts() {
        assertNotNull(validateChunkSize(0, 100))
        assertNotNull(validateChunkSize(1, SplitFileOperation.MAX_CHUNKS + 1L))
        assertNotNull(validateChunkSize(1, Long.MAX_VALUE))
    }

    @Test
    fun chunkSizeAcceptsUpToTheLimit() {
        assertNull(validateChunkSize(1, SplitFileOperation.MAX_CHUNKS.toLong()))
        assertNull(validateChunkSize(1024, 0))
        assertNull(validateChunkSize(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun chunkCountDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE, SplitFileOperation.chunkCount(Long.MAX_VALUE, 1))
        assertEquals(3, SplitFileOperation.chunkCount(5, 2))
        assertEquals(1, SplitFileOperation.chunkCount(0, 2))
    }

    @Test
    fun splitRefusesAChunkSizeThatNeedsTooManyParts() {
        val source = base.resolve("big.bin")
        Files.write(source, ByteArray(SplitFileOperation.MAX_CHUNKS + 1))

        assertThrows(IllegalArgumentException::class.java) {
            SplitFileOperation.split(source, base.resolve("out"), 1, { _, _, _, _ -> }, { false })
        }
    }
}
