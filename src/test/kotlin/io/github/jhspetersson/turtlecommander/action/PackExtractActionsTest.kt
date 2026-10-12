package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.getSelectedEntry
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackExtractActionsTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var tab: FileTab
    private val parentDisposable = Disposer.newDisposable("turtle-test-pack-extract-actions")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        FileContextMenuState.clear()
        tempDir = Files.createTempDirectory("turtle-test-pack-extract-")
        Files.writeString(tempDir.resolve("plain.txt"), "x")
        ZipOutputStream(Files.newOutputStream(tempDir.resolve("bundle.zip"))).use { zos ->
            zos.putNextEntry(ZipEntry("inner.txt"))
            zos.write(byteArrayOf(1))
            zos.closeEntry()
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

    fun testPackIsEnabledFromTheContextMenuWithASelection() {
        selectRowNamed("plain.txt")
        FileContextMenuState.clickedTab = tab
        FileContextMenuState.clickedEntry = tab.getSelectedEntry()
        val action = PackFilesAction()
        val event = menuEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
    }

    fun testExtractIsVisibleFromTheContextMenuOnlyForArchives() {
        selectRowNamed("bundle.zip")
        FileContextMenuState.clickedTab = tab
        FileContextMenuState.clickedEntry = tab.getSelectedEntry()
        val action = ExtractFilesAction()
        val archiveEvent = menuEvent(action)
        action.update(archiveEvent)
        assertTrue(archiveEvent.presentation.isEnabledAndVisible)

        selectRowNamed("plain.txt")
        FileContextMenuState.clickedEntry = tab.getSelectedEntry()
        val plainEvent = menuEvent(action)
        action.update(plainEvent)
        assertFalse(plainEvent.presentation.isEnabledAndVisible)
    }

    fun testOutsideTheMenuTheEntryComesFromTheResolvedTabNotStaleMenuState() {
        selectRowNamed("bundle.zip")
        FileContextMenuState.clickedTab = tab
        FileContextMenuState.clickedEntry = tab.getSelectedEntry()
        FileContextMenuState.clear()
        assertNull(FileContextMenuState.clickedEntry)
        assertEquals("bundle.zip", resolveFileContextEntry(tab, isMenu = false)?.name)
        assertNull(resolveFileContextEntry(tab, isMenu = true))
    }
}
