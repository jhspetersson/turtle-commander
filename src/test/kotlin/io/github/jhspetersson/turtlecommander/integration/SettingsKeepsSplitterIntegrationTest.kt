package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.UIUtil
import io.github.jhspetersson.turtlecommander.settings.PanelLayout
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerToolWindowFactory

class SettingsKeepsSplitterIntegrationTest : BasePlatformTestCase() {

    private val toolWindowId = "Turtle Commander Splitter Test"
    private lateinit var savedLayout: String
    private lateinit var toolWindow: ToolWindow

    override fun setUp() {
        super.setUp()
        savedLayout = TurtleCommanderSettings.getInstance().state.panelLayout
        TurtleCommanderSettings.getInstance().state.panelLayout = PanelLayout.HORIZONTAL.name
        toolWindow = ToolWindowManager.getInstance(project).registerToolWindow(toolWindowId) {}
        FileManagerToolWindowFactory().createToolWindowContent(project, toolWindow)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            TurtleCommanderSettings.getInstance().state.panelLayout = savedLayout
            ToolWindowManager.getInstance(project).unregisterToolWindow(toolWindowId)
        } finally {
            super.tearDown()
        }
    }

    private fun splitter(): OnePixelSplitter? =
        UIUtil.findComponentOfType(toolWindow.contentManager.getContent(0)!!.component, OnePixelSplitter::class.java)

    private fun fireSettingsChanged() {
        TurtleCommanderSettings.getInstance().fireSettingsChanged()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    fun testUnrelatedSettingsChangeKeepsTheSplitter() {
        val before = splitter()
        assertNotNull(before)

        fireSettingsChanged()

        assertSame(before, splitter())
    }

    fun testLayoutChangeStillRebuildsTheSplitter() {
        val before = splitter()!!
        assertFalse(before.orientation)

        TurtleCommanderSettings.getInstance().state.panelLayout = PanelLayout.VERTICAL.name
        fireSettingsChanged()

        val after = splitter()!!
        assertNotSame(before, after)
        assertTrue(after.orientation)
    }
}
