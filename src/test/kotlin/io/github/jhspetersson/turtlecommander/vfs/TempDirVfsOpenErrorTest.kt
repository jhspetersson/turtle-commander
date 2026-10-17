package io.github.jhspetersson.turtlecommander.vfs

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TempDirVfsOpenErrorTest {

    private class ErrorVfs(override val archivePath: Path, private val seen: MutableList<Path>) :
        AbstractTempDirVirtualFileSystem("turtle-open-error-test-") {

        override val isReadOnly: Boolean get() = true

        init {
            openTempDir()
        }

        override fun extract(into: Path) {
            seen.add(into)
            throw OutOfMemoryError("Java heap space")
        }
    }

    private lateinit var archive: Path

    @After
    fun tearDown() {
        if (::archive.isInitialized) Files.deleteIfExists(archive)
    }

    @Test
    fun `an Error thrown by extract discards the claimed temp root`() {
        archive = Files.createTempFile("open-error-archive-", ".fake")
        val seen = mutableListOf<Path>()
        try {
            ErrorVfs(archive, seen)
            fail("Expected the Error to propagate")
        } catch (_: OutOfMemoryError) {
        }
        val dir = seen.single()
        assertFalse(TempRootLocks.isClaimed(dir))
        assertTrue(Files.notExists(dir))
    }
}
