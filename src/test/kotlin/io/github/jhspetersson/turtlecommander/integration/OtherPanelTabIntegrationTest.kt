package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import java.nio.file.Files
import java.nio.file.Path

class OtherPanelTabIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var leftPanel: FileManagerPanel
    private lateinit var rightPanel: FileManagerPanel
    private lateinit var stateService: FileManagerStateService
    private val parentDisposable = Disposer.newDisposable("turtle-test-other-panel-tab")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-other-panel-")
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

    fun testOtherPanelTabIsRelativeToTheTabsOwnPanelNotTheActiveOne() {
        stateService.noteFocusOwner(leftPanel.getActiveTab()!!.table)
        assertSame(leftPanel, stateService.getActivePanel())

        assertSame(leftPanel.getActiveTab(), rightPanel.getActiveTab()!!.getOtherPanelTab())
        assertSame(rightPanel.getActiveTab(), leftPanel.getActiveTab()!!.getOtherPanelTab())
    }

    fun testOtherPanelTabFollowsATabMovedToTheOtherPanel() {
        val moved = leftPanel.getActiveTab()!!
        leftPanel.openDirectoryInNewTab(tempDir)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        leftPanel.moveTabToOtherPanel(0, rightPanel, 1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        stateService.noteFocusOwner(rightPanel.getActiveTab()!!.table)

        assertSame(rightPanel, stateService.panelOf(moved))
        assertSame(leftPanel.getActiveTab(), moved.getOtherPanelTab())
    }
}
