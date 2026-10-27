package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.openSelectedInAssociatedApp
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList

class OpenInAppMaterializesIntegrationTest : BasePlatformTestCase() {

    private class LazyStubVfs(override val root: Path, override val archivePath: Path) : VirtualFileSystem {
        val materialized = CopyOnWriteArrayList<Path>()

        override suspend fun listFiles(directory: Path): List<FileEntry> {
            val stamp = FileTime.fromMillis(0)
            return Files.list(directory).use { stream ->
                stream.map { FileEntry(it.fileName.toString(), it, false, 7, stamp, stamp, permissions = "") }.toList()
            }
        }

        override fun materialize(path: Path) {
            materialized.add(path)
            Files.writeString(path, "payload")
        }

        override fun isRoot(path: Path): Boolean = path == root
        override fun getPath(relativePath: String): Path = root.resolve(relativePath.removePrefix("/"))
        override fun flush() {}
        override suspend fun renameFile(source: Path, newName: String): Path = source
        override fun close() {}
    }

    private lateinit var tempDir: Path
    private lateinit var stub: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var tab: FileTab
    private lateinit var vfs: LazyStubVfs
    private val parentDisposable = Disposer.newDisposable("turtle-test-open-in-app")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-open-in-app-")
        val root = Files.createDirectories(tempDir.resolve("archive-root"))
        stub = root.resolve("doc.txt")
        Files.createFile(stub)
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab = panel.getActiveTab()!!
        vfs = LazyStubVfs(root, tempDir.resolve("fake.iso"))
        tab.vfsStack.add(VfsStackEntry(vfs, vfs.archivePath))
        val job = tab.fileOps.launch { tab.navigateTo(root, requestFocus = false) }
        waitFor("listing") { job.isCompleted && tab.tableModel.rowCount >= 1 }
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

    fun testOpenInAssociatedAppFillsALazyStubBeforeLaunching() {
        val row = (0 until tab.table.rowCount).first {
            tab.tableModel.getEntryAt(tab.table.convertRowIndexToModel(it))?.name == "doc.txt"
        }
        tab.table.setRowSelectionInterval(row, row)
        var launched: Path? = null
        var sizeAtLaunch = -1L
        tab.openWithAssociatedApp = {
            sizeAtLaunch = Files.size(it)
            launched = it
        }

        tab.openSelectedInAssociatedApp()
        waitFor("launch") { launched != null }

        assertEquals(stub, launched)
        assertEquals(listOf(stub), vfs.materialized)
        assertEquals(7L, sizeAtLaunch)
    }
}
