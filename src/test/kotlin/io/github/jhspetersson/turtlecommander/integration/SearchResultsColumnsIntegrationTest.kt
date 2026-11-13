package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.dialog.NamePatternMode
import io.github.jhspetersson.turtlecommander.settings.ColumnConfig
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.COLUMN_NAME_TO_MODEL_INDEX
import io.github.jhspetersson.turtlecommander.ui.FileTableModel
import io.github.jhspetersson.turtlecommander.ui.SearchResultsPanel
import java.nio.file.Files
import java.nio.file.Path

class SearchResultsColumnsIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var savedColumns: List<ColumnConfig>
    private val panels = mutableListOf<SearchResultsPanel>()

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("turtle-test-search-columns-")
        savedColumns = TurtleCommanderSettings.getInstance().state.columns.toList()
    }

    override fun tearDown() {
        try {
            panels.forEach { Disposer.dispose(it) }
            val columns = TurtleCommanderSettings.getInstance().state.columns
            columns.clear()
            columns.addAll(savedColumns)
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun createPanel(): SearchResultsPanel {
        val criteria = FileSearchCriteria(
            rootPath = tempDir,
            namePattern = "*",
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )
        return SearchResultsPanel(project, criteria).also { panels.add(it) }
    }

    private fun viewModelIndices(panel: SearchResultsPanel): List<Int> =
        (0 until panel.table.columnModel.columnCount).map { panel.table.columnModel.getColumn(it).modelIndex }

    private fun column(id: String, visible: Boolean) = ColumnConfig().apply {
        this.id = id
        this.visible = visible
    }

    fun testDefaultColumnsHideWhatTheSettingsHide() {
        TurtleCommanderSettings.getInstance().state.columns.clear()

        val expected = TurtleCommanderSettings.getInstance().getEffectiveColumns()
            .filter { it.visible }
            .map { COLUMN_NAME_TO_MODEL_INDEX.getValue(it.id) }

        assertEquals(expected, viewModelIndices(createPanel()))
        assertFalse(FileTableModel.COL_INODE in viewModelIndices(createPanel()))
    }

    fun testConfiguredColumnVisibilityAndOrderAreApplied() {
        val columns = TurtleCommanderSettings.getInstance().state.columns
        columns.clear()
        columns.addAll(
            listOf(
                column("Name", true),
                column("Date Modified", true),
                column("Size", true),
                column("Ext", false),
                column("Date Created", false),
                column("User", false),
                column("Permissions", false),
            ),
        )

        assertEquals(
            listOf(FileTableModel.COL_NAME, FileTableModel.COL_DATE, FileTableModel.COL_SIZE),
            viewModelIndices(createPanel()),
        )
    }
}
