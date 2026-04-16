package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests for state persistence: favorites, column state, panel state save/restore.
 */
class StateAndFavoritesIntegrationTest : BasePlatformTestCase() {

    private lateinit var stateService: FileManagerStateService

    override fun setUp() {
        super.setUp()
        stateService = project.service()
        // Reset state to defaults so tests don't pollute each other
        stateService.loadState(FileManagerStateService.FileManagerState())
        // Ensure default view mode is TABLE (may be polluted by other tests)
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
    }

    // --- Favorites ---

    fun testAddFavorite() {
        stateService.setFavoriteEntries(emptyList())

        stateService.addFavorite("/some/path")

        val favorites = stateService.getFavorites()
        assertTrue("Should contain added path", "/some/path" in favorites)
    }

    fun testAddFavoriteWithColor() {
        stateService.setFavoriteEntries(emptyList())

        stateService.addFavorite("/colored/path", "#FF0000")

        val entries = stateService.getFavoriteEntries()
        val entry = entries.find { it.path == "/colored/path" }
        assertNotNull("Should find favorite entry", entry)
        assertEquals("#FF0000", entry!!.color)
    }

    fun testRemoveFavorite() {
        stateService.setFavoriteEntries(emptyList())
        stateService.addFavorite("/to/remove")
        stateService.addFavorite("/to/keep")

        stateService.removeFavorite("/to/remove")

        val favorites = stateService.getFavorites()
        assertFalse("Removed path should be gone", "/to/remove" in favorites)
        assertTrue("Other path should remain", "/to/keep" in favorites)
    }

    fun testRemoveNonExistentFavoriteIsNoOp() {
        stateService.setFavoriteEntries(emptyList())
        stateService.addFavorite("/existing")

        stateService.removeFavorite("/nonexistent")

        assertEquals(1, stateService.getFavorites().size)
    }

    fun testDuplicateFavoriteNotAdded() {
        stateService.setFavoriteEntries(emptyList())
        stateService.addFavorite("/path")
        stateService.addFavorite("/path")

        assertEquals("Duplicate should not be added", 1, stateService.getFavorites().size)
    }

    fun testSetFavoriteEntries() {
        val entries = listOf(
            FileManagerStateService.FavoriteEntry().apply { path = "/a"; color = "#111111" },
            FileManagerStateService.FavoriteEntry().apply { path = "/b"; color = "#222222" },
        )

        stateService.setFavoriteEntries(entries)

        val result = stateService.getFavoriteEntries()
        assertEquals(2, result.size)
        assertEquals("/a", result[0].path)
        assertEquals("#111111", result[0].color)
        assertEquals("/b", result[1].path)
    }

    fun testGetFavoritesReturnsPaths() {
        stateService.setFavoriteEntries(emptyList())
        stateService.addFavorite("/first")
        stateService.addFavorite("/second")

        val paths = stateService.getFavorites()

        assertEquals(2, paths.size)
        assertTrue("/first" in paths)
        assertTrue("/second" in paths)
    }

    // --- Column state ---

    fun testColumnStateSaveAndRetrieve() {
        val path = "/test/dir"
        val widths = listOf(200, 80, 100, 150, 150, 80)
        val order = listOf(0, 1, 2, 3, 4, 5)

        stateService.putColumnState(path, widths, order)

        val entry = stateService.getColumnState(path)
        assertNotNull("Column state should be retrievable", entry)

        val parsedWidths = stateService.parseWidths(entry!!)
        val parsedOrder = stateService.parseOrder(entry)
        assertEquals(widths, parsedWidths)
        assertEquals(order, parsedOrder)
    }

    fun testColumnStateOverwrite() {
        stateService.putColumnState("/dir", listOf(100, 200), listOf(0, 1))
        stateService.putColumnState("/dir", listOf(300, 400), listOf(1, 0))

        val entry = stateService.getColumnState("/dir")
        assertNotNull(entry)
        assertEquals(listOf(300, 400), stateService.parseWidths(entry!!))
        assertEquals(listOf(1, 0), stateService.parseOrder(entry))
    }

    fun testColumnStateForUnknownPathReturnsNull() {
        val entry = stateService.getColumnState("/nonexistent/path")
        assertNull("Unknown path should return null", entry)
    }

    // --- Panel state ---

    fun testPanelStateDefaults() {
        // Test default values of a fresh FileManagerState (not via getState(),
        // which may be contaminated by registered UI panels from other tests)
        val state = FileManagerStateService.FileManagerState()
        assertNotNull(state.leftPanel)
        assertNotNull(state.rightPanel)
        assertTrue("Default tabs should be empty", state.leftPanel.tabs.isEmpty())
        assertEquals(0, state.leftPanel.selectedTabIndex)
    }

    fun testSplitProportion() {
        // Test directly on FileManagerState to avoid getState() overwriting from a registered splitter
        val state = FileManagerStateService.FileManagerState()
        state.splitProportion = 0.3f
        assertEquals(0.3f, state.splitProportion)
    }

