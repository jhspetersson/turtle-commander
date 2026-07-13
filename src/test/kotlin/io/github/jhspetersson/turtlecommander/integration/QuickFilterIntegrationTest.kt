package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService.PanelState
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.ViewMode
import io.github.jhspetersson.turtlecommander.ui.setViewMode
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Tests for the quick filter (Ctrl-S) across view modes, in particular the tree view:
 * - the flat tree (show-all-nested-files) filters live like the table/list/thumbnail views
 * - the full-hierarchy tree refuses to open the filter bar instead of silently no-opping
 * - switching into the full-hierarchy tree drops an active filter
 * - switching into the flat tree keeps an active filter
 */
class QuickFilterIntegrationTest : BasePlatformTestCase() {

    private lateinit var projectPath: Path
    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        projectPath = Path.of(project.basePath!!)
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-filter-")
        Files.writeString(tempDir.resolve("alpha.txt"), "a")
        Files.writeString(tempDir.resolve("beta.txt"), "b")
        Files.writeString(tempDir.resolve("notes.md"), "n")
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

    private fun openTab(): FileTab {
        val panel = FileManagerPanel(
            project = project,
            initialPath = projectPath,
            otherPanelPathProvider = { projectPath },
        )
        panel.restoreState(PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        panel.openDirectoryInNewTab(tempDir)
        val tab = panel.getTabAt(1)!!
        waitForListing(tab, "alpha.txt")
        return tab
    }

    /** Navigation is coroutine-based; pump the EDT until the listing shows up. */
    private fun waitForListing(tab: FileTab, name: String) {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (name in tableNames(tab)) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for listing to contain $name")
    }

    private fun tableNames(tab: FileTab): List<String> =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it)?.name }

    private fun treeNames(tab: FileTab): List<String> =
        (0 until tab.treeRootNode.childCount).mapNotNull { i ->
            val node = tab.treeRootNode.getChildAt(i) as? DefaultMutableTreeNode
            (node?.userObject as? io.github.jhspetersson.turtlecommander.model.FileEntry)?.name
        }

    fun testFilterAppliesToFlatTreeInTreeView() {
        if (skipIfHeadless()) return
        val tab = openTab()
        tab.showAllNestedFiles = true
        tab.setViewMode(ViewMode.TREE)
        assertTrue("Unfiltered flat tree should show notes.md", "notes.md" in treeNames(tab))

        tab.setQuickFilterText("*.txt")

        assertTrue("Filter bar should be active in flat tree view", tab.isQuickFilterActive())
        val filtered = treeNames(tab)
        assertTrue("Flat tree should keep alpha.txt", "alpha.txt" in filtered)
        assertTrue("Flat tree should keep beta.txt", "beta.txt" in filtered)
        assertFalse("Flat tree should hide notes.md", "notes.md" in filtered)

        tab.setQuickFilterText("")
        assertTrue("Clearing the filter should restore notes.md", "notes.md" in treeNames(tab))
    }

    fun testFilterRefusedInFullHierarchyTreeView() {
        if (skipIfHeadless()) return
        val tab = openTab()
        tab.setViewMode(ViewMode.TREE)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        tab.setQuickFilterText("*.txt")

        assertFalse("Filter bar must refuse to open in the hierarchical tree", tab.isQuickFilterActive())
        assertTrue("Models must stay unfiltered after a refused activation", "notes.md" in tableNames(tab))
    }

    fun testSwitchingToFullTreeDropsActiveFilter() {
        if (skipIfHeadless()) return
        val tab = openTab()
        tab.setQuickFilterText("*.txt")
        assertTrue(tab.isQuickFilterActive())
        assertFalse("Table should be filtered before the switch", "notes.md" in tableNames(tab))

        tab.setViewMode(ViewMode.TREE)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertFalse("Switching to the hierarchical tree should drop the filter", tab.isQuickFilterActive())
        assertTrue("Models should be unfiltered again", "notes.md" in tableNames(tab))
    }

    fun testSwitchingToFlatTreeKeepsActiveFilter() {
        if (skipIfHeadless()) return
        val tab = openTab()
        tab.showAllNestedFiles = true
        tab.setQuickFilterText("*.txt")
        assertFalse("Table should be filtered before the switch", "notes.md" in tableNames(tab))

        tab.setViewMode(ViewMode.TREE)

        assertTrue("Filter should survive switching to the flat tree", tab.isQuickFilterActive())
        val names = treeNames(tab)
        assertTrue("Flat tree should keep alpha.txt", "alpha.txt" in names)
        assertFalse("Flat tree should respect the active filter", "notes.md" in names)
    }
}
