package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests for tab management: open, close, multiple tabs, view mode persistence.
 */
class TabManagementIntegrationTest : BasePlatformTestCase() {

    private lateinit var projectPath: Path
    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        projectPath = Path.of(project.basePath!!)
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-tabs-")
        // Ensure default view mode is TABLE (may be polluted by other tests)
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
    }

    override fun tearDown() {
        try {
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

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

    fun testSingleTabAfterInit() {
        val panel = createPanel()

        assertNotNull("Should have an active tab", panel.getActiveTab())
        val state = panel.saveState()
        assertEquals("Should have exactly 1 tab", 1, state.tabPaths.size)
    }

    fun testOpenDirectoryInNewTab() {
        val panel = createPanel()
        val homeDir = Path.of(System.getProperty("user.home"))

        panel.openDirectoryInNewTab(homeDir)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals("Should have 2 tabs", 2, state.tabPaths.size)
    }

    fun testOpenMultipleTabs() {
        val panel = createPanel()
        val dir1 = Files.createDirectory(tempDir.resolve("dir1"))
        val dir2 = Files.createDirectory(tempDir.resolve("dir2"))
        val dir3 = Files.createDirectory(tempDir.resolve("dir3"))

        panel.openDirectoryInNewTab(dir1)
        panel.openDirectoryInNewTab(dir2)
        panel.openDirectoryInNewTab(dir3)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals("Should have 4 tabs", 4, state.tabPaths.size)
    }

    fun testCloseTab() {
        val panel = createPanel()
        val dir = Files.createDirectory(tempDir.resolve("closeme"))
        panel.openDirectoryInNewTab(dir)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(2, panel.saveState().tabPaths.size)

        panel.closeTab(1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Should have 1 tab after close", 1, panel.saveState().tabPaths.size)
    }

    fun testCloseLastTabKeepsOne() {
        val panel = createPanel()

        // Try to close the only tab — it should be kept (can't have zero tabs)
        panel.closeTab(0)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertNotNull("Should still have an active tab", panel.getActiveTab())
    }

    fun testCloseOtherTabs() {
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("a")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("b")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("c")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(4, panel.saveState().tabPaths.size)

        panel.closeOtherTabs(0) // keep only tab 0
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Should have 1 tab after closing others", 1, panel.saveState().tabPaths.size)
    }

    fun testCloseAllTabs() {
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("x")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("y")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        panel.closeAllTabs()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        // closeAllTabs should leave at least one tab (re-opens with initial path)
        assertNotNull("Should still have an active tab", panel.getActiveTab())
    }

    fun testCloseTabsToTheRight() {
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("r1")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("r2")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("r3")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(4, panel.saveState().tabPaths.size)

        panel.closeTabsToTheRight(1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Should have 2 tabs (0 and 1)", 2, panel.saveState().tabPaths.size)
    }

    fun testCloseTabsToTheLeft() {
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("l1")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("l2")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("l3")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(4, panel.saveState().tabPaths.size)

        panel.closeTabsToTheLeft(3) // close tabs 0, 1, 2
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Should have 1 tab left", 1, panel.saveState().tabPaths.size)
    }

    // --- View mode persistence ---

    fun testViewModeSavedInState() {
        val dir = Files.createDirectory(tempDir.resolve("vms"))
        val panelState = FileManagerStateService.PanelState().apply {
            tabPaths.add(dir.toString())
            tabViewModes.add("LIST")
        }

        val panel = FileManagerPanel(
            project = project,
            initialPath = dir,
            otherPanelPathProvider = { dir },
        )
        panel.restoreState(panelState, stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals("LIST", state.tabViewModes[0])
    }

    fun testViewModeRestoredFromState() {
        val dir = Files.createDirectory(tempDir.resolve("vmr"))
        val panelState = FileManagerStateService.PanelState().apply {
            tabPaths.add(dir.toString())
            tabViewModes.add("THUMBNAIL")
        }

        val panel = FileManagerPanel(
            project = project,
            initialPath = dir,
            otherPanelPathProvider = { dir },
        )
        panel.restoreState(panelState, stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        // Verify via saved state round-trip (setViewMode may not fully work in headless)
        val saved = panel.saveState()
        assertEquals("THUMBNAIL", saved.tabViewModes.firstOrNull())
    }

    fun testMultipleTabsPreserveViewModes() {
        val dir1 = Files.createDirectory(tempDir.resolve("vm1"))
        val dir2 = Files.createDirectory(tempDir.resolve("vm2"))
        val panelState = FileManagerStateService.PanelState().apply {
            tabPaths.add(dir1.toString())
            tabPaths.add(dir2.toString())
            tabViewModes.add("TABLE")
            tabViewModes.add("TREE")
        }

        val panel = FileManagerPanel(
            project = project,
            initialPath = dir1,
            otherPanelPathProvider = { dir1 },
        )
        panel.restoreState(panelState, stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals(2, state.tabViewModes.size)
        assertEquals("TABLE", state.tabViewModes[0])
        assertEquals("TREE", state.tabViewModes[1])
    }

    // --- Tab navigation ---

    fun testSelectNextTab() {
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("next")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        // Start at tab 1 (most recently opened)
        panel.selectPreviousTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(0, panel.getActiveTabIndex())

        panel.selectNextTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(1, panel.getActiveTabIndex())
    }

    fun testSelectNextTabWraps() {
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("wrap")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        // At last tab, selectNext should wrap to first
        panel.selectNextTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(0, panel.getActiveTabIndex())
    }

    // --- Dual panel interaction ---

    fun testOtherPanelReference() {
        val left = createPanel()
        val right = FileManagerPanel(
            project = project,
            initialPath = projectPath,
            otherPanelPathProvider = { projectPath },
        )
        right.restoreState(FileManagerStateService.PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        left.otherPanel = right
        right.otherPanel = left

        assertNotNull("Left panel should reference right", left.otherPanel)
        assertNotNull("Right panel should reference left", right.otherPanel)
        assertSame(right, left.otherPanel)
        assertSame(left, right.otherPanel)
    }
}
