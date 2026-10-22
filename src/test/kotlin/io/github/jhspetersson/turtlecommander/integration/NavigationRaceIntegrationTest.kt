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
import io.github.jhspetersson.turtlecommander.ui.getDisplayPath
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

class NavigationRaceIntegrationTest : BasePlatformTestCase() {

    private class GatedVfs(override val root: Path, override val archivePath: Path) : VirtualFileSystem {
        val gates = ConcurrentHashMap<Path, CountDownLatch>()
        val started = ConcurrentHashMap<Path, CountDownLatch>()
        val listings = ConcurrentHashMap<Path, Int>()

        override suspend fun listFiles(directory: Path): List<FileEntry> {
            listings.merge(directory, 1, Int::plus)
            started[directory]?.countDown()
            gates[directory]?.let { gate -> withContext(Dispatchers.IO) { gate.await() } }
            val name = "${directory.fileName}-child"
            val stamp = FileTime.fromMillis(0)
            return listOf(FileEntry(name, directory.resolve(name), false, 1, stamp, stamp, permissions = ""))
        }

        override fun isRoot(path: Path): Boolean = path == root
        override fun getPath(relativePath: String): Path = root.resolve(relativePath.removePrefix("/"))
        override fun flush() {}
        override suspend fun renameFile(source: Path, newName: String): Path = source
        override fun close() {}
    }

    private lateinit var tempDir: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var tab: FileTab
    private lateinit var vfs: GatedVfs
    private lateinit var slow: Path
    private lateinit var fast: Path
    private val parentDisposable = Disposer.newDisposable("turtle-test-navigation-race")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-navigation-race-")
        val root = Files.createDirectories(tempDir.resolve("archive-root"))
        slow = Files.createDirectories(root.resolve("slow"))
        fast = Files.createDirectories(root.resolve("fast"))
        panel = FileManagerPanel(
            project = project,
            initialPath = tempDir,
            otherPanelPathProvider = { tempDir },
        )
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab = panel.getActiveTab()!!
        vfs = GatedVfs(root, tempDir.resolve("fake.zip"))
        tab.vfsStack.add(VfsStackEntry(vfs, vfs.archivePath))
    }

    override fun tearDown() {
        try {
            vfs.gates.values.forEach { it.countDown() }
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

    private fun shownNames(): List<String> =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it)?.name }

    private fun startGatedNavigation(target: Path): Pair<CountDownLatch, Job> {
        val gate = CountDownLatch(1)
        val begun = CountDownLatch(1)
        vfs.gates[target] = gate
        vfs.started[target] = begun
        val job = tab.fileOps.launch { tab.navigateTo(target, requestFocus = false) }
        waitFor("listing of $target to start") { begun.count == 0L }
        return gate to job
    }

    fun testAnEarlierSlowListingDoesNotOverwriteALaterNavigation() {
        val (gate, slowJob) = startGatedNavigation(slow)

        val fastJob = tab.fileOps.launch { tab.navigateTo(fast, requestFocus = false) }
        waitFor("fast navigation") { fastJob.isCompleted }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(fast, tab.currentPath)

        gate.countDown()
        waitFor("slow navigation to finish") { slowJob.isCompleted }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("the newest navigation must stay current", fast, tab.currentPath)
        assertEquals(listOf("fast-child"), shownNames())
        assertTrue(tab.getDisplayPath(), tab.getDisplayPath().endsWith("fast"))
    }

    fun testTheNewestNavigationWinsEvenWhenItIsTheSlowOne() {
        val (gate, _) = startGatedNavigation(slow)

        val fastJob = tab.fileOps.launch { tab.navigateTo(fast, requestFocus = false) }
        waitFor("fast navigation") { fastJob.isCompleted }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(fast, tab.currentPath)

        val secondSlowJob = tab.fileOps.launch { tab.navigateTo(slow, requestFocus = false) }
        waitFor("second slow listing to start") { vfs.listings[slow] == 2 }
        gate.countDown()
        waitFor("second slow navigation") { secondSlowJob.isCompleted }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(slow, tab.currentPath)
        assertEquals(listOf("slow-child"), shownNames())
    }
}
