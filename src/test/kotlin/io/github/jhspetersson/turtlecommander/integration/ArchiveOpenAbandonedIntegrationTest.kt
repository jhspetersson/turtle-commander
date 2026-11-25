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
import io.github.jhspetersson.turtlecommander.ui.enterVfs
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemProvider
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ArchiveOpenAbandonedIntegrationTest : BasePlatformTestCase() {

    private class RecordingVfs(override val root: Path, override val archivePath: Path) : VirtualFileSystem {
        val closes = AtomicInteger()

        override suspend fun listFiles(directory: Path): List<FileEntry> {
            val stamp = FileTime.fromMillis(0)
            return listOf(FileEntry("child.txt", directory.resolve("child.txt"), false, 1, stamp, stamp, permissions = ""))
        }

        override fun isRoot(path: Path): Boolean = path == root
        override fun getPath(relativePath: String): Path = root.resolve(relativePath.removePrefix("/"))
        override fun flush() {}
        override suspend fun renameFile(source: Path, newName: String): Path = source
        override fun close() {
            closes.incrementAndGet()
        }
    }

    private object SlowProvider : VirtualFileSystemProvider {
        @Volatile var started = CountDownLatch(1)
        @Volatile var release = CountDownLatch(1)
        val created = CopyOnWriteArrayList<RecordingVfs>()

        fun reset() {
            started = CountDownLatch(1)
            release = CountDownLatch(1)
            created.clear()
        }

        override fun supportsExtension(ext: String): Boolean = ext == "turtleslowopen"

        override fun create(archivePath: Path): VirtualFileSystem {
            started.countDown()
            release.await(30, TimeUnit.SECONDS)
            val root = Files.createDirectories(archivePath.resolveSibling(archivePath.fileName.toString() + ".root"))
            return RecordingVfs(root, archivePath).also { created.add(it) }
        }
    }

    private lateinit var tempDir: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var tab: FileTab
    private val parentDisposable = Disposer.newDisposable("turtle-test-archive-open-abandoned")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-open-abandoned-")
        Files.write(tempDir.resolve("data.turtleslowopen"), byteArrayOf(1))
        SlowProvider.reset()
        if (!registered) {
            VirtualFileSystemRegistry.register(SlowProvider)
            registered = true
        }
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        tab = panel.getActiveTab()!!
        waitFor("listing") { tab.tableModel.rowCount >= 1 }
    }

    override fun tearDown() {
        try {
            SlowProvider.release.countDown()
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

    private fun entryFor(path: Path): FileEntry {
        val stamp = FileTime.fromMillis(0)
        return FileEntry(path.fileName.toString(), path, false, 1, stamp, stamp, permissions = "")
    }

    fun testClosingTheTabWhileTheArchiveOpensClosesTheArchive() {
        tab.enterVfs(entryFor(tempDir.resolve("data.turtleslowopen")))
        assertTrue(SlowProvider.started.await(10, TimeUnit.SECONDS))

        Disposer.dispose(tab)
        SlowProvider.release.countDown()

        waitFor("archive created") { SlowProvider.created.size == 1 }
        waitFor("archive closed") { SlowProvider.created.single().closes.get() == 1 }
        assertTrue(tab.vfsStack.isEmpty())
    }

    fun testLeavingTheOuterArchiveWhileANestedOneOpensDropsTheNestedOne() {
        val outerRoot = Files.createDirectories(tempDir.resolve("outer-root"))
        val inner = outerRoot.resolve("inner.turtleslowopen")
        Files.write(inner, byteArrayOf(1))
        val outer = RecordingVfs(outerRoot, tempDir.resolve("outer.tar"))
        tab.vfsStack.add(VfsStackEntry(outer, outer.archivePath))
        tab.fileOps.launch { tab.navigateTo(outerRoot, requestFocus = false) }
        waitFor("inside the outer archive") { tab.currentPath == outerRoot }

        tab.enterVfs(entryFor(inner))
        assertTrue(SlowProvider.started.await(10, TimeUnit.SECONDS))
        tab.closeVfsStackAsync()
        tab.fileOps.launch { tab.navigateTo(tempDir, requestFocus = false) }
        waitFor("left the outer archive") { tab.currentPath == tempDir }
        SlowProvider.release.countDown()

        waitFor("nested archive created") { SlowProvider.created.size == 1 }
        waitFor("nested archive closed") { SlowProvider.created.single().closes.get() == 1 }
        assertTrue(tab.vfsStack.isEmpty())
        assertEquals(tempDir, tab.currentPath)
    }

    fun testNavigatingElsewhereWhileTheArchiveOpensDropsTheArchive() {
        val elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"))

        tab.enterVfs(entryFor(tempDir.resolve("data.turtleslowopen")))
        assertTrue(SlowProvider.started.await(10, TimeUnit.SECONDS))
        tab.fileOps.launch { tab.navigateTo(elsewhere, requestFocus = false) }
        waitFor("navigated elsewhere") { tab.currentPath == elsewhere }
        SlowProvider.release.countDown()

        waitFor("archive created") { SlowProvider.created.size == 1 }
        waitFor("archive closed") { SlowProvider.created.single().closes.get() == 1 }
        assertTrue(tab.vfsStack.isEmpty())
        assertEquals(elsewhere, tab.currentPath)
    }

    fun testRefreshingTheSameFolderWhileTheArchiveOpensStillEntersIt() {
        tab.enterVfs(entryFor(tempDir.resolve("data.turtleslowopen")))
        assertTrue(SlowProvider.started.await(10, TimeUnit.SECONDS))
        val generation = tab.navigationGeneration.get()
        tab.fileOps.launch { tab.navigateTo(tempDir, requestFocus = false) }
        waitFor("refreshed") { tab.navigationGeneration.get() > generation && tab.tableModel.rowCount >= 1 }
        SlowProvider.release.countDown()

        waitFor("archive opened") { tab.vfsStack.size == 1 }
        assertEquals(0, SlowProvider.created.single().closes.get())
    }

    fun testAnArchiveStillOpensWhenNothingInterrupts() {
        SlowProvider.release.countDown()

        tab.enterVfs(entryFor(tempDir.resolve("data.turtleslowopen")))

        waitFor("archive opened") { tab.vfsStack.size == 1 }
        assertEquals(0, SlowProvider.created.single().closes.get())
    }

    companion object {
        private var registered = false
    }
}
