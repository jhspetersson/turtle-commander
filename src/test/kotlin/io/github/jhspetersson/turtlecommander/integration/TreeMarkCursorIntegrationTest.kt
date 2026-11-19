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
import io.github.jhspetersson.turtlecommander.ui.setViewMode
import io.github.jhspetersson.turtlecommander.ui.toggleSelection
import io.github.jhspetersson.turtlecommander.ui.toggleSelectionAndMoveDown
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode

class TreeMarkCursorIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var tab: FileTab
    private val parentDisposable = Disposer.newDisposable("turtle-test-tree-mark-cursor")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-tree-mark-cursor-")
        for (name in listOf("a.txt", "b.txt", "c.txt", "d.txt")) Files.writeString(tempDir.resolve(name), name)
        val panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        tab = panel.getActiveTab()!!
        waitFor("listing") { tab.tableModel.rowCount >= 4 }
        tab.setViewMode(ViewMode.TREE)
        waitFor("tree rows") { rowOf("d.txt") >= 0 }
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

    private fun nameAt(row: Int): String? =
        ((tab.tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FileEntry)?.name

    private fun rowOf(name: String): Int = (0 until tab.tree.rowCount).firstOrNull { nameAt(it) == name } ?: -1

    private fun markedNames(): Set<String> = tab.markedPaths.map { it.fileName.toString() }.toSet()

    fun testMarkingAndMovingDownPutsTheCursorOnTheNextRow() {
        val start = rowOf("b.txt")
        tab.tree.setSelectionRow(start)

        tab.toggleSelectionAndMoveDown()

        assertEquals(setOf("b.txt"), markedNames())
        assertEquals("c.txt", nameAt(tab.tree.leadSelectionRow))
        assertEquals(listOf(start), tab.tree.selectionRows!!.toList())
    }

    fun testMarkingSeveralRowsInARowWalksDownTheTree() {
        tab.tree.setSelectionRow(rowOf("a.txt"))

        tab.toggleSelectionAndMoveDown()
        tab.toggleSelectionAndMoveDown()
        tab.toggleSelectionAndMoveDown()

        assertEquals(setOf("a.txt", "b.txt", "c.txt"), markedNames())
        assertEquals("d.txt", nameAt(tab.tree.leadSelectionRow))
    }

    fun testMarkingInPlaceKeepsTheCursorWhereItWas() {
        tab.tree.setSelectionRow(rowOf("a.txt"))
        tab.toggleSelectionAndMoveDown()
        tab.tree.leadSelectionPath = tab.tree.getPathForRow(rowOf("c.txt"))

        tab.toggleSelection()

        assertEquals(setOf("a.txt", "c.txt"), markedNames())
        assertEquals("c.txt", nameAt(tab.tree.leadSelectionRow))
    }

    fun testRemovingTheLastMarkKeepsTheCursor() {
        tab.tree.setSelectionRow(rowOf("b.txt"))
        tab.toggleSelection()

        tab.toggleSelection()

        assertTrue(markedNames().isEmpty())
        assertEquals("b.txt", nameAt(tab.tree.leadSelectionRow))
    }
}