    // --- Panel state save/restore via FileManagerPanel ---

    fun testPanelSaveAndRestoreState() {
        if (GraphicsEnvironment.isHeadless()) return
        val projectPath = Path.of(project.basePath!!)

        val panel = io.github.jhspetersson.turtlecommander.ui.FileManagerPanel(
            project = project,
            initialPath = projectPath,
            otherPanelPathProvider = { projectPath },
        )
        panel.restoreState(stateService.state.leftPanel, stateService)
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val savedState = panel.saveState()

        assertTrue("Saved state should have at least one tab", savedState.tabs.isNotEmpty())
        assertTrue("Tabs should contain the initial path", savedState.tabs.any {
            try { Files.isSameFile(Path.of(it.path), projectPath) } catch (_: Exception) { false }
        })
        assertEquals(0, savedState.selectedTabIndex)
    }

    fun testPanelRestoreMultipleTabs() {
        if (GraphicsEnvironment.isHeadless()) return
        // Use real temp dirs that definitely exist on the file system
        val tempDir1 = Files.createTempDirectory("turtle-tab1-")
        val tempDir2 = Files.createTempDirectory("turtle-tab2-")

        try {
            val panelState = FileManagerStateService.PanelState().apply {
                tabs.add(FileManagerStateService.TabState(path = tempDir1.toString()))
                tabs.add(FileManagerStateService.TabState(path = tempDir2.toString()))
                selectedTabIndex = 1
            }

            val panel = io.github.jhspetersson.turtlecommander.ui.FileManagerPanel(
                project = project,
                initialPath = tempDir1,
                otherPanelPathProvider = { tempDir1 },
            )
            panel.restoreState(panelState, stateService)
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            val saved = panel.saveState()
            assertEquals("Should restore 2 tabs", 2, saved.tabs.size)
        } finally {
            tempDir1.toFile().deleteRecursively()
            tempDir2.toFile().deleteRecursively()
        }
    }

    fun testPanelRestoreWithInvalidPathFallsBack() {
        if (GraphicsEnvironment.isHeadless()) return
        val projectPath = Path.of(project.basePath!!)

        val panelState = FileManagerStateService.PanelState().apply {
            tabs.add(FileManagerStateService.TabState(path = "/nonexistent/path/that/does/not/exist"))
        }

        val panel = io.github.jhspetersson.turtlecommander.ui.FileManagerPanel(
            project = project,
            initialPath = projectPath,
            otherPanelPathProvider = { projectPath },
        )
        panel.restoreState(panelState, stateService)
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        // Invalid path is skipped, should fall back to initialPath
        val tab = panel.getActiveTab()
        assertNotNull("Should still have an active tab after invalid path", tab)
    }

    fun testPanelRestoreViewModes() {
        if (GraphicsEnvironment.isHeadless()) return
        val tempDir = Files.createTempDirectory("turtle-viewmode-")
        try {
            val panelState = FileManagerStateService.PanelState().apply {
                tabs.add(FileManagerStateService.TabState(path = tempDir.toString(), viewMode = "LIST"))
            }

            val panel = io.github.jhspetersson.turtlecommander.ui.FileManagerPanel(
                project = project,
                initialPath = tempDir,
                otherPanelPathProvider = { tempDir },
            )
            panel.restoreState(panelState, stateService)
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            // Verify view mode was saved correctly in the round-trip
            val saved = panel.saveState()
            assertEquals(
                "View mode should be persisted in state",
                "LIST",
                saved.tabs.firstOrNull()?.viewMode
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    fun testPanelRestoreTableViewModeWithNonTableDefault() {
        if (GraphicsEnvironment.isHeadless()) return
        // Default view mode differs from the saved tab's view mode. Tab was explicitly TABLE;
        // it must restore as TABLE, not fall back to the LIST default.
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "LIST"
        val tempDir = Files.createTempDirectory("turtle-viewmode-table-")
        try {
            val panelState = FileManagerStateService.PanelState().apply {
                tabs.add(FileManagerStateService.TabState(path = tempDir.toString(), viewMode = "TABLE"))
            }

            val panel = io.github.jhspetersson.turtlecommander.ui.FileManagerPanel(
                project = project,
                initialPath = tempDir,
                otherPanelPathProvider = { tempDir },
            )
            panel.restoreState(panelState, stateService)
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            val activeTab = panel.getActiveTab()
            assertNotNull("Should have an active tab after restore", activeTab)
            assertEquals(
                "Tab should be restored as TABLE even when default is LIST",
                io.github.jhspetersson.turtlecommander.ui.ViewMode.TABLE,
                activeTab!!.viewMode
            )

            val saved = panel.saveState()
            assertEquals(
                "View mode should round-trip as TABLE",
                "TABLE",
                saved.tabs.firstOrNull()?.viewMode
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
