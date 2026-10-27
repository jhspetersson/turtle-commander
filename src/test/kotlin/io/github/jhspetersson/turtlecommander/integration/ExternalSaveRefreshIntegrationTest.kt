package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.getSelectedEntry
import java.nio.file.Files
import java.nio.file.Path

class ExternalSaveRefreshIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var note: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var tab: FileTab
    private val parentDisposable = Disposer.newDisposable("turtle-test-external-save")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-external-save-")
        note = tempDir.resolve("note.txt")
        Files.writeString(note, "")
        Files.writeString(tempDir.resolve("other.txt"), "xx")
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        val stateService = project.service<FileManagerStateService>()
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        stateService.registerPanels(panel, panel, null)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab = panel.getActiveTab()!!
        waitUntil("listing") { tab.tableModel.rowCount >= 3 }
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

    private fun waitUntil(what: String, condition: () -> Boolean) {
        repeat(100) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for $what")
    }

    private fun rowNamed(name: String): Int =
        (0 until tab.table.rowCount).first {
            tab.tableModel.getEntryAt(tab.table.convertRowIndexToModel(it))?.name == name
        }

    private fun sizeOf(name: String): Long =
        tab.tableModel.getEntryAt(tab.table.convertRowIndexToModel(rowNamed(name)))!!.size

    fun testSavingAFileInTheIdeUpdatesItsSizeInTheListing() {
        assertEquals(0L, sizeOf("note.txt"))
        val row = rowNamed("note.txt")
        tab.table.setRowSelectionInterval(row, row)

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(note)!!
        WriteAction.runAndWait<RuntimeException> { vf.setBinaryContent("hello".toByteArray()) }

        assertFalse(tab.fileOps.isListingCached(tempDir))
        waitUntil("size update") { sizeOf("note.txt") == 5L }
        assertEquals(2L, sizeOf("other.txt"))
        assertEquals("note.txt", tab.getSelectedEntry()?.name)
        assertEquals(5L, tab.listModel.elements().asSequence().first { it.name == "note.txt" }.size)
    }
}
