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
import io.github.jhspetersson.turtlecommander.ui.refreshAfterVfsChange
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LeaveArchiveDuringSaveIntegrationTest : BasePlatformTestCase() {

    private class SlowVfs(override val root: Path, override val archivePath: Path) : VirtualFileSystem {
        val flushStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val flushDone = AtomicBoolean()
        val listingsAfterFlush = AtomicInteger()

        override suspend fun listFiles(directory: Path): List<FileEntry> {
            if (flushDone.get()) listingsAfterFlush.incrementAndGet()
            val stamp = FileTime.fromMillis(0)
            return listOf(FileEntry("child.txt", directory.resolve("child.txt"), false, 1, stamp, stamp, permissions = ""))
        }

        override fun isRoot(path: Path): Boolean = path == root
        override fun getPath(relativePath: String): Path = root.resolve(relativePath.removePrefix("/"))
        override fun flush() {
            flushStarted.countDown()
            release.await(30, TimeUnit.SECONDS)
            flushDone.set(true)
        }
        override suspend fun renameFile(source: Path, newName: String): Path = source
        override fun close() {}
    }

    private lateinit var tempDir: Path
    private lateinit var elsewhere: Path
    private lateinit var tab: FileTab
    private lateinit var vfs: SlowVfs
    private val parentDisposable = Disposer.newDisposable("turtle-test-leave-archive-during-save")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-leave-during-save-")
        elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"))
        Files.writeString(elsewhere.resolve("plain.txt"), "x")
        val root = Files.createDirectories(tempDir.resolve("archive-root"))
        val panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab = panel.getActiveTab()!!
        vfs = SlowVfs(root, tempDir.resolve("data.tar"))
        tab.vfsStack.add(VfsStackEntry(vfs, vfs.archivePath))
        tab.fileOps.launch { tab.navigateTo(root, requestFocus = false) }
        waitFor("inside the archive") { tab.currentPath == root }
    }

    override fun tearDown() {
        try {
            vfs.release.countDown()
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

    private fun leaveWhileSavingThenFinishTheSave() {
        assertTrue(vfs.flushStarted.await(10, TimeUnit.SECONDS))
        tab.closeVfsStackAsync()
        tab.fileOps.launch { tab.navigateTo(elsewhere, requestFocus = false) }
        waitFor("left the archive") { tab.currentPath == elsewhere }
        vfs.release.countDown()
        waitFor("save finished") { vfs.flushDone.get() }
        repeat(10) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(50)
        }
    }

    fun testRefreshDoesNotDragTheTabBackAfterLeavingTheArchive() {
        tab.refresh(requestFocus = false)

        leaveWhileSavingThenFinishTheSave()

        assertEquals(elsewhere, tab.currentPath)
        assertEquals(0, vfs.listingsAfterFlush.get())
    }

    fun testRefreshAfterAChangeDoesNotDragTheTabBackAfterLeavingTheArchive() {
        tab.fileOps.launch { tab.refreshAfterVfsChange() }

        leaveWhileSavingThenFinishTheSave()

        assertEquals(elsewhere, tab.currentPath)
        assertEquals(0, vfs.listingsAfterFlush.get())
    }

    fun testRefreshStillRelistsWhenTheTabStaysInTheArchive() {
        tab.refresh(requestFocus = false)
        assertTrue(vfs.flushStarted.await(10, TimeUnit.SECONDS))

        vfs.release.countDown()

        waitFor("relisted") { vfs.listingsAfterFlush.get() > 0 }
        assertEquals(vfs.root, tab.currentPath)
    }
}
