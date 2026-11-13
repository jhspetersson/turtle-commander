package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.dialog.NamePatternMode
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JLabel
import javax.swing.JPanel

class SearchTabTitleIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var parent: Disposable
    private lateinit var panel: FileManagerPanel

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("turtle-test-search-title-")
        parent = Disposer.newDisposable("turtle-test-search-title")
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parent
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            Disposer.dispose(parent)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun criteria(pattern: String) = FileSearchCriteria(
        rootPath = tempDir,
        namePattern = pattern,
        namePatternMode = NamePatternMode.GLOB,
        sizeFilter = null,
        creationDateFilter = null,
        modificationDateFilter = null,
    )

    private fun headerLabelText(index: Int): String =
        ((panel.tabHeaderAt(index) as JPanel).getComponent(0) as JLabel).text

    fun testEditingTheSearchRenamesTheTab() {
        val searchPanel = panel.openSearchTab(criteria("*.txt"))
        val index = panel.getActiveTabIndex()
        assertEquals("Search: *.txt", panel.tabTitleAt(index))

        searchPanel.updateCriteria(criteria("*.kt"))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Search: *.kt", panel.tabTitleAt(index))
        assertEquals("Search: *.kt", headerLabelText(index))
    }
}
