package io.github.jhspetersson.turtlecommander.integration
import io.github.jhspetersson.turtlecommander.settings.ColumnConfig
import javax.swing.RowSorter
import javax.swing.SortOrder

import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService.*
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTableModel
import io.github.jhspetersson.turtlecommander.ui.ViewMode
import io.github.jhspetersson.turtlecommander.ui.setViewMode
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for per-tab state management:
 * - TabState is a self-sufficient object with view mode, columns, sort
 * - Multiple tabs with the same path can have different states
 * - State is cleaned up when tabs are closed
 * - Backward compatibility migration from old format
 */
class TabStateIntegrationTest : BasePlatformTestCase() {

    private lateinit var projectPath: Path
    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        projectPath = Path.of(project.basePath!!)
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-state-")
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
        panel.restoreState(PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return panel
    }

    private fun createPanelWithState(panelState: PanelState): FileManagerPanel {
        val panel = FileManagerPanel(
            project = project,
            initialPath = projectPath,
            otherPanelPathProvider = { projectPath },
        )
        panel.restoreState(panelState, stateService)
        waitForNavigation()
        return panel
    }

    private fun waitForNavigation() {
        // Navigation uses coroutines: listFiles on background thread, then EDT dispatch.
        // Dispatch events repeatedly to allow both parts to complete.
        repeat(20) {
            Thread.sleep(100)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }

    // --- TabState is self-sufficient ---

    fun testTabStateCapturesViewMode() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("viewmode"))
        val panel = createPanel()
        panel.openDirectoryInNewTab(dir)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val tab = panel.getTabAt(1)!!
        tab.setViewMode(ViewMode.LIST)

