package io.github.jhspetersson.turtlecommander.service

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.action.FileCopyBuffer
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class VfsCloseDropsCopyBufferTest : BasePlatformTestCase() {

    private class FakeVfs(override val root: Path, override val archivePath: Path) : VirtualFileSystem {
        override suspend fun listFiles(directory: Path): List<FileEntry> = emptyList()
        override fun isRoot(path: Path): Boolean = path == root
        override fun getPath(relativePath: String): Path = root.resolve(relativePath)
        override fun flush() {}
        override suspend fun renameFile(source: Path, newName: String): Path = source
        override fun close() {}
    }

    override fun tearDown() {
        try {
            FileCopyBuffer.entries = emptyList()
        } finally {
            super.tearDown()
        }
    }

    fun `test closing an archive drops its entries from the copy buffer`() {
        val base = Path.of("turtle-close-buffer").toAbsolutePath()
        val vfs = FakeVfs(base.resolve("root"), base.resolve("a.zip"))
        val inner = vfs.root.resolve("inner.txt")
        FileCopyBuffer.entries = listOf(FileEntry("inner.txt", inner, false, 1, lastModified = FileTime.fromMillis(0), permissions = ""))

        VfsCloseService(CoroutineScope(Dispatchers.Unconfined)).closeNow(listOf(VfsStackEntry(vfs, base)))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue(FileCopyBuffer.entries.isEmpty())
    }
}
