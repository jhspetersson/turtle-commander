package io.github.jhspetersson.turtlecommander.vfs

import io.github.jhspetersson.turtlecommander.model.FileEntry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class OpenVfsRegistryTest {

    private val registered = mutableListOf<RecordingVfs>()

    @After
    fun cleanup() {
        for (vfs in registered) {
            OpenVfsRegistry.unregister(vfs)
            Files.walk(vfs.root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        registered.clear()
    }

    private fun newVfs(prefix: String = "open-vfs-reg-test-"): RecordingVfs {
        val root = Files.createTempDirectory(prefix)
        val vfs = RecordingVfs(root)
        registered.add(vfs)
        OpenVfsRegistry.register(vfs)
        return vfs
    }

    @Test
    fun `materializeIfNeeded dispatches to the owning VFS`() {
        val vfs = newVfs()
        val inside = vfs.root.resolve("subdir/file.txt")
        Files.createDirectories(inside.parent)
        Files.createFile(inside)

        OpenVfsRegistry.materializeIfNeeded(inside)
        assertEquals(1, vfs.materializeCount.get())
        assertEquals(inside, vfs.lastMaterializedPath)
    }

    @Test
    fun `materializeIfNeeded is a no-op for paths outside every registered VFS`() {
        val vfs = newVfs()
        val outside = Files.createTempFile("not-in-any-vfs-", ".tmp")
        try {
            OpenVfsRegistry.materializeIfNeeded(outside)
            assertEquals(0, vfs.materializeCount.get())
            assertNull(vfs.lastMaterializedPath)
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `materializeIfNeeded picks the right VFS when multiple are open`() {
        val vfsA = newVfs("open-vfs-reg-a-")
        val vfsB = newVfs("open-vfs-reg-b-")
        val insideA = vfsA.root.resolve("a.txt").also { Files.createFile(it) }
        val insideB = vfsB.root.resolve("b.txt").also { Files.createFile(it) }

        OpenVfsRegistry.materializeIfNeeded(insideA)
        OpenVfsRegistry.materializeIfNeeded(insideB)

        assertEquals(1, vfsA.materializeCount.get())
        assertEquals(insideA, vfsA.lastMaterializedPath)
        assertEquals(1, vfsB.materializeCount.get())
        assertEquals(insideB, vfsB.lastMaterializedPath)
    }

    @Test
    fun `unregister stops dispatch even when the directory still exists on disk`() {
        val vfs = newVfs()
        val inside = vfs.root.resolve("x.txt").also { Files.createFile(it) }

        OpenVfsRegistry.unregister(vfs)
        OpenVfsRegistry.materializeIfNeeded(inside)
        assertEquals(0, vfs.materializeCount.get())
    }

    @Test
    fun `default owns implementation matches root-prefixed paths`() {
        val vfs = newVfs()
        val inside = vfs.root.resolve("sub/x.txt")
        val outside = Files.createTempDirectory("outside-").resolve("y.txt")
        try {
            assertTrue(vfs.owns(inside))
            assertFalse(vfs.owns(outside))
        } finally {
            Files.deleteIfExists(outside.parent)
        }
    }

    /**
     * Minimal [VirtualFileSystem] used to count `materialize` invocations through the
     * registry. Doesn't need to extract anything — the tests only care that the
     * registry dispatches to the right instance.
     */
    private class RecordingVfs(override val root: Path) : VirtualFileSystem {
        val materializeCount = AtomicInteger(0)
        var lastMaterializedPath: Path? = null

        override val archivePath: Path get() = root
        override val isReadOnly: Boolean get() = true
        override suspend fun listFiles(directory: Path): List<FileEntry> = emptyList()
        override fun isRoot(path: Path): Boolean = path == root
        override fun getPath(relativePath: String): Path = root.resolve(relativePath.trimStart('/'))
        override fun flush() {}
        override suspend fun renameFile(source: Path, newName: String): Path =
            throw UnsupportedOperationException()
        override fun close() {}

        override fun materialize(path: Path) {
            materializeCount.incrementAndGet()
            lastMaterializedPath = path
        }
    }
}
