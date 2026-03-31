package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression tests for tab drag-and-drop bugs:
 * - Shrinking: JBTabbedPane.insertTab calls UIUtil.addInsets on each insert,
 *   accumulating padding on tab content components.
 * - Last position: tabs must be reorderable/movable to the last position.
 */
class TabDragDropRegressionTest : BasePlatformTestCase() {

    private lateinit var projectPath: Path
    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        projectPath = Path.of(project.basePath!!)
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-dnd-")
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
    }

    override fun tearDown() {
        try {
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun skipIfHeadless(): Boolean = GraphicsEnvironment.isHeadless()

    private fun createPanel(): FileManagerPanel {
        val panel = FileManagerPanel(
            project = project,
            initialPath = projectPath,
            otherPanelPathProvider = { projectPath },
        )
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return panel
    }

    private fun createPanelWithTabs(vararg dirs: Path): FileManagerPanel {
        val panel = createPanel()
        for (dir in dirs) {
            panel.openDirectoryInNewTab(dir)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return panel
    }

    // --- Shrinking regression: border must not accumulate after reorder ---

    fun testReorderTabPreservesBorder() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("a"))
        val dir2 = Files.createDirectory(tempDir.resolve("b"))
        val panel = createPanelWithTabs(dir1, dir2)

        val tab = panel.getTabAt(0)!!
        val insetsBefore = tab.insets

        panel.reorderTab(0, 1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val insetsAfter = tab.insets
        assertEquals("Top insets must not grow after reorder", insetsBefore.top, insetsAfter.top)
        assertEquals("Left insets must not grow after reorder", insetsBefore.left, insetsAfter.left)
        assertEquals("Bottom insets must not grow after reorder", insetsBefore.bottom, insetsAfter.bottom)
        assertEquals("Right insets must not grow after reorder", insetsBefore.right, insetsAfter.right)
    }

    fun testRepeatedReorderDoesNotAccumulateInsets() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("r1"))
        val dir2 = Files.createDirectory(tempDir.resolve("r2"))
        val dir3 = Files.createDirectory(tempDir.resolve("r3"))
        val panel = createPanelWithTabs(dir1, dir2, dir3)

        val tab = panel.getTabAt(0)!!
        val insetsBefore = tab.insets

        // Reorder multiple times — insets must remain stable
        panel.reorderTab(0, 1)
        panel.reorderTab(1, 2)
        panel.reorderTab(2, 0)
        panel.reorderTab(0, 2)
        panel.reorderTab(1, 0)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val insetsAfter = tab.insets
        assertEquals("Top insets must not accumulate", insetsBefore.top, insetsAfter.top)
        assertEquals("Left insets must not accumulate", insetsBefore.left, insetsAfter.left)
        assertEquals("Bottom insets must not accumulate", insetsBefore.bottom, insetsAfter.bottom)
        assertEquals("Right insets must not accumulate", insetsBefore.right, insetsAfter.right)
    }

    fun testMoveTabToOtherPanelPreservesBorder() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("m1"))
        val dir2 = Files.createDirectory(tempDir.resolve("m2"))
        val left = createPanelWithTabs(dir1, dir2)
        val right = createPanel()
        left.otherPanel = right
        right.otherPanel = left

        val tab = left.getTabAt(0)!!
        val insetsBefore = tab.insets

        left.moveTabToOtherPanel(0, right, 0)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val insetsAfter = tab.insets
        assertEquals("Top insets must not grow after cross-panel move", insetsBefore.top, insetsAfter.top)
        assertEquals("Left insets must not grow after cross-panel move", insetsBefore.left, insetsAfter.left)
        assertEquals("Bottom insets must not grow after cross-panel move", insetsBefore.bottom, insetsAfter.bottom)
        assertEquals("Right insets must not grow after cross-panel move", insetsBefore.right, insetsAfter.right)
    }

    // --- Last position regression: reorder/move to end must work ---

    fun testReorderTabToLastPosition() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("l1"))
        val dir2 = Files.createDirectory(tempDir.resolve("l2"))
        val dir3 = Files.createDirectory(tempDir.resolve("l3"))
        val panel = createPanelWithTabs(dir1, dir2, dir3)

        // Tab order: [project, l1, l2, l3, +]
        // Reorder tab 0 to the last real position (index 4 = plusIndex)
        val state0 = panel.saveState()
        val firstPath = state0.tabs[0].path

        panel.reorderTab(0, 4) // plusIndex = 4
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val stateAfter = panel.saveState()
        assertEquals("Tab count should be unchanged", 4, stateAfter.tabs.size)
        assertEquals("First tab should now be at last position", firstPath, stateAfter.tabs[3].path)
    }

    fun testReorderTabToLastPositionPreservesContent() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("c1"))
        val dir2 = Files.createDirectory(tempDir.resolve("c2"))
        val panel = createPanelWithTabs(dir1, dir2)

        // [project, c1, c2, +] — move tab 0 to end
        val pathsBefore = panel.saveState().tabs.map { it.path }.toList()

        panel.reorderTab(0, 3) // plusIndex = 3
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val pathsAfter = panel.saveState().tabs.map { it.path }
        assertEquals("Tab count unchanged", 3, pathsAfter.size)
        // First tab moved to end: [c1, c2, project]
        assertEquals(pathsBefore[1], pathsAfter[0])
        assertEquals(pathsBefore[2], pathsAfter[1])
        assertEquals(pathsBefore[0], pathsAfter[2])
    }

    fun testMoveTabToLastPositionInOtherPanel() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("x1"))
        val dir2 = Files.createDirectory(tempDir.resolve("x2"))
        val left = createPanelWithTabs(dir1, dir2)
        val right = createPanel()
        left.otherPanel = right
        right.otherPanel = left

        val rightTabCount = right.saveState().tabs.size
        val movingPath = left.saveState().tabs[0].path

        // Move to plusIndex of right panel (last position)
        val rightPlusIndex = right.saveState().tabs.size // = 1 real tab, plusIndex = 1
        left.moveTabToOtherPanel(0, right, rightPlusIndex)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val rightState = right.saveState()
        assertEquals("Right panel should have one more tab", rightTabCount + 1, rightState.tabs.size)
        assertEquals("Moved tab should be at end", movingPath, rightState.tabs.last().path)
    }
}
