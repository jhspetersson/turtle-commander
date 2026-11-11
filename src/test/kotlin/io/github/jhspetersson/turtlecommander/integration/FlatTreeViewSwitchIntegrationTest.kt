package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService.PanelState
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.ViewMode
import io.github.jhspetersson.turtlecommander.ui.setViewMode
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class FlatTreeViewSwitchIntegrationTest : BasePlatformTestCase() {

    private lateinit var projectPath: Path
    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        projectPath = Path.of(project.basePath!!)
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-flat-tree-")
        Files.writeString(tempDir.resolve("top.txt"), "t")
        Files.writeString(Files.createDirectory(tempDir.resolve("sub")).resolve("deep.txt"), "d")
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
    }

    override fun tearDown() {
        try {
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun tableNames(tab: FileTab): List<String> =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it)?.name }

    private fun pump(times: Int = 20) {
        repeat(times) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(100)
        }
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        repeat(50) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for $description")
    }

    fun testLeavingTheFlatTreeKeepsTheTabInItsDirectory() {
        val panel = FileManagerPanel(project = project, initialPath = projectPath, otherPanelPathProvider = { projectPath })
        panel.restoreState(PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        panel.openDirectoryInNewTab(tempDir)
        val tab = panel.getTabAt(1)!!
        waitFor("listing") { "top.txt" in tableNames(tab) }
        tab.setShowAllNestedFiles(true)
        waitFor("nested listing") { "deep.txt" in tableNames(tab) }
        tab.setViewMode(ViewMode.TREE)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val deepNode = (0 until tab.treeRootNode.childCount)
            .map { tab.treeRootNode.getChildAt(it) as DefaultMutableTreeNode }
            .first { (it.userObject as? FileEntry)?.name == "deep.txt" }
        tab.tree.selectionPath = TreePath(arrayOf(tab.treeRootNode, deepNode))

        tab.setViewMode(ViewMode.TABLE)
        pump()

        assertEquals(tempDir, tab.currentPath)
        assertTrue("deep.txt" in tableNames(tab))
    }
}
