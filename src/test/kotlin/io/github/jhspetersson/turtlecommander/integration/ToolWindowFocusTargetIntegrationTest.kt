package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerToolWindowFactory
import java.nio.file.Files
import java.nio.file.Path

class ToolWindowFocusTargetIntegrationTest : BasePlatformTestCase() {

    private val toolWindowId = "Turtle Commander Focus Target Test"
    private lateinit var toolWindow: ToolWindow
    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-focus-target-")
        stateService = project.service()
        toolWindow = ToolWindowManager.getInstance(project).registerToolWindow(toolWindowId) {}
        FileManagerToolWindowFactory().createToolWindowContent(project, toolWindow)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            ToolWindowManager.getInstance(project).unregisterToolWindow(toolWindowId)
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun preferred() = toolWindow.contentManager.getContent(0)!!.preferredFocusableComponent

    fun testToolWindowFocusGoesToTheActivePanelTable() {
        assertSame(stateService.rightPanel!!.getActiveTab()!!.table, preferred())
    }

    fun testToolWindowFocusFollowsTheLastActivePanel() {
        val left = stateService.leftPanel!!
        stateService.noteFocusOwner(left.getActiveTab()!!.table)

        assertSame(left.getActiveTab()!!.table, preferred())
    }

    fun testToolWindowFocusTargetsTheTabJustOpened() {
        val right = stateService.rightPanel!!
        val before = right.getActiveTab()!!

        right.openDirectoryInNewTab(tempDir)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val opened = right.getActiveTab()!!
        assertNotSame(before, opened)
        assertSame(opened.table, preferred())
    }
}
