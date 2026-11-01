package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.ViewMode
import io.github.jhspetersson.turtlecommander.ui.setViewMode
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class DoubleClickEmptySpaceIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var tab: FileTab
    private val parentDisposable = Disposer.newDisposable("turtle-test-double-click-empty")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-double-click-empty-")
        Files.writeString(tempDir.resolve("a.txt"), "a")
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab = panel.getActiveTab()!!
        waitFor("listing") { (0 until tab.tableModel.rowCount).any { tab.tableModel.getEntryAt(it)?.name == "a.txt" } }
    }

    override fun tearDown() {
        try {
            val editors = FileEditorManager.getInstance(project)
            editors.openFiles.forEach { editors.closeFile(it) }
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

    private fun settle() {
        repeat(10) {
            Thread.sleep(50)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }

    private fun openFileNames(): List<String> = FileEditorManager.getInstance(project).openFiles.map { it.name }

    private fun doubleClick(component: Component, point: Point) {
        component.dispatchEvent(
            MouseEvent(component, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, point.x, point.y, 2, false, MouseEvent.BUTTON1)
        )
    }

    private fun assertEmptySpaceIgnoredThenItemOpens(component: Component, empty: Point, item: Point) {
        doubleClick(component, empty)
        settle()
        assertEquals(emptyList<String>(), openFileNames())

        doubleClick(component, item)
        waitFor("a.txt to open") { "a.txt" in openFileNames() }
    }

    private fun switchTo(mode: ViewMode) {
        tab.setViewMode(mode)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    fun testTableIgnoresDoubleClickBelowTheRows() {
        val table = tab.table
        val viewRow = (0 until table.rowCount).first { tab.tableModel.getEntryAt(table.convertRowIndexToModel(it))?.name == "a.txt" }
        table.setSize(400, table.rowCount * table.rowHeight + 200)
        table.setRowSelectionInterval(viewRow, viewRow)
        val cell = table.getCellRect(viewRow, 0, true)

        assertEmptySpaceIgnoredThenItemOpens(
            table,
            Point(10, table.rowCount * table.rowHeight + 100),
            Point(cell.x + 5, cell.y + cell.height / 2),
        )
    }

    private fun checkList(list: JList<FileEntry>, model: DefaultListModel<FileEntry>) {
        waitFor("list model") { (0 until model.size()).any { model.getElementAt(it).name == "a.txt" } }
        val index = (0 until model.size()).first { model.getElementAt(it).name == "a.txt" }
        list.setSize(1000, 1000)
        list.selectedIndex = index
        val cell = list.getCellBounds(index, index)

        assertEmptySpaceIgnoredThenItemOpens(list, Point(990, 990), Point(cell.x + 5, cell.y + cell.height / 2))
    }

    fun testListIgnoresDoubleClickOutsideTheCells() {
        switchTo(ViewMode.LIST)
        checkList(tab.list, tab.listModel)
    }

    fun testThumbnailListIgnoresDoubleClickOutsideTheCells() {
        switchTo(ViewMode.THUMBNAIL)
        checkList(tab.thumbnailList, tab.thumbnailListModel)
    }

    fun testTreeIgnoresDoubleClickBelowTheRows() {
        switchTo(ViewMode.TREE)
        val tree = tab.tree
        fun findPath(): TreePath? = (0 until tree.rowCount).map { tree.getPathForRow(it) }
            .firstOrNull { ((it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FileEntry)?.name == "a.txt" }
        waitFor("full tree with a.txt") { (findPath()?.pathCount ?: 0) > 2 }
        val path = findPath()!!
        tree.selectionPath = path
        val last = tree.getRowBounds(tree.rowCount - 1)
        tree.setSize(400, last.y + last.height + 200)
        val row = tree.getPathBounds(path)!!

        assertEmptySpaceIgnoredThenItemOpens(
            tree,
            Point(10, last.y + last.height + 100),
            Point(row.x + 5, row.y + row.height / 2),
        )
    }
}
