package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Dirty tracking in [AbstractTempDirVirtualFileSystem]: [VirtualFileSystem.flush] must
 * repack only when the temp dir actually changed since extraction. Read-only operations
 * (copying a file out of the archive, a plain refresh) used to rewrite — and re-compress —
 * the archive on every flush.
 */
class TempDirVfsDirtyTrackingTest {

    private class CountingVfs(override val archivePath: Path) :
        AbstractTempDirVirtualFileSystem("turtle-dirty-test-") {

        var repackCount = 0

        init {
            openTempDir()
        }

        override fun extract(into: Path) {
            Files.writeString(into.resolve("a.txt"), "alpha")
            Files.createDirectories(into.resolve("sub"))
            Files.writeString(into.resolve("sub/b.txt"), "beta")
        }

        override fun repack(from: Path) {
            repackCount++
        }
    }

    private lateinit var archive: Path
    private var vfs: CountingVfs? = null

    private fun newVfs(): CountingVfs {
        archive = Files.createTempFile("dirty-test-archive-", ".fake")
        return CountingVfs(archive).also { vfs = it }
    }

    @After
    fun tearDown() {
        vfs?.close()
        if (::archive.isInitialized) Files.deleteIfExists(archive)
    }

    @Test
    fun `flush without changes does not repack`() {
        val v = newVfs()
        v.flush()
        v.flush()
        assertEquals("clean flushes must not rewrite the archive", 0, v.repackCount)
    }

    @Test
    fun `flush after a content change repacks once`() {
        val v = newVfs()
        // Different size, so the check is immune to filesystem mtime granularity.
        Files.writeString(v.getPath("/a.txt"), "alpha-edited")
        v.flush()
        assertEquals(1, v.repackCount)
        // The re-extracted temp dir is pristine again.
        v.flush()
        assertEquals(1, v.repackCount)
    }

    @Test
    fun `flush after a delete repacks`() {
        val v = newVfs()
        Files.delete(v.getPath("/sub/b.txt"))
        v.flush()
        assertEquals(1, v.repackCount)
    }

    @Test
    fun `flush after adding a file repacks`() {
        val v = newVfs()
        Files.writeString(v.getPath("/new.txt"), "added")
        v.flush()
        assertEquals(1, v.repackCount)
    }

    @Test
    fun `rename repacks once and the following flush does not repack again`() = runBlocking {
        val v = newVfs()
        v.renameFile(v.getPath("/a.txt"), "renamed.txt")
        assertEquals("renameFile repacks directly", 1, v.repackCount)
        v.flush()
        assertEquals("flush after rename must not repack a second time", 1, v.repackCount)
    }
}
