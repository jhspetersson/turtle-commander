package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.action.FILE_CONTEXT_PLACE
import io.github.jhspetersson.turtlecommander.action.FileContextMenuState
import io.github.jhspetersson.turtlecommander.action.OpenInNewTabAction
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class SearchTargetPanelIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var leftPanel: FileManagerPanel
    private lateinit var rightPanel: FileManagerPanel
    private lateinit var stateService: FileManagerStateService
    private val parentDisposable = Disposer.newDisposable("turtle-test-search-target")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-search-target-")
        stateService = project.service()
        leftPanel = createPanel(Files.createDirectories(tempDir.resolve("left")))
        rightPanel = createPanel(Files.createDirectories(tempDir.resolve("right")))
        leftPanel.otherPanel = rightPanel
        rightPanel.otherPanel = leftPanel
        stateService.registerPanels(leftPanel, rightPanel, null)
    }

    override fun tearDown() {
        try {
            Disposer.dispose(parentDisposable)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun createPanel(root: Path): FileManagerPanel {
        val panel = FileManagerPanel(project = project, initialPath = root, otherPanelPathProvider = { root })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return panel
    }

    fun testPanelOfResolvesTheOwningPanelRegardlessOfFocus() {
        assertSame(leftPanel, stateService.panelOf(leftPanel.getActiveTab()!!))
        assertSame(rightPanel, stateService.panelOf(rightPanel.getActiveTab()!!))
    }

    fun testOpenInNewTabOpensInTheClickedTabsPanelNotTheFocusedOne() {
        val sub = Files.createDirectories(tempDir.resolve("left").resolve("sub"))
        val leftTab = leftPanel.getActiveTab()!!
        FileContextMenuState.clickedTab = leftTab
        FileContextMenuState.clickedEntry = FileEntry(
            name = "sub",
            path = sub,
            isDirectory = true,
            size = 0,
            lastModified = FileTime.fromMillis(0),
            permissions = "",
        )
        val leftBefore = leftPanel.getRealTabCount()
        val rightBefore = rightPanel.getRealTabCount()
        try {
            val action = OpenInNewTabAction()
            val event = AnActionEvent.createEvent(
                SimpleDataContext.getProjectContext(project),
                action.templatePresentation.clone(),
                FILE_CONTEXT_PLACE,
                ActionUiKind.NONE,
                null,
            )
            action.actionPerformed(event)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally {
            FileContextMenuState.clear()
        }
        assertEquals(leftBefore + 1, leftPanel.getRealTabCount())
        assertEquals(rightBefore, rightPanel.getRealTabCount())
    }

    fun testPanelOfReturnsNullForATabNoPanelOwns() {
        val orphan = FileTab(
            project = project,
            initialPath = tempDir,
            otherPanelPathProvider = { null },
            onDirectoryChanged = {},
            onRefreshOtherPanel = {},
        )
        Disposer.register(parentDisposable, orphan)
        assertNull(stateService.panelOf(orphan))
    }
}
