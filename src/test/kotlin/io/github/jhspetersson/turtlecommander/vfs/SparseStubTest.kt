package io.github.jhspetersson.turtlecommander.vfs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

class SparseStubTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("sparse-stub-")
    }

    @After
    fun tearDown() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `large stub reports its logical size without allocating it on disk`() {
        val stub = dir.resolve("stub.bin")
        val before = Files.getFileStore(dir).usableSpace

        createSparseStub(stub, STUB_SIZE)

        val allocated = before - Files.getFileStore(dir).usableSpace
        assertEquals(STUB_SIZE, Files.size(stub))
        assertTrue("stub allocated $allocated bytes for a $STUB_SIZE byte hole", allocated < STUB_SIZE / 4)
    }

    @Test
    fun `zero size stub is an empty file`() {
        val stub = dir.resolve("empty.bin")

        createSparseStub(stub, 0)

        assertTrue(Files.isRegularFile(stub))
        assertEquals(0L, Files.size(stub))
    }

    @Test
    fun `stub never replaces an existing file`() {
        val existing = Files.writeString(dir.resolve("existing.bin"), "keep")

        try {
            createSparseStub(existing, 1024)
            fail("expected FileAlreadyExistsException")
        } catch (_: FileAlreadyExistsException) {
        }

        assertEquals("keep", Files.readString(existing))
    }

    private companion object {
        const val STUB_SIZE = 256L * 1024 * 1024
    }
}
