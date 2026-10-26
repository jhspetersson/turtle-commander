package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.getSelectedEntry
import java.nio.file.Files
import java.nio.file.Path

class RunExecutableActionTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var tab: FileTab
    private val parentDisposable = Disposer.newDisposable("turtle-test-run-executable-action")
    private val scriptName = if (SystemInfo.isWindows) "tool.bat" else "tool.sh"

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        FileContextMenuState.clear()
        tempDir = Files.createTempDirectory("turtle-test-run-executable-")
        Files.writeString(tempDir.resolve("plain.txt"), "x")
        val script = tempDir.resolve(scriptName)
        if (SystemInfo.isWindows) {
            Files.writeString(script, "@echo off\r\necho ran > out.txt\r\n")
        } else {
            Files.writeString(script, "#!/bin/sh\necho ran > out.txt\n")
            script.toFile().setExecutable(true)
        }
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab = panel.getActiveTab()!!
        repeat(100) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (tab.tableModel.rowCount >= 2) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for listing")
    }

    override fun tearDown() {
        try {
            FileContextMenuState.clear()
            Disposer.dispose(parentDisposable)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun selectRowNamed(name: String) {
        for (row in 0 until tab.table.rowCount) {
            if (tab.tableModel.getEntryAt(tab.table.convertRowIndexToModel(row))?.name == name) {
                tab.table.setRowSelectionInterval(row, row)
                return
            }
        }
        fail("No row named $name")
    }

    private fun menuEvent(action: AnAction): AnActionEvent =
        AnActionEvent.createEvent(
            SimpleDataContext.getProjectContext(project),
            action.templatePresentation.clone(),
            FILE_CONTEXT_PLACE,
            ActionUiKind.NONE,
            null,
        )

    fun testRunIsVisibleOnlyForExecutableFiles() {
        val action = RunExecutableAction()
        FileContextMenuState.clickedTab = tab

        selectRowNamed(scriptName)
        FileContextMenuState.clickedEntry = tab.getSelectedEntry()
        val scriptEvent = menuEvent(action)
        action.update(scriptEvent)
        assertTrue(scriptEvent.presentation.isEnabledAndVisible)

        selectRowNamed("plain.txt")
        FileContextMenuState.clickedEntry = tab.getSelectedEntry()
        val plainEvent = menuEvent(action)
        action.update(plainEvent)
        assertFalse(plainEvent.presentation.isEnabledAndVisible)
    }

    fun testRunIsHiddenWithoutMenuState() {
        val action = RunExecutableAction()
        val event = menuEvent(action)
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible)
    }

    fun testRunExecutesTheFileInItsOwnDirectory() {
        selectRowNamed(scriptName)
        FileContextMenuState.clickedTab = tab
        FileContextMenuState.clickedEntry = tab.getSelectedEntry()
        val action = RunExecutableAction()
        action.actionPerformed(menuEvent(action))

        val output = tempDir.resolve("out.txt")
        repeat(100) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (Files.exists(output)) {
                assertEquals("ran", Files.readString(output).trim())
                return
            }
            Thread.sleep(100)
        }
        fail("The executable did not produce out.txt in its directory")
    }
}
