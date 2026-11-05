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
import io.github.jhspetersson.turtlecommander.ui.ViewMode
import io.github.jhspetersson.turtlecommander.ui.listContextEntry
import io.github.jhspetersson.turtlecommander.ui.setViewMode
import io.github.jhspetersson.turtlecommander.ui.treeContextEntry
import java.awt.Point
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class ContextMenuTargetIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var tab: FileTab
    private val parentDisposable = Disposer.newDisposable("turtle-test-context-target")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-context-target-")
        Files.writeString(tempDir.resolve("a.txt"), "a")
        Files.writeString(tempDir.resolve("b.txt"), "b")
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab = panel.getActiveTab()!!
        waitFor("listing") { (0 until tab.tableModel.rowCount).any { tab.tableModel.getEntryAt(it)?.name == "b.txt" } }
    }

    override fun tearDown() {
        try {
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

    private fun switchTo(mode: ViewMode) {
        tab.setViewMode(mode)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun checkList(list: JList<FileEntry>, model: DefaultListModel<FileEntry>) {
        waitFor("list model") { (0 until model.size()).any { model.getElementAt(it).name == "b.txt" } }
        val a = (0 until model.size()).first { model.getElementAt(it).name == "a.txt" }
        val b = (0 until model.size()).first { model.getElementAt(it).name == "b.txt" }
        list.setSize(1000, 1000)
        list.selectedIndex = a

        assertNull(listContextEntry(list, model, Point(990, 990)))
        assertEquals(listOf(a), list.selectedIndices.toList())

        val cell = list.getCellBounds(b, b)
        assertEquals("b.txt", listContextEntry(list, model, Point(cell.x + 5, cell.y + cell.height / 2))?.name)
        assertEquals(listOf(b), list.selectedIndices.toList())
    }

    fun testListEmptySpaceTargetsNoEntry() {
        switchTo(ViewMode.LIST)
        checkList(tab.list, tab.listModel)
    }

    fun testThumbnailEmptySpaceTargetsNoEntry() {
        switchTo(ViewMode.THUMBNAIL)
        checkList(tab.thumbnailList, tab.thumbnailListModel)
    }

    private fun treePathOf(name: String): TreePath? = (0 until tab.tree.rowCount).map { tab.tree.getPathForRow(it) }
        .firstOrNull { ((it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FileEntry)?.name == name }

    private fun setUpTree(): Pair<TreePath, TreePath> {
        switchTo(ViewMode.TREE)
        waitFor("full tree") { (treePathOf("b.txt")?.pathCount ?: 0) > 2 && treePathOf("a.txt") != null }
        val last = tab.tree.getRowBounds(tab.tree.rowCount - 1)
        tab.tree.setSize(400, last.y + last.height + 200)
        return treePathOf("a.txt")!! to treePathOf("b.txt")!!
    }

    fun testTreeEmptySpaceTargetsNoEntry() {
        val (a, _) = setUpTree()
        tab.tree.selectionPath = a
        val last = tab.tree.getRowBounds(tab.tree.rowCount - 1)

        assertNull(tab.treeContextEntry(Point(10, last.y + last.height + 100)))
    }

    fun testTreeTargetsTheClickedRowInsideAMultiSelection() {
        val (a, b) = setUpTree()
        tab.tree.selectionPath = a
        tab.tree.addSelectionPath(b)
        assertEquals("a.txt", ((tab.tree.lastSelectedPathComponent as DefaultMutableTreeNode).userObject as FileEntry).name)
        val row = tab.tree.getPathBounds(b)!!

        assertEquals("b.txt", tab.treeContextEntry(Point(row.x + 5, row.y + row.height / 2))?.name)
        assertEquals(2, tab.tree.selectionCount)
    }
}
