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
import io.github.jhspetersson.turtlecommander.ui.archiveSaveFailureMessage
import io.github.jhspetersson.turtlecommander.ui.refreshAfterVfsChange
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ArchiveSaveFailureIntegrationTest : BasePlatformTestCase() {

    private class FailingVfs(override val root: Path, override val archivePath: Path) : VirtualFileSystem {
        val listings = AtomicInteger()

        override suspend fun listFiles(directory: Path): List<FileEntry> {
            listings.incrementAndGet()
            val stamp = FileTime.fromMillis(0)
            return listOf(FileEntry("child.txt", directory.resolve("child.txt"), false, 1, stamp, stamp, permissions = ""))
        }

        override fun isRoot(path: Path): Boolean = path == root
        override fun getPath(relativePath: String): Path = root.resolve(relativePath.removePrefix("/"))
        override fun flush(): Unit = throw IOException("archive is locked")
        override suspend fun renameFile(source: Path, newName: String): Path = source
        override fun close() {}
    }

    private lateinit var tempDir: Path
    private lateinit var tab: FileTab
    private lateinit var vfs: FailingVfs
    private val failures = CopyOnWriteArrayList<Pair<VirtualFileSystem, Exception>>()
    private val parentDisposable = Disposer.newDisposable("turtle-test-archive-save-failure")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-archive-save-failure-")
        val root = Files.createDirectories(tempDir.resolve("archive-root"))
        val stateService = project.service<FileManagerStateService>()
        val panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab = panel.getActiveTab()!!
        vfs = FailingVfs(root, tempDir.resolve("data.tar"))
        tab.vfsStack.add(VfsStackEntry(vfs, vfs.archivePath))
        tab.onArchiveSaveFailed = { failed, cause -> failures.add(failed to cause) }
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
            Thread.sleep(100)
        }
        fail("Timed out waiting for $description")
    }

    fun testRefreshReportsAFailedSaveAndStillLists() {
        tab.refresh(requestFocus = false)

        waitFor("failure reported") { failures.isNotEmpty() }
        assertSame(vfs, failures.single().first)
        assertEquals("archive is locked", failures.single().second.message)
        waitFor("listing still runs") { vfs.listings.get() > 0 }
    }

    fun testRefreshAfterAChangeReportsAFailedSave() {
        tab.fileOps.launch { tab.refreshAfterVfsChange() }

        waitFor("failure reported") { failures.isNotEmpty() }
        assertSame(vfs, failures.single().first)
        waitFor("listing still runs") { vfs.listings.get() > 0 }
    }

    fun testTheMessageNamesTheArchiveTheCauseAndWhereTheFilesAre() {
        val message = archiveSaveFailureMessage(vfs, IOException("archive is locked"))

        assertTrue(message, "\"data.tar\"" in message)
        assertTrue(message, "archive is locked" in message)
        assertTrue(message, vfs.root.toString() in message)
    }
}
