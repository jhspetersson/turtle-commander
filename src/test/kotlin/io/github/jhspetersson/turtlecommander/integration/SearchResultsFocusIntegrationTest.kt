package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.dialog.NamePatternMode
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.SearchResultsPanel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class SearchResultsFocusIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var parent: com.intellij.openapi.Disposable

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("turtle-test-search-focus-")
        parent = Disposer.newDisposable("turtle-test-search-focus")
    }

    override fun tearDown() {
        try {
            Disposer.dispose(parent)
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun criteria() = FileSearchCriteria(
        rootPath = tempDir,
        namePattern = "*",
        namePatternMode = NamePatternMode.GLOB,
        sizeFilter = null,
        creationDateFilter = null,
        modificationDateFilter = null,
    )

    private fun createPanel(): FileManagerPanel {
        val panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parent
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return panel
    }

    fun testOpeningSearchTabFocusesItsTableRightAway() {
        val panel = createPanel()
        val focused = mutableListOf<SearchResultsPanel>()
        panel.focusSearchPanel = { focused += it }

        val searchPanel = panel.openSearchTab(criteria())

        assertEquals(listOf(searchPanel), focused)
    }

    fun testArrivingResultsDoNotRequestFocus() {
        val searchPanel = SearchResultsPanel(project, criteria())
        Disposer.register(parent, searchPanel)
        var requests = 0
        searchPanel.focusTable = { requests++ }
        val stamp = FileTime.fromMillis(0)

        searchPanel.appendResults(listOf(FileEntry("a", tempDir.resolve("a"), false, 1, stamp, stamp, permissions = "")))

        assertEquals(1, searchPanel.table.rowCount)
        assertEquals(0, searchPanel.table.selectedRow)
        assertEquals(0, requests)
    }
}
