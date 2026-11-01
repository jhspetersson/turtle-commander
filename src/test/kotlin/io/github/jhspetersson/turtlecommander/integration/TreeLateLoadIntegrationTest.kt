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
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class TreeLateLoadIntegrationTest : BasePlatformTestCase() {

    private class GatedVfs(override val root: Path, override val archivePath: Path) : VirtualFileSystem {
        val gates = ConcurrentHashMap<Path, CountDownLatch>()
        val started = ConcurrentHashMap<Path, CountDownLatch>()
        val finished = ConcurrentHashMap<Path, CountDownLatch>()

        override suspend fun listFiles(directory: Path): List<FileEntry> {
            started[directory]?.countDown()
            gates[directory]?.let { gate -> withContext(Dispatchers.IO) { gate.await() } }
            val stamp = FileTime.fromMillis(0)
            val result = if (directory == root) {
                listOf("a", "b").map { FileEntry(it, root.resolve(it), true, 0, stamp, stamp, permissions = "") }
            } else {
                val name = "${directory.fileName}-child"
                listOf(FileEntry(name, directory.resolve(name), false, 1, stamp, stamp, permissions = ""))
            }
            finished[directory]?.countDown()
            return result
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
    private val parentDisposable = Disposer.newDisposable("turtle-test-tree-late-load")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-tree-late-load-")
        val root = Files.createDirectories(tempDir.resolve("archive-root"))
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
        val job = tab.fileOps.launch { tab.navigateTo(root, requestFocus = false) }
        waitFor("navigation to the archive root") { job.isCompleted }
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

    private fun pathOf(name: String): TreePath {
        val node = (0 until tab.treeRootNode.childCount)
            .map { tab.treeRootNode.getChildAt(it) as DefaultMutableTreeNode }
            .first { (it.userObject as? FileEntry)?.name == name }
        return TreePath(node.path)
    }

    private fun isLoaded(path: TreePath): Boolean {
        val node = path.lastPathComponent as DefaultMutableTreeNode
        return node.childCount == 1 && (node.getChildAt(0) as DefaultMutableTreeNode).userObject is FileEntry
    }

    fun testLateChildListingForADetachedNodeDoesNotCollapseTheTree() {
        val a = vfs.root.resolve("a")
        val gate = CountDownLatch(1)
        val begun = CountDownLatch(1)
        val done = CountDownLatch(1)
        vfs.gates[a] = gate
        vfs.started[a] = begun
        vfs.finished[a] = done

        tab.tree.expandPath(pathOf("a"))
        waitFor("listing of a to start") { begun.count == 0L }

        tab.populateFlatTree()
        val freshB = pathOf("b")
        tab.tree.expandPath(freshB)
        waitFor("listing of b") { isLoaded(freshB) }
        assertTrue(tab.tree.isExpanded(freshB))

        gate.countDown()
        waitFor("late listing of a") { done.count == 0L }
        repeat(5) {
            Thread.sleep(50)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }

        assertTrue("b must stay expanded", tab.tree.isExpanded(freshB))
        assertTrue("b's children must survive", isLoaded(freshB))
    }
}
