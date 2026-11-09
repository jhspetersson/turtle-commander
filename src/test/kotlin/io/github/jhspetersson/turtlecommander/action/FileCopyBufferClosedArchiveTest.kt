package io.github.jhspetersson.turtlecommander.action

import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class FileCopyBufferClosedArchiveTest {

    private class FakeVfs(override val root: Path, override val archivePath: Path) : VirtualFileSystem {
        override suspend fun listFiles(directory: Path): List<FileEntry> = emptyList()
        override fun isRoot(path: Path): Boolean = path == root
        override fun getPath(relativePath: String): Path = root.resolve(relativePath)
        override fun flush() {}
        override suspend fun renameFile(source: Path, newName: String): Path = source
        override fun close() {}
    }

    private val archiveRoot = Path.of("tc-archive-1").toAbsolutePath()
    private val archive = Path.of("a.zip").toAbsolutePath()
    private val closed = FakeVfs(archiveRoot, archive)
    private val stillOpen = FakeVfs(archiveRoot, archive)

    private fun entry(path: Path) = FileEntry(
        name = path.fileName.toString(),
        path = path,
        isDirectory = false,
        size = 1,
        lastModified = FileTime.fromMillis(0),
        permissions = "",
    )

    @Before
    fun setUp() {
        FileCopyBuffer.isCut = false
        FileCopyBuffer.entries = listOf(entry(archiveRoot.resolve("inner.txt")), entry(Path.of("outside.txt").toAbsolutePath()))
    }

    @After
    fun tearDown() {
        OpenVfsRegistry.unregister(stillOpen)
        FileCopyBuffer.entries = emptyList()
    }

    @Test
    fun `entries of a closed archive are dropped`() {
        FileCopyBuffer.dropEntriesOwnedBy(listOf(closed))
        assertEquals(listOf("outside.txt"), FileCopyBuffer.entries.map { it.name })
    }

    @Test
    fun `entries are kept while another open archive still owns them`() {
        OpenVfsRegistry.register(stillOpen)
        FileCopyBuffer.dropEntriesOwnedBy(listOf(closed))
        assertEquals(listOf("inner.txt", "outside.txt"), FileCopyBuffer.entries.map { it.name })
    }

    @Test
    fun `unrelated close leaves the buffer untouched`() {
        var fired = 0
        FileCopyBuffer.addChangeListener { fired++ }
        FileCopyBuffer.dropEntriesOwnedBy(listOf(FakeVfs(Path.of("other").toAbsolutePath(), Path.of("b.zip").toAbsolutePath())))
        assertEquals(0, fired)
        assertEquals(2, FileCopyBuffer.entries.size)
    }
}
