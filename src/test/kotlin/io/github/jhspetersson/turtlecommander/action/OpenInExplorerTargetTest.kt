package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class OpenInExplorerTargetTest : BasePlatformTestCase() {

    private class FakeVfs(override val root: Path, override val archivePath: Path) : VirtualFileSystem {
        override suspend fun listFiles(directory: Path): List<FileEntry> {
            val stamp = FileTime.fromMillis(0)
            return Files.list(directory).use { stream ->
                stream.map { FileEntry(it.fileName.toString(), it, false, 1, stamp, stamp, permissions = "") }.toList()
            }
        }

        override fun isRoot(path: Path): Boolean = path == root
        override fun getPath(relativePath: String): Path = root.resolve(relativePath.removePrefix("/"))
        override fun flush() {}
        override suspend fun renameFile(source: Path, newName: String): Path = source
        override fun close() {}
    }

    private lateinit var tempDir: Path
    private lateinit var archive: Path
    private lateinit var inner: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var tab: FileTab
    private val parentDisposable = Disposer.newDisposable("turtle-test-open-in-explorer")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-open-in-explorer-")
        archive = Files.createFile(tempDir.resolve("bundle.zip"))
        val root = Files.createDirectories(tempDir.resolve("extracted"))
        inner = Files.createFile(root.resolve("inner.txt"))
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab = panel.getActiveTab()!!
        waitFor("listing") { tab.tableModel.rowCount >= 1 }
        val vfs = FakeVfs(root, archive)
        tab.vfsStack.add(VfsStackEntry(vfs, archive))
        val job = tab.fileOps.launch { tab.navigateTo(root, requestFocus = false) }
        waitFor("archive listing") { job.isCompleted && tab.currentPath == root }
    }

    override fun tearDown() {
        try {
            tab.vfsStack.clear()
            Disposer.dispose(parentDisposable)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        repeat(100) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for $description")
    }

    private fun entryFor(path: Path): FileEntry {
        val stamp = FileTime.fromMillis(0)
        return FileEntry(path.fileName.toString(), path, false, 1, stamp, stamp, permissions = "")
    }

    fun testInsideAnArchiveTheRevealTargetIsTheArchiveFile() {
        assertEquals(archive, explorerRevealTarget(tab, entryFor(inner)))
    }

    fun testOutsideAnArchiveTheRevealTargetIsTheEntry() {
        tab.vfsStack.clear()
        val plain = Files.createFile(tempDir.resolve("plain.txt"))
        assertEquals(plain, explorerRevealTarget(tab, entryFor(plain)))
    }
}
