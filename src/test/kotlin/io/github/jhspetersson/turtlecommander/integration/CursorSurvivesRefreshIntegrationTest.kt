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
import io.github.jhspetersson.turtlecommander.ui.getSelectedEntry
import io.github.jhspetersson.turtlecommander.ui.setViewMode
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode

class CursorSurvivesRefreshIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var tab: FileTab
    private val parentDisposable = Disposer.newDisposable("turtle-test-cursor-survives-refresh")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-cursor-refresh-")
        for (name in listOf("a.txt", "b.txt", "c.txt", "d.txt", "e.txt")) Files.writeString(tempDir.resolve(name), name)
        val panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        tab = panel.getActiveTab()!!
        waitFor("listing") { tab.tableModel.rowCount >= 5 }
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
            Thread.sleep(100)
        }
        fail("Timed out waiting for $description")
    }

    private fun treeNameAt(row: Int): String? =
        ((tab.tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FileEntry)?.name

    private fun treeRowOf(name: String): Int = (0 until tab.tree.rowCount).firstOrNull { treeNameAt(it) == name } ?: -1

    private fun refreshAfterAddingAFile(appeared: () -> Boolean) {
        Files.writeString(tempDir.resolve("z-new.txt"), "new")
        tab.refresh(requestFocus = false)
        waitFor("refreshed listing") { appeared() }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    fun testListViewKeepsTheCursorOnRefresh() {
        tab.setViewMode(ViewMode.LIST)
        val index = (0 until tab.listModel.size()).first { tab.listModel.getElementAt(it).name == "d.txt" }
        tab.list.selectedIndex = index

        refreshAfterAddingAFile { (0 until tab.listModel.size()).any { tab.listModel.getElementAt(it).name == "z-new.txt" } }

        assertEquals("d.txt", tab.getSelectedEntry()?.name)
    }

    fun testThumbnailViewKeepsTheCursorOnRefresh() {
        tab.setViewMode(ViewMode.THUMBNAIL)
        val index = (0 until tab.thumbnailListModel.size()).first { tab.thumbnailListModel.getElementAt(it).name == "c.txt" }
        tab.thumbnailList.selectedIndex = index

        refreshAfterAddingAFile {
            (0 until tab.thumbnailListModel.size()).any { tab.thumbnailListModel.getElementAt(it).name == "z-new.txt" }
        }

        assertEquals("c.txt", tab.getSelectedEntry()?.name)
    }

    fun testTreeViewKeepsTheCursorOnRefresh() {
        tab.setViewMode(ViewMode.TREE)
        waitFor("tree rows") { treeRowOf(tempDir.fileName.toString()) >= 0 && treeRowOf("d.txt") >= 0 }
        tab.tree.setSelectionRow(treeRowOf("d.txt"))

        refreshAfterAddingAFile { treeRowOf("z-new.txt") >= 0 }

        assertEquals("d.txt", tab.getSelectedEntry()?.name)
    }

    fun testFlatTreeKeepsTheCursorOnRefresh() {
        tab.setShowAllNestedFiles(true)
        waitFor("flat listing") { tab.tableModel.rowCount >= 5 }
        tab.setViewMode(ViewMode.TREE)
        waitFor("tree rows") { treeRowOf("c.txt") >= 0 }
        tab.tree.setSelectionRow(treeRowOf("c.txt"))

        refreshAfterAddingAFile { treeRowOf("z-new.txt") >= 0 }

        assertEquals("c.txt", tab.getSelectedEntry()?.name)
    }

    fun testTableViewStillKeepsTheCursorOnRefresh() {
        val modelRow = (0 until tab.tableModel.rowCount).first { tab.tableModel.getEntryAt(it)?.name == "d.txt" }
        val viewRow = tab.table.convertRowIndexToView(modelRow)
        tab.table.setRowSelectionInterval(viewRow, viewRow)

        refreshAfterAddingAFile { (0 until tab.tableModel.rowCount).any { tab.tableModel.getEntryAt(it)?.name == "z-new.txt" } }

        assertEquals("d.txt", tab.getSelectedEntry()?.name)
    }
}
