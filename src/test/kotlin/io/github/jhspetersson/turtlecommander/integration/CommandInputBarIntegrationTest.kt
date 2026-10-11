package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.CommandInputBar
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import java.nio.file.Files
import java.nio.file.Path

class CommandInputBarIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var leftRoot: Path
    private lateinit var rightRoot: Path
    private lateinit var leftPanel: FileManagerPanel
    private lateinit var rightPanel: FileManagerPanel
    private lateinit var bar: CommandInputBar
    private val parentDisposable = Disposer.newDisposable("turtle-test-command-bar")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-command-bar-")
        leftRoot = Files.createDirectories(tempDir.resolve("left"))
        rightRoot = Files.createDirectories(tempDir.resolve("right"))
        Files.createDirectories(leftRoot.resolve("sub"))
        leftPanel = createPanel(leftRoot)
        rightPanel = createPanel(rightRoot)
        leftPanel.otherPanel = rightPanel
        rightPanel.otherPanel = leftPanel
        bar = CommandInputBar(project, leftPanel, rightPanel)
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
        val panel = FileManagerPanel(
            project = project,
            initialPath = root,
            otherPanelPathProvider = { root },
        )
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return panel
    }

    private fun navigate(panel: FileManagerPanel, target: Path) {
        val tab = panel.getActiveTab()!!
        tab.fileOps.launch { tab.navigateTo(target, requestFocus = false) }
        waitFor("navigation to $target") { tab.currentPath == target }
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        repeat(100) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for $description")
    }

    fun testActivateWhileVisibleRetargetsToTheRequestedPanel() {
        bar.activate(leftPanel)
        assertSame(leftPanel, bar.targetPanel)
        assertEquals(leftRoot.toString(), bar.promptDirectory)

        bar.activate(rightPanel)
        assertSame(rightPanel, bar.targetPanel)
        assertEquals(rightRoot.toString(), bar.promptDirectory)
    }

    fun testActivateWithoutPanelKeepsCurrentTarget() {
        bar.activate(rightPanel)
        bar.activate(null)
        assertSame(rightPanel, bar.targetPanel)
        assertEquals(rightRoot.toString(), bar.promptDirectory)
    }

    fun testPromptFollowsNavigationInTargetPanel() {
        bar.activate(leftPanel)
        val sub = leftRoot.resolve("sub")
        navigate(leftPanel, sub)
        assertEquals(sub.toString(), bar.promptDirectory)
    }

    fun testPromptIgnoresNavigationInOtherPanel() {
        bar.activate(rightPanel)
        val sub = leftRoot.resolve("sub")
        navigate(leftPanel, sub)
        assertEquals(rightRoot.toString(), bar.promptDirectory)
    }

    fun testSwitchTargetPanelUpdatesPrompt() {
        bar.activate(leftPanel)
        bar.switchTargetPanel()
        assertSame(rightPanel, bar.targetPanel)
        assertEquals(rightRoot.toString(), bar.promptDirectory)
    }
}
