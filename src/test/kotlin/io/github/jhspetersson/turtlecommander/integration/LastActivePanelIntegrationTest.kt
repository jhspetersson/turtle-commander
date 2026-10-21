package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.options.advanced.AdvancedSettings
import io.github.jhspetersson.turtlecommander.action.settingsOpenInEditorTab
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton

class LastActivePanelIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var leftPanel: FileManagerPanel
    private lateinit var rightPanel: FileManagerPanel
    private lateinit var stateService: FileManagerStateService
    private val parentDisposable = Disposer.newDisposable("turtle-test-last-active-panel")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-last-active-")
        stateService = project.service()
        leftPanel = createPanel(Files.createDirectories(tempDir.resolve("left")))
        rightPanel = createPanel(Files.createDirectories(tempDir.resolve("right")))
        leftPanel.otherPanel = rightPanel
        rightPanel.otherPanel = leftPanel
        stateService.registerPanels(leftPanel, rightPanel, null)
    }

    override fun tearDown() {
        try {
            stateService.focusPanel = { it.focusActiveTab() }
            AdvancedSettings.setBoolean(NON_MODAL_SETTINGS_KEY, false)
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

    fun testFocusInsideRightPanelMakesItLastActive() {
        stateService.noteFocusOwner(rightPanel.getActiveTab()!!.table)

        assertSame(rightPanel, stateService.lastActivePanel)
    }

    fun testFocusInsideLeftPanelMakesItLastActive() {
        stateService.noteFocusOwner(rightPanel.getActiveTab()!!.table)
        stateService.noteFocusOwner(leftPanel.getActiveTab()!!.table)

        assertSame(leftPanel, stateService.lastActivePanel)
    }

    fun testFocusOutsideBothPanelsKeepsTheLastActivePanel() {
        stateService.noteFocusOwner(rightPanel.getActiveTab()!!.table)
        stateService.noteFocusOwner(JButton())
        stateService.noteFocusOwner(null)

        assertSame(rightPanel, stateService.lastActivePanel)
    }

    fun testRegisteringNewPanelsForgetsTheLastActivePanel() {
        stateService.noteFocusOwner(rightPanel.getActiveTab()!!.table)
        val newRight = createPanel(Files.createDirectories(tempDir.resolve("right2")))
        stateService.registerPanels(leftPanel, newRight, null)

        assertNull(stateService.lastActivePanel)
    }

    fun testFocusInsideAStalePanelIsIgnored() {
        val staleRight = rightPanel
        val newRight = createPanel(Files.createDirectories(tempDir.resolve("right2")))
        stateService.registerPanels(leftPanel, newRight, null)
        stateService.noteFocusOwner(staleRight.getActiveTab()!!.table)

        assertNull(stateService.lastActivePanel)
    }

    fun testNoLastActivePanelBeforeAnyFocus() {
        assertNull(stateService.lastActivePanel)
    }

    private fun recordRefocus(): MutableList<FileManagerPanel> {
        val refocused = mutableListOf<FileManagerPanel>()
        stateService.focusPanel = { refocused.add(it) }
        return refocused
    }

    fun testRestoringPanelFocusReturnsToThePanelActiveBeforeTheDialog() {
        val refocused = recordRefocus()
        stateService.noteFocusOwner(rightPanel.getActiveTab()!!.table)

        stateService.restoringPanelFocus { stateService.noteFocusOwner(leftPanel.getActiveTab()!!.table) }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(listOf(rightPanel), refocused)
    }

    fun testRestoringPanelFocusDoesNothingWithoutALastActivePanel() {
        val refocused = recordRefocus()

        stateService.restoringPanelFocus {}
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEmpty(refocused)
    }

    fun testSettingsAsEditorTabFollowsTheAdvancedSetting() {
        AdvancedSettings.setBoolean(NON_MODAL_SETTINGS_KEY, false)
        assertFalse(settingsOpenInEditorTab())

        AdvancedSettings.setBoolean(NON_MODAL_SETTINGS_KEY, true)
        assertTrue(settingsOpenInEditorTab())
    }

    private companion object {
        const val NON_MODAL_SETTINGS_KEY = "ide.ui.non.modal.settings.window"
    }
}