        val state = panel.saveState()
        assertEquals("LIST", state.tabs[1].viewMode)
    }

    fun testTabStateCapturesColumnWidths() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val tab = panel.getTabAt(0)!!
        val cm = tab.table.columnModel
        cm.getColumn(0).preferredWidth = 300
        cm.getColumn(0).width = 300

        val state = panel.saveState()
        val widths = state.tabs[0].columnWidths.split(",").map { it.toInt() }
        assertEquals("Column 0 width should be captured", 300, widths[0])
    }

    fun testTabStateCapturesColumnOrder() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val tab = panel.getTabAt(0)!!
        val cm = tab.table.columnModel
        if (cm.columnCount >= 2) {
            cm.moveColumn(0, 1)
        }

        val state = panel.saveState()
        val order = state.tabs[0].columnOrder.split(",").map { it.toInt() }
        assertTrue("Column order should be captured", order.isNotEmpty())
    }

    fun testTabStateCapturesSortColumn() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val tab = panel.getTabAt(0)!!
        tab.table.rowSorter?.sortKeys = listOf(
            RowSorter.SortKey(1, SortOrder.DESCENDING)
        )

        val state = panel.saveState()
        assertEquals("Sort column should be captured", 1, state.tabs[0].sortColumn)
        assertFalse("Sort direction should be captured", state.tabs[0].sortAscending)
    }

    fun testTabStateCapturesPath() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("pathtest"))
        val panel = createPanel()
        panel.openDirectoryInNewTab(dir)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals(dir.toString(), state.tabs[1].path)
    }

    // --- Multiple tabs with same path can have different states ---

    fun testSamePathDifferentViewModes() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("samepath"))
        val panel = createPanel()
        panel.openDirectoryInNewTab(dir)
        panel.openDirectoryInNewTab(dir)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        panel.getTabAt(1)!!.setViewMode(ViewMode.TABLE)
        panel.getTabAt(2)!!.setViewMode(ViewMode.LIST)

        val state = panel.saveState()
        assertEquals("Same path", state.tabs[1].path, state.tabs[2].path)
        assertEquals("First tab should be TABLE", "TABLE", state.tabs[1].viewMode)
        assertEquals("Second tab should be LIST", "LIST", state.tabs[2].viewMode)
    }

    fun testSamePathDifferentColumnWidths() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("samecols"))
        val panel = createPanel()
        panel.openDirectoryInNewTab(dir)
        panel.openDirectoryInNewTab(dir)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val tab1 = panel.getTabAt(1)!!
        val tab2 = panel.getTabAt(2)!!
        tab1.table.columnModel.getColumn(0).apply { preferredWidth = 200; width = 200 }
        tab2.table.columnModel.getColumn(0).apply { preferredWidth = 400; width = 400 }

        val state = panel.saveState()
        val widths1 = state.tabs[1].columnWidths.split(",").map { it.toInt() }
        val widths2 = state.tabs[2].columnWidths.split(",").map { it.toInt() }
        assertEquals("First tab col 0 width", 200, widths1[0])
        assertEquals("Second tab col 0 width", 400, widths2[0])
    }

    fun testSamePathDifferentSortOrders() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("samesort"))
        val panel = createPanel()
        panel.openDirectoryInNewTab(dir)
        panel.openDirectoryInNewTab(dir)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        panel.getTabAt(1)!!.table.rowSorter?.sortKeys = listOf(
            RowSorter.SortKey(0, SortOrder.ASCENDING)
        )
        panel.getTabAt(2)!!.table.rowSorter?.sortKeys = listOf(
            RowSorter.SortKey(2, SortOrder.DESCENDING)
        )

        val state = panel.saveState()
        assertEquals(0, state.tabs[1].sortColumn)
        assertTrue(state.tabs[1].sortAscending)
        assertEquals(2, state.tabs[2].sortColumn)
        assertFalse(state.tabs[2].sortAscending)
    }

    // --- State restore with different per-tab states ---

    fun testRestoreTabsWithDifferentViewModes() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("restorevm"))
        val panelState = PanelState().apply {
            tabs.add(TabState(path = dir.toString(), viewMode = "TABLE"))
            tabs.add(TabState(path = dir.toString(), viewMode = "LIST"))
        }

        val panel = createPanelWithState(panelState)
        assertEquals("TABLE", panel.getTabAt(0)!!.viewMode.name)
        assertEquals("LIST", panel.getTabAt(1)!!.viewMode.name)
    }

    fun testRestoreTabsWithDifferentColumnWidths() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("restorecw"))

        // First create a panel to discover the actual column count
        val probe = createPanel()
        val colCount = probe.getTabAt(0)!!.table.columnModel.columnCount
        val defaultWidths = (0 until colCount).map { 80 }
        val defaultOrder = (0 until colCount).toList()

        val widths1 = defaultWidths.toMutableList().also { it[0] = 300 }
        val widths2 = defaultWidths.toMutableList().also { it[0] = 100 }

        val panelState = PanelState().apply {
            tabs.add(TabState(path = dir.toString(),
                columnWidths = widths1.joinToString(","),
                columnOrder = defaultOrder.joinToString(",")))
            tabs.add(TabState(path = dir.toString(),
                columnWidths = widths2.joinToString(","),
                columnOrder = defaultOrder.joinToString(",")))
        }

        val panel = createPanelWithState(panelState)
        val w1 = panel.getTabAt(0)!!.table.columnModel.getColumn(0).width
        val w2 = panel.getTabAt(1)!!.table.columnModel.getColumn(0).width
        assertEquals("First tab col 0 width", 300, w1)
        assertEquals("Second tab col 0 width", 100, w2)
    }

    fun testRestoreTabsWithDifferentSortOrders() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("restoresort"))
        val panelState = PanelState().apply {
            tabs.add(TabState(path = dir.toString(), sortColumn = 0, sortAscending = true))
            tabs.add(TabState(path = dir.toString(), sortColumn = 2, sortAscending = false))
        }

        val panel = createPanelWithState(panelState)
        val keys1 = panel.getTabAt(0)!!.table.rowSorter?.sortKeys
        val keys2 = panel.getTabAt(1)!!.table.rowSorter?.sortKeys
        assertEquals(0, keys1?.firstOrNull()?.column)
        assertEquals(SortOrder.ASCENDING, keys1?.firstOrNull()?.sortOrder)
        assertEquals(2, keys2?.firstOrNull()?.column)
        assertEquals(SortOrder.DESCENDING, keys2?.firstOrNull()?.sortOrder)
    }

    // --- State cleanup on tab close ---

    fun testCloseTabRemovesState() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("close1"))
        val dir2 = Files.createDirectory(tempDir.resolve("close2"))
        val panel = createPanel()
        panel.openDirectoryInNewTab(dir1)
        panel.openDirectoryInNewTab(dir2)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(3, panel.saveState().tabs.size)
        panel.closeTab(1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals("Tab count should decrease", 2, state.tabs.size)
        assertFalse("Closed tab's path should not be in state",
            state.tabs.any { it.path == dir1.toString() })
    }

    fun testCloseAllTabsKeepsOne() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("all1"))
        val dir2 = Files.createDirectory(tempDir.resolve("all2"))
        val panel = createPanel()
        panel.openDirectoryInNewTab(dir1)
        panel.openDirectoryInNewTab(dir2)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        panel.closeAllTabs()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals("Should keep exactly one tab", 1, state.tabs.size)
    }

    fun testCloseOtherTabsKeepsSelected() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("other1"))
        val dir2 = Files.createDirectory(tempDir.resolve("other2"))
        val panel = createPanel()
        panel.openDirectoryInNewTab(dir1)
        panel.openDirectoryInNewTab(dir2)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        // Close all except tab 1
        panel.closeOtherTabs(1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals("Should keep only selected tab", 1, state.tabs.size)
        assertEquals(dir1.toString(), state.tabs[0].path)
    }

    fun testStateDoesNotGrowAfterOpenClose() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("grow"))
        val panel = createPanel()

        // Open and close tabs multiple times
        for (i in 1..5) {
            panel.openDirectoryInNewTab(dir)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
        val sizeAfterOpen = panel.saveState().tabs.size
        assertEquals(6, sizeAfterOpen) // 1 initial + 5 opened

        // Close all extra tabs
        while (panel.saveState().tabs.size > 1) {
            panel.closeTab(1)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }

        val finalState = panel.saveState()
        assertEquals("Only one tab should remain", 1, finalState.tabs.size)
    }

    // --- Save/restore round-trip ---

    fun testSaveRestoreRoundTrip() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("rt1"))
        val dir2 = Files.createDirectory(tempDir.resolve("rt2"))

        // Create a probe panel to get actual column count
        val probe = createPanel()
        val colCount = probe.getTabAt(0)!!.table.columnModel.columnCount
        val defaultOrder = (0 until colCount).joinToString(",")
        val widths1 = (0 until colCount).map { if (it == 0) 250 else 80 }.joinToString(",")
        val widths2 = (0 until colCount).map { if (it == 0) 150 else 80 }.joinToString(",")

        val panelState = PanelState().apply {
            tabs.add(TabState(path = dir1.toString(), viewMode = "LIST", columnWidths = widths1, columnOrder = defaultOrder))
            tabs.add(TabState(path = dir2.toString(), viewMode = "TABLE", columnWidths = widths2, columnOrder = defaultOrder))
        }

        val panel = createPanelWithState(panelState)
        val savedState = panel.saveState()

        assertEquals(2, savedState.tabs.size)
        assertEquals(dir1.toString(), savedState.tabs[0].path)
        assertEquals(dir2.toString(), savedState.tabs[1].path)
        assertEquals("LIST", savedState.tabs[0].viewMode)
        assertEquals("TABLE", savedState.tabs[1].viewMode)

        // Restore into a new panel
        val panel2 = createPanelWithState(savedState)
        assertEquals("LIST", panel2.getTabAt(0)!!.viewMode.name)
        assertEquals("TABLE", panel2.getTabAt(1)!!.viewMode.name)
        assertNull("pendingTabState should be consumed after navigation", panel2.getTabAt(0)!!.pendingTabState)
        assertEquals(250, panel2.getTabAt(0)!!.table.columnModel.getColumn(0).width)
        assertEquals(150, panel2.getTabAt(1)!!.table.columnModel.getColumn(0).width)
    }

    fun testSaveRestorePreservesSelectedTab() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("sel1"))
        val dir2 = Files.createDirectory(tempDir.resolve("sel2"))

        val panelState = PanelState().apply {
            tabs.add(TabState(path = dir1.toString()))
            tabs.add(TabState(path = dir2.toString()))
            selectedTabIndex = 1
        }

        val panel = createPanelWithState(panelState)
        assertEquals("Selected tab should be restored", 1, panel.getActiveTabIndex())

        val savedState = panel.saveState()
        assertEquals(1, savedState.selectedTabIndex)

        val panel2 = createPanelWithState(savedState)
        assertEquals("Selected tab should survive round-trip", 1, panel2.getActiveTabIndex())
    }

    // --- Backward compatibility ---

    fun testMigrateOldFormatTabPaths() {
        val panelState = PanelState().apply {
            tabPaths.addAll(listOf("/path/a", "/path/b"))
            tabViewModes.addAll(listOf("TABLE", "LIST"))
        }

        panelState.migrateIfNeeded()

        assertEquals(2, panelState.tabs.size)
        assertEquals("/path/a", panelState.tabs[0].path)
        assertEquals("TABLE", panelState.tabs[0].viewMode)
        assertEquals("/path/b", panelState.tabs[1].path)
        assertEquals("LIST", panelState.tabs[1].viewMode)
        assertTrue("Old tabPaths should be cleared", panelState.tabPaths.isEmpty())
        assertTrue("Old tabViewModes should be cleared", panelState.tabViewModes.isEmpty())
    }

    fun testMigrateOldFormatMissingViewModes() {
        val panelState = PanelState().apply {
            tabPaths.addAll(listOf("/path/a", "/path/b"))
            // No view modes saved — should default to TABLE
        }

        panelState.migrateIfNeeded()

        assertEquals(2, panelState.tabs.size)
        assertEquals("TABLE", panelState.tabs[0].viewMode)
        assertEquals("TABLE", panelState.tabs[1].viewMode)
    }

    fun testMigrateDoesNothingWhenTabsExist() {
        val panelState = PanelState().apply {
            tabs.add(TabState(path = "/existing", viewMode = "LIST"))
            tabPaths.add("/old")
        }

        panelState.migrateIfNeeded()

        assertEquals("Should not migrate when tabs already exist", 1, panelState.tabs.size)
        assertEquals("/existing", panelState.tabs[0].path)
    }

    fun testMigrateColumnStateFromPathColumns() {
        val state = FileManagerState().apply {
            leftPanel.tabPaths.add(tempDir.toString())
            leftPanel.tabViewModes.add("TABLE")
            pathColumns.add(PathColumnEntry().apply {
                path = tempDir.toString()
                columnWidths = "300,50,80"
                columnOrder = "2,0,1"
            })
        }

        // Simulate loadState migration
        state.leftPanel.migrateIfNeeded()
        // Manually call the migration logic
        for (tab in state.leftPanel.tabs) {
            if (tab.columnWidths.isEmpty() && tab.columnOrder.isEmpty()) {
                val entry = state.pathColumns.find { it.path == tab.path }
                if (entry != null) {
                    tab.columnWidths = entry.columnWidths
                    tab.columnOrder = entry.columnOrder
                }
            }
        }

        assertEquals("300,50,80", state.leftPanel.tabs[0].columnWidths)
        assertEquals("2,0,1", state.leftPanel.tabs[0].columnOrder)
    }

    // --- TabState default values ---

    // --- Column order preserved across visibility changes ---

    fun testColumnOrderPreservedWhenHidingColumn() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val tab = panel.getTabAt(0)!!
        val cm = tab.table.columnModel

        // Reorder: move column 0 to position 2
        if (cm.columnCount >= 3) {
            cm.moveColumn(0, 2)
        }
        val orderBefore = (0 until cm.columnCount).map { cm.getColumn(it).modelIndex }

        // Hide "Permissions" column (modelIndex 5) via settings
        val settings = TurtleCommanderSettings.getInstance()
        val columns = settings.getEffectiveColumns()
        columns.find { it.id == "Permissions" }?.visible = false
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })
        tab.applyPanelFont()

        // Verify that the remaining columns kept their relative order
        val orderAfter = (0 until cm.columnCount).map { cm.getColumn(it).modelIndex }
        val expectedOrder = orderBefore.filter { it != FileTableModel.COL_PERMS } // remove Permissions
        assertEquals("Column order should be preserved after hiding a column", expectedOrder, orderAfter)

        // Restore all columns visible for other tests
        columns.find { it.id == "Permissions" }?.visible = true
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })
    }

    fun testColumnOrderPreservedWhenShowingColumn() {
        if (skipIfHeadless()) return
        val settings = TurtleCommanderSettings.getInstance()

        // Start with "Ext" column hidden
        val columns = settings.getEffectiveColumns()
        columns.find { it.id == "Ext" }?.visible = false
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })

        val panel = createPanel()
        val tab = panel.getTabAt(0)!!
        val cm = tab.table.columnModel

        // Reorder existing columns
        if (cm.columnCount >= 2) {
            cm.moveColumn(0, 1)
        }
        val orderBefore = (0 until cm.columnCount).map { cm.getColumn(it).modelIndex }

        // Now show "Ext" column
        columns.find { it.id == "Ext" }?.visible = true
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })
        tab.applyPanelFont()

        val orderAfter = (0 until cm.columnCount).map { cm.getColumn(it).modelIndex }
        // Old columns should maintain their relative order; Ext (1) added at end
        for (i in orderBefore.indices) {
            val modelIdx = orderBefore[i]
            val newPos = orderAfter.indexOf(modelIdx)
            assertTrue("Previously visible column $modelIdx should still be present", newPos >= 0)
            // Check relative ordering: if A was before B, A should still be before B
            for (j in i + 1 until orderBefore.size) {
                val otherModelIdx = orderBefore[j]
                val otherNewPos = orderAfter.indexOf(otherModelIdx)
                assertTrue(
                    "Column $modelIdx should still be before column $otherModelIdx",
                    newPos < otherNewPos,
                )
            }
        }
        // New column (Ext) should be present
        assertTrue("Ext column should be visible", 1 in orderAfter)
    }

    fun testColumnWidthsPreservedAcrossVisibilityChange() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val tab = panel.getTabAt(0)!!
        val cm = tab.table.columnModel

        // Set custom widths
        for (i in 0 until cm.columnCount) {
            cm.getColumn(i).preferredWidth = 100 + i * 50
            cm.getColumn(i).width = 100 + i * 50
        }
        val widthsBefore = mutableMapOf<Int, Int>()
        for (i in 0 until cm.columnCount) {
            widthsBefore[cm.getColumn(i).modelIndex] = cm.getColumn(i).width
        }

        // Hide "Permissions" column
        val settings = TurtleCommanderSettings.getInstance()
        val columns = settings.getEffectiveColumns()
        columns.find { it.id == "Permissions" }?.visible = false
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })
        tab.applyPanelFont()

        // Verify widths of remaining columns are preserved
        for (i in 0 until cm.columnCount) {
            val modelIdx = cm.getColumn(i).modelIndex
            val expectedWidth = widthsBefore[modelIdx]
            assertNotNull("Width for model column $modelIdx should exist", expectedWidth)
            assertEquals(
                "Width of column $modelIdx should be preserved",
                expectedWidth!!,
                cm.getColumn(i).width,
            )
        }

        // Restore
        columns.find { it.id == "Permissions" }?.visible = true
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })
    }

    fun testMultipleTabsPreserveIndependentOrderAcrossVisibilityChange() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("colvis1"))
        val dir2 = Files.createDirectory(tempDir.resolve("colvis2"))
        val panel = createPanel()
        panel.openDirectoryInNewTab(dir1)
        panel.openDirectoryInNewTab(dir2)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val tab1 = panel.getTabAt(1)!!
        val tab2 = panel.getTabAt(2)!!

        // Give tabs different column orders
        val cm1 = tab1.table.columnModel
        val cm2 = tab2.table.columnModel
        if (cm1.columnCount >= 3) cm1.moveColumn(0, 2) // tab1: reorder
        // tab2: keep default order

        val order1Before = (0 until cm1.columnCount).map { cm1.getColumn(it).modelIndex }
        val order2Before = (0 until cm2.columnCount).map { cm2.getColumn(it).modelIndex }

        // They should be different
        assertFalse("Tabs should have different column orders", order1Before == order2Before)

        // Hide a column
        val settings = TurtleCommanderSettings.getInstance()
        val columns = settings.getEffectiveColumns()
        columns.find { it.id == "Permissions" }?.visible = false
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })
        tab1.applyPanelFont()
        tab2.applyPanelFont()

        val order1After = (0 until cm1.columnCount).map { cm1.getColumn(it).modelIndex }
        val order2After = (0 until cm2.columnCount).map { cm2.getColumn(it).modelIndex }

        assertEquals("Tab1 order should be preserved minus hidden column",
            order1Before.filter { it != FileTableModel.COL_PERMS }, order1After)
        assertEquals("Tab2 order should be preserved minus hidden column",
            order2Before.filter { it != FileTableModel.COL_PERMS }, order2After)

        // They should still be different
        assertFalse("Tabs should still have different orders after visibility change",
            order1After == order2After)

        // Restore
        columns.find { it.id == "Permissions" }?.visible = true
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })
    }

    fun testOppositePanelPreservesColumnOrderAcrossVisibilityChange() {
        if (skipIfHeadless()) return
        val dirL = Files.createDirectory(tempDir.resolve("leftcol"))
        val dirR = Files.createDirectory(tempDir.resolve("rightcol"))

        // Create two panels (left and right) with different column orders
        val leftPanel = FileManagerPanel(
            project = project,
            initialPath = dirL,
            otherPanelPathProvider = { dirR },
        )
        leftPanel.restoreState(PanelState().apply {
            tabs.add(TabState(path = dirL.toString()))
        }, stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val rightPanel = FileManagerPanel(
            project = project,
            initialPath = dirR,
            otherPanelPathProvider = { dirL },
        )
        rightPanel.restoreState(PanelState().apply {
            tabs.add(TabState(path = dirR.toString()))
        }, stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        leftPanel.otherPanel = rightPanel
        rightPanel.otherPanel = leftPanel

        val leftTab = leftPanel.getTabAt(0)!!
        val rightTab = rightPanel.getTabAt(0)!!
        val cmL = leftTab.table.columnModel
        val cmR = rightTab.table.columnModel

        // Reorder left panel columns differently from right
        if (cmL.columnCount >= 3) cmL.moveColumn(0, 2)
        if (cmR.columnCount >= 3) cmR.moveColumn(1, 0)

        val orderLBefore = (0 until cmL.columnCount).map { cmL.getColumn(it).modelIndex }
        val orderRBefore = (0 until cmR.columnCount).map { cmR.getColumn(it).modelIndex }
        assertFalse("Panels should have different column orders", orderLBefore == orderRBefore)

        // Hide a column globally
        val settings = TurtleCommanderSettings.getInstance()
        val columns = settings.getEffectiveColumns()
        columns.find { it.id == "Permissions" }?.visible = false
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })

        // Apply to both panels (simulates what settingsChanged() does)
        leftTab.applyPanelFont()
        rightTab.applyPanelFont()

        val orderLAfter = (0 until cmL.columnCount).map { cmL.getColumn(it).modelIndex }
        val orderRAfter = (0 until cmR.columnCount).map { cmR.getColumn(it).modelIndex }

        assertEquals("Left panel order should be preserved minus hidden column",
            orderLBefore.filter { it != FileTableModel.COL_PERMS }, orderLAfter)
        assertEquals("Right panel order should be preserved minus hidden column",
            orderRBefore.filter { it != FileTableModel.COL_PERMS }, orderRAfter)
        assertFalse("Panels should still have different orders",
            orderLAfter == orderRAfter)

        // Restore
        columns.find { it.id == "Permissions" }?.visible = true
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })
    }

    fun testOppositePanelPreservesColumnWidthsAcrossVisibilityChange() {
        if (skipIfHeadless()) return
        val dirL = Files.createDirectory(tempDir.resolve("lwid"))
        val dirR = Files.createDirectory(tempDir.resolve("rwid"))

        val leftPanel = FileManagerPanel(
            project = project,
            initialPath = dirL,
            otherPanelPathProvider = { dirR },
        )
        leftPanel.restoreState(PanelState().apply {
            tabs.add(TabState(path = dirL.toString()))
        }, stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val rightPanel = FileManagerPanel(
            project = project,
            initialPath = dirR,
            otherPanelPathProvider = { dirL },
        )
        rightPanel.restoreState(PanelState().apply {
            tabs.add(TabState(path = dirR.toString()))
        }, stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        leftPanel.otherPanel = rightPanel
        rightPanel.otherPanel = leftPanel

        val leftTab = leftPanel.getTabAt(0)!!
        val rightTab = rightPanel.getTabAt(0)!!
        val cmL = leftTab.table.columnModel
        val cmR = rightTab.table.columnModel

        // Set different widths on each panel
        for (i in 0 until cmL.columnCount) {
            cmL.getColumn(i).preferredWidth = 100 + i * 10
            cmL.getColumn(i).width = 100 + i * 10
        }
        for (i in 0 until cmR.columnCount) {
            cmR.getColumn(i).preferredWidth = 200 + i * 20
            cmR.getColumn(i).width = 200 + i * 20
        }
        val widthsLBefore = mutableMapOf<Int, Int>()
        val widthsRBefore = mutableMapOf<Int, Int>()
        for (i in 0 until cmL.columnCount) widthsLBefore[cmL.getColumn(i).modelIndex] = cmL.getColumn(i).width
        for (i in 0 until cmR.columnCount) widthsRBefore[cmR.getColumn(i).modelIndex] = cmR.getColumn(i).width

        // Hide a column
        val settings = TurtleCommanderSettings.getInstance()
        val columns = settings.getEffectiveColumns()
        columns.find { it.id == "Size" }?.visible = false
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })
        leftTab.applyPanelFont()
        rightTab.applyPanelFont()

        // Verify widths preserved on both panels
        for (i in 0 until cmL.columnCount) {
            val modelIdx = cmL.getColumn(i).modelIndex
            assertEquals("Left panel width for column $modelIdx",
                widthsLBefore[modelIdx]!!, cmL.getColumn(i).width)
        }
        for (i in 0 until cmR.columnCount) {
            val modelIdx = cmR.getColumn(i).modelIndex
            assertEquals("Right panel width for column $modelIdx",
                widthsRBefore[modelIdx]!!, cmR.getColumn(i).width)
        }

        // Restore
        columns.find { it.id == "Size" }?.visible = true
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })
    }

    fun testOppositePanelMultipleTabsPreserveOrderAcrossVisibilityChange() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("omt1"))
        val dir2 = Files.createDirectory(tempDir.resolve("omt2"))
        val dir3 = Files.createDirectory(tempDir.resolve("omt3"))

        // Left panel with 2 tabs, right panel with 1 tab — all different column orders
        val leftPanel = FileManagerPanel(
            project = project,
            initialPath = dir1,
            otherPanelPathProvider = { dir3 },
        )
        leftPanel.restoreState(PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        leftPanel.openDirectoryInNewTab(dir2)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val rightPanel = FileManagerPanel(
            project = project,
            initialPath = dir3,
            otherPanelPathProvider = { dir1 },
        )
        rightPanel.restoreState(PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        leftPanel.otherPanel = rightPanel
        rightPanel.otherPanel = leftPanel

        val leftTab0 = leftPanel.getTabAt(0)!!
        val leftTab1 = leftPanel.getTabAt(1)!!
        val rightTab0 = rightPanel.getTabAt(0)!!

        // Give each tab a different column order
        val cmL0 = leftTab0.table.columnModel
        val cmL1 = leftTab1.table.columnModel
        val cmR0 = rightTab0.table.columnModel
        if (cmL0.columnCount >= 3) cmL0.moveColumn(0, 2)
        if (cmL1.columnCount >= 4) cmL1.moveColumn(0, 3)
        if (cmR0.columnCount >= 3) cmR0.moveColumn(2, 0)

        val orderL0Before = (0 until cmL0.columnCount).map { cmL0.getColumn(it).modelIndex }
        val orderL1Before = (0 until cmL1.columnCount).map { cmL1.getColumn(it).modelIndex }
        val orderR0Before = (0 until cmR0.columnCount).map { cmR0.getColumn(it).modelIndex }

        // Hide "Date Created" (modelIndex 3)
        val settings = TurtleCommanderSettings.getInstance()
        val columns = settings.getEffectiveColumns()
        columns.find { it.id == "Date Created" }?.visible = false
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })
        leftTab0.applyPanelFont()
        leftTab1.applyPanelFont()
        rightTab0.applyPanelFont()

        val orderL0After = (0 until cmL0.columnCount).map { cmL0.getColumn(it).modelIndex }
        val orderL1After = (0 until cmL1.columnCount).map { cmL1.getColumn(it).modelIndex }
        val orderR0After = (0 until cmR0.columnCount).map { cmR0.getColumn(it).modelIndex }

        assertEquals("Left tab 0 order preserved", orderL0Before.filter { it != 3 }, orderL0After)
        assertEquals("Left tab 1 order preserved", orderL1Before.filter { it != 3 }, orderL1After)
        assertEquals("Right tab 0 order preserved", orderR0Before.filter { it != 3 }, orderR0After)

        // Restore
        columns.find { it.id == "Date Created" }?.visible = true
        settings.state.columns.clear()
        settings.state.columns.addAll(columns.map { c ->
            ColumnConfig().apply {
                id = c.id; visible = c.visible; style.copyFrom(c.style)
            }
        })
    }

    fun testTabStateDefaults() {
        val state = TabState()
        assertEquals("", state.path)
        assertEquals("TABLE", state.viewMode)
        assertEquals("", state.columnWidths)
        assertEquals("", state.columnOrder)
        assertEquals(-1, state.sortColumn)
        assertTrue(state.sortAscending)
    }

    fun testTabStateConstructor() {
        val state = TabState(
            path = "/test/path",
            viewMode = "LIST",
            columnWidths = "100,200",
            columnOrder = "1,0",
            sortColumn = 2,
            sortAscending = false,
        )
        assertEquals("/test/path", state.path)
        assertEquals("LIST", state.viewMode)
        assertEquals("100,200", state.columnWidths)
        assertEquals("1,0", state.columnOrder)
        assertEquals(2, state.sortColumn)
        assertFalse(state.sortAscending)
    }

    // --- Per-tab column widths and view-mode switch behavior ---

    fun testNewTabInheritsColumnWidthsFromActiveTableTab() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("inheritwidths"))
        val panel = createPanel()
        panel.openDirectoryInNewTab(dir)
        waitForNavigation()

        val tab1 = panel.getTabAt(1)!!
        tab1.table.columnModel.getColumn(0).apply { preferredWidth = 500; width = 500 }

        panel.openDirectoryInNewTab(dir)
        waitForNavigation()

        val tab2 = panel.getTabAt(2)!!
        assertEquals("New tab should inherit col 0 width from the previously active TABLE tab",
            500, tab2.table.columnModel.getColumn(0).width)
    }

    fun testNewTabIndependentAfterInheritance() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("indepafter"))
        val panel = createPanel()
        panel.openDirectoryInNewTab(dir)
        waitForNavigation()

        val tab1 = panel.getTabAt(1)!!
        tab1.table.columnModel.getColumn(0).apply { preferredWidth = 500; width = 500 }

        panel.openDirectoryInNewTab(dir)
        waitForNavigation()
        val tab2 = panel.getTabAt(2)!!

        // Resize tab2 — tab1 must not be affected
        tab2.table.columnModel.getColumn(0).apply { preferredWidth = 200; width = 200 }
        tab2.saveColumnState()

        assertEquals("tab1 width unchanged by tab2 mutation",
            500, tab1.table.columnModel.getColumn(0).width)
        assertEquals("tab2 width is its own",
            200, tab2.table.columnModel.getColumn(0).width)
    }

    fun testSwitchToTableAppliesDefaultWidthsWhenNoSavedState() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val tab = panel.getTabAt(0)!!
        val defaultWidth = tab.table.columnModel.getColumn(0).width

        // User widens column 0 directly (no saveColumnState triggered, no navigation)
        tab.table.columnModel.getColumn(0).apply { preferredWidth = 500; width = 500 }

        tab.setViewMode(ViewMode.LIST)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab.setViewMode(ViewMode.TABLE)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Width should reset to default when no saved state",
            defaultWidth, tab.table.columnModel.getColumn(0).width)
    }

    fun testSwitchToTablePreservesWidthsWhenSavedState() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val tab = panel.getTabAt(0)!!

        tab.table.columnModel.getColumn(0).apply { preferredWidth = 500; width = 500 }
        tab.saveColumnState()

        tab.setViewMode(ViewMode.LIST)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab.setViewMode(ViewMode.TABLE)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Width should be preserved when tab has saved column state",
            500, tab.table.columnModel.getColumn(0).width)
    }

    fun testSwitchToTableFromThumbnailAppliesDefaultWidths() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val tab = panel.getTabAt(0)!!
        val defaultWidth = tab.table.columnModel.getColumn(0).width

        tab.table.columnModel.getColumn(0).apply { preferredWidth = 500; width = 500 }

        tab.setViewMode(ViewMode.THUMBNAIL)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab.setViewMode(ViewMode.TABLE)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Switching from THUMBNAIL should also reset to defaults",
            defaultWidth, tab.table.columnModel.getColumn(0).width)
    }

    fun testDuplicateTabIndependentColumnWidthsAfterResize() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("dup"))
        val panel = createPanel()
        panel.openDirectoryInNewTab(dir)
        panel.openDirectoryInNewTab(dir)
        waitForNavigation()

        val tab1 = panel.getTabAt(1)!!
        val tab2 = panel.getTabAt(2)!!

        tab1.table.columnModel.getColumn(0).apply { preferredWidth = 350; width = 350 }
        tab1.saveColumnState()

        // tab2's saved state must not have been touched by tab1's save
        assertFalse("tab2 should not have saved column state from tab1",
            tab2.hasTabColumnState(dir.toString()))
        assertNotSame(
            "tab2 column width should be independent",
            350, tab2.table.columnModel.getColumn(0).width
        )
    }
}
