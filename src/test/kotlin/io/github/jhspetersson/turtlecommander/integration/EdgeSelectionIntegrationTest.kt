package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.ViewMode
import io.github.jhspetersson.turtlecommander.ui.getSelectedEntry
import io.github.jhspetersson.turtlecommander.ui.setViewMode
import java.nio.file.Files
import java.nio.file.Path

class EdgeSelectionIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var tab: FileTab
    private val parentDisposable = Disposer.newDisposable("turtle-test-edge-selection")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-edge-selection-")
        for (name in listOf("a.txt", "b.txt", "c.txt", "d.txt")) Files.writeString(tempDir.resolve(name), name)
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab = panel.getActiveTab()!!
        waitFor("listing") { tab.tableModel.rowCount >= 4 }
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

    private fun switchTo(mode: ViewMode) {
        tab.setViewMode(mode)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    fun testTableViewSelectsFirstAndLastRow() {
        switchTo(ViewMode.TABLE)
        tab.selectLast()
        assertEquals(tab.table.rowCount - 1, tab.table.selectedRow)
        tab.selectFirst()
        assertEquals(0, tab.table.selectedRow)
    }

    fun testListViewSelectsFirstAndLastIndex() {
        switchTo(ViewMode.LIST)
        waitFor("list model") { tab.listModel.size() >= 4 }
        tab.selectLast()
        assertEquals(tab.listModel.size() - 1, tab.list.selectedIndex)
        assertEquals("d.txt", tab.getSelectedEntry()?.name)
        tab.selectFirst()
        assertEquals(0, tab.list.selectedIndex)
    }

    fun testThumbnailViewSelectsFirstAndLastIndex() {
        switchTo(ViewMode.THUMBNAIL)
        waitFor("thumbnail model") { tab.thumbnailListModel.size() >= 4 }
        tab.selectLast()
        assertEquals(tab.thumbnailListModel.size() - 1, tab.thumbnailList.selectedIndex)
        assertEquals("d.txt", tab.getSelectedEntry()?.name)
        tab.selectFirst()
        assertEquals(0, tab.thumbnailList.selectedIndex)
    }

    fun testTreeViewSelectsFirstAndLastRow() {
        switchTo(ViewMode.TREE)
        waitFor("tree rows") { tab.tree.rowCount >= 4 }
        tab.selectLast()
        assertEquals(tab.tree.rowCount - 1, tab.tree.leadSelectionRow)
        tab.selectFirst()
        assertEquals(0, tab.tree.leadSelectionRow)
    }
}
