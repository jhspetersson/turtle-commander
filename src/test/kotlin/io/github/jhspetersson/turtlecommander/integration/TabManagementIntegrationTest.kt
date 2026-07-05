package io.github.jhspetersson.turtlecommander.integration
import com.intellij.openapi.util.Disposer
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.vfs.VfsStackEntry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem

import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.dialog.NamePatternMode
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.*
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests for tab management: open, close, multiple tabs, view mode persistence.
 * Requires a non-headless environment (Swing UI components).
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

    fun testDisposingParentDisposesTabsAndClosesVfs() {
        if (skipIfHeadless()) return
        // Tabs must be disposed with the tool window's disposable (project close, plugin
        // unload), not only on manual close — otherwise a tab left inside an archive
        // leaks its filesystem handles and extraction temp directory.
        val parent = Disposer.newDisposable("turtle-test-parent")
        val panel = FileManagerPanel(
            project = project,
            initialPath = projectPath,
            otherPanelPathProvider = { projectPath },
        )
        panel.parentDisposable = parent
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val tab = panel.getActiveTab()
        assertNotNull("Should have an active tab", tab)

        // Simulate an open archive: a fake VFS on the stack whose close() we can observe.
        var vfsClosed = false
        val fakeVfs = object : VirtualFileSystem {
            override val archivePath: Path = projectPath
            override val root: Path = projectPath
            override suspend fun listFiles(directory: Path) =
                emptyList<FileEntry>()
            override fun isRoot(path: Path) = path == root
            override fun getPath(relativePath: String): Path = root
            override fun flush() {}
            override suspend fun renameFile(source: Path, newName: String): Path =
                throw UnsupportedOperationException()
            override fun close() { vfsClosed = true }
        }
        tab!!.vfsStack.add(VfsStackEntry(fakeVfs, projectPath))

        Disposer.dispose(parent)

        assertTrue("Disposing the parent must close the tab's open VFS", vfsClosed)
        assertTrue("VFS stack must be cleared on dispose", tab.vfsStack.isEmpty())
    }

    fun testSingleTabAfterInit() {
        if (skipIfHeadless()) return
        val panel = createPanel()

        assertNotNull("Should have an active tab", panel.getActiveTab())
        val state = panel.saveState()
        assertEquals("Should have exactly 1 tab", 1, state.tabs.size)
    }

    fun testOpenDirectoryInNewTab() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val homeDir = Path.of(System.getProperty("user.home"))

        panel.openDirectoryInNewTab(homeDir)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals("Should have 2 tabs", 2, state.tabs.size)
    }

    fun testOpenMultipleTabs() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val dir1 = Files.createDirectory(tempDir.resolve("dir1"))
        val dir2 = Files.createDirectory(tempDir.resolve("dir2"))
        val dir3 = Files.createDirectory(tempDir.resolve("dir3"))

        panel.openDirectoryInNewTab(dir1)
        panel.openDirectoryInNewTab(dir2)
        panel.openDirectoryInNewTab(dir3)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals("Should have 4 tabs", 4, state.tabs.size)
    }

    fun testCloseTab() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val dir = Files.createDirectory(tempDir.resolve("closeme"))
        panel.openDirectoryInNewTab(dir)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(2, panel.saveState().tabs.size)

        panel.closeTab(1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Should have 1 tab after close", 1, panel.saveState().tabs.size)
    }

    fun testCloseLastTabKeepsOne() {
        if (skipIfHeadless()) return
        val panel = createPanel()

        panel.closeTab(0)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertNotNull("Should still have an active tab", panel.getActiveTab())
    }

    fun testClosingLastSearchTabLeavesAUsableFileTab() {
        if (skipIfHeadless()) return
        val panel = createPanel()

        // Open a search tab, then close the only file tab (allowed while the search tab exists).
        val criteria = FileSearchCriteria(
            rootPath = tempDir,
            namePattern = "*.txt",
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )
        val searchPanel = panel.openSearchTab(criteria)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        panel.closeTab(0) // close the file tab; only the search tab remains
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertNull("With only a search tab, getActiveTab() is null", panel.getActiveTab())

        // Closing the last (search) tab must not leave the panel empty.
        panel.closeSearchTab(searchPanel)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Panel must keep a real tab", 1, panel.getRealTabCount())
        assertNotNull("A usable file tab must remain after closing the last search tab", panel.getActiveTab())
    }

    fun testCloseOtherTabs() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("a")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("b")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("c")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(4, panel.saveState().tabs.size)

        panel.closeOtherTabs(0)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Should have 1 tab after closing others", 1, panel.saveState().tabs.size)
    }

    fun testCloseAllTabs() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("x")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("y")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        panel.closeAllTabs()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertNotNull("Should still have an active tab", panel.getActiveTab())
    }

    fun testCloseTabsToTheRight() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("r1")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("r2")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("r3")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(4, panel.saveState().tabs.size)

        panel.closeTabsToTheRight(1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Should have 2 tabs (0 and 1)", 2, panel.saveState().tabs.size)
    }

    fun testCloseTabsToTheLeft() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("l1")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("l2")))
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("l3")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(4, panel.saveState().tabs.size)

        panel.closeTabsToTheLeft(3)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Should have 1 tab left", 1, panel.saveState().tabs.size)
    }

    // --- View mode persistence ---

    fun testViewModeSavedInState() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("vms"))
        val panelState = FileManagerStateService.PanelState().apply {
            tabs.add(FileManagerStateService.TabState(path = dir.toString(), viewMode = "LIST"))
        }

        val panel = FileManagerPanel(
            project = project,
            initialPath = dir,
            otherPanelPathProvider = { dir },
        )
        panel.restoreState(panelState, stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals("LIST", state.tabs[0].viewMode)
    }

    fun testViewModeRestoredFromState() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("vmr"))
        val panelState = FileManagerStateService.PanelState().apply {
            tabs.add(FileManagerStateService.TabState(path = dir.toString(), viewMode = "THUMBNAIL"))
        }

        val panel = FileManagerPanel(
            project = project,
            initialPath = dir,
            otherPanelPathProvider = { dir },
        )
        panel.restoreState(panelState, stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val saved = panel.saveState()
        assertEquals("THUMBNAIL", saved.tabs.firstOrNull()?.viewMode)
    }

    fun testMultipleTabsPreserveViewModes() {
        if (skipIfHeadless()) return
        val dir1 = Files.createDirectory(tempDir.resolve("vm1"))
        val dir2 = Files.createDirectory(tempDir.resolve("vm2"))
        val panelState = FileManagerStateService.PanelState().apply {
            tabs.add(FileManagerStateService.TabState(path = dir1.toString(), viewMode = "TABLE"))
            tabs.add(FileManagerStateService.TabState(path = dir2.toString(), viewMode = "TREE"))
        }

        val panel = FileManagerPanel(
            project = project,
            initialPath = dir1,
            otherPanelPathProvider = { dir1 },
        )
        panel.restoreState(panelState, stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals(2, state.tabs.size)
        assertEquals("TABLE", state.tabs[0].viewMode)
        assertEquals("TREE", state.tabs[1].viewMode)
    }

    // --- Tab navigation ---

    fun testSelectNextTab() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("next")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        panel.selectPreviousTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(0, panel.getActiveTabIndex())

        panel.selectNextTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(1, panel.getActiveTabIndex())
    }

    fun testSelectNextTabWraps() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("wrap")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        panel.selectNextTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(0, panel.getActiveTabIndex())
    }

    // --- Reopen closed tab ---

    fun testHasClosedTabsInitiallyFalse() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        assertFalse("Closed-tab stack should start empty", panel.hasClosedTabs())
    }

    fun testHasClosedTabsAfterClose() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("rc1")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        panel.closeTab(1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue("Closing a tab should push onto the reopen stack", panel.hasClosedTabs())
    }

    fun testReopenClosedTabRestoresPath() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val dir = Files.createDirectory(tempDir.resolve("reopenpath"))
        panel.openDirectoryInNewTab(dir)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(2, panel.saveState().tabs.size)

        panel.closeTab(1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(1, panel.saveState().tabs.size)

        panel.reopenLastClosedTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals("Reopen should restore the tab", 2, state.tabs.size)
        assertTrue(
            "Reopened tab should point at the original directory",
            state.tabs.any { it.path == dir.toString() },
        )
        assertFalse("Stack should be empty after single reopen", panel.hasClosedTabs())
    }

    fun testReopenInsertsAtOriginalIndex() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val a = Files.createDirectory(tempDir.resolve("oi_a"))
        val b = Files.createDirectory(tempDir.resolve("oi_b"))
        val c = Files.createDirectory(tempDir.resolve("oi_c"))
        panel.openDirectoryInNewTab(a)
        panel.openDirectoryInNewTab(b)
        panel.openDirectoryInNewTab(c)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        // Tabs: [project, a, b, c] — close the middle one.
        panel.closeTab(2)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        panel.reopenLastClosedTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val state = panel.saveState()
        assertEquals(4, state.tabs.size)
        assertEquals("Reopen should restore index 2 (b) back between a and c",
            b.toString(), state.tabs[2].path)
    }

    fun testReopenMultipleInLIFOOrder() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val a = Files.createDirectory(tempDir.resolve("lifo_a"))
        val b = Files.createDirectory(tempDir.resolve("lifo_b"))
        panel.openDirectoryInNewTab(a)
        panel.openDirectoryInNewTab(b)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        panel.closeTab(1) // close a (at index 1 after project at 0, a, b)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        panel.closeTab(1) // close b (now at index 1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        // Most recently closed is b — it should come back first.
        panel.reopenLastClosedTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(panel.saveState().tabs.any { it.path == b.toString() })

        panel.reopenLastClosedTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val paths = panel.saveState().tabs.map { it.path }
        assertTrue("Both reopened tabs should be present", paths.contains(a.toString()) && paths.contains(b.toString()))
        assertFalse(panel.hasClosedTabs())
    }

    fun testReopenAfterCloseOthersRestoresRightmostFirst() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val a = Files.createDirectory(tempDir.resolve("co_a"))
        val b = Files.createDirectory(tempDir.resolve("co_b"))
        val c = Files.createDirectory(tempDir.resolve("co_c"))
        panel.openDirectoryInNewTab(a)
        panel.openDirectoryInNewTab(b)
        panel.openDirectoryInNewTab(c)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        // Tabs: [project(0), a(1), b(2), c(3)] — keep 'b'.
        panel.closeOtherTabs(2)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(1, panel.saveState().tabs.size)

        // Bulk-close pushed in ascending order → rightmost (c) pops first.
        panel.reopenLastClosedTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val paths1 = panel.saveState().tabs.map { it.path }
        assertTrue("c should reopen first", paths1.contains(c.toString()))
        assertFalse("a should not be back yet", paths1.contains(a.toString()))
    }

    fun testReopenAfterCloseTabsToTheRight() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        panel.openDirectoryInNewTab(Files.createDirectory(tempDir.resolve("cr_a")))
        val b = Files.createDirectory(tempDir.resolve("cr_b"))
        panel.openDirectoryInNewTab(b)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(3, panel.saveState().tabs.size)

        panel.closeTabsToTheRight(1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(2, panel.saveState().tabs.size)

        panel.reopenLastClosedTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(3, panel.saveState().tabs.size)
        assertTrue(panel.saveState().tabs.any { it.path == b.toString() })
    }

    fun testReopenPreservesViewMode() {
        if (skipIfHeadless()) return
        val dir = Files.createDirectory(tempDir.resolve("vmreopen"))
        val panelState = FileManagerStateService.PanelState().apply {
            tabs.add(FileManagerStateService.TabState(path = projectPath.toString(), viewMode = "TABLE"))
            tabs.add(FileManagerStateService.TabState(path = dir.toString(), viewMode = "LIST"))
        }
        val panel = FileManagerPanel(
            project = project,
            initialPath = projectPath,
            otherPanelPathProvider = { projectPath },
        )
        panel.restoreState(panelState, stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        panel.closeTab(1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        panel.reopenLastClosedTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val reopened = panel.saveState().tabs.firstOrNull { it.path == dir.toString() }
        assertNotNull("Reopened tab should be present", reopened)
        assertEquals("View mode should survive close → reopen", "LIST", reopened!!.viewMode)
    }

    fun testReopenWithEmptyStackIsNoop() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val before = panel.saveState().tabs.size

        panel.reopenLastClosedTab()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Reopen on empty stack should not change tab count", before, panel.saveState().tabs.size)
    }

    // --- Dual panel interaction ---

    fun testOtherPanelReference() {
        if (skipIfHeadless()) return
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
