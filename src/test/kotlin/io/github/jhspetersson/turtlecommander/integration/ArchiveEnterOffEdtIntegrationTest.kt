package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemProvider
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveEnterOffEdtIntegrationTest : BasePlatformTestCase() {

    private object ProbeProvider : VirtualFileSystemProvider {
        val probedOnEdt = CopyOnWriteArrayList<Boolean>()

        override fun supports(path: Path): Boolean {
            if (!supportsName(path)) return false
            probedOnEdt.add(ApplicationManager.getApplication().isDispatchThread)
            return false
        }

        override fun supportsExtension(ext: String): Boolean = ext == "turtleedtprobe"

        override fun create(archivePath: Path): VirtualFileSystem = throw UnsupportedOperationException()
    }

    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path
    private val parentDisposable = Disposer.newDisposable("turtle-test-archive-enter-off-edt")

    override fun setUp() {
        super.setUp()
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-enter-off-edt-")
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        ZipOutputStream(Files.newOutputStream(tempDir.resolve("data.zip"))).use { zos ->
            zos.putNextEntry(ZipEntry("inside.txt"))
            zos.write(byteArrayOf(1))
            zos.closeEntry()
        }
        Files.write(tempDir.resolve("probe.turtleedtprobe"), byteArrayOf(1))
        ProbeProvider.probedOnEdt.clear()
        if (!registered) {
            VirtualFileSystemRegistry.register(ProbeProvider)
            registered = true
        }
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

    private fun waitFor(description: String, condition: () -> Boolean) {
        repeat(100) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for $description")
    }

    private fun tableNames(tab: FileTab): List<String> =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it)?.name }

    private fun select(tab: FileTab, name: String) {
        val row = (0 until tab.table.rowCount).first {
            tab.tableModel.getEntryAt(tab.table.convertRowIndexToModel(it))?.name == name
        }
        tab.table.setRowSelectionInterval(row, row)
    }

    private fun localTab(): FileTab {
        val panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        val tab = panel.getActiveTab()!!
        waitFor("files listed") { "data.zip" in tableNames(tab) && "probe.turtleedtprobe" in tableNames(tab) }
        return tab
    }

    fun testArchiveCheckRunsOffTheEdt() {
        val tab = localTab()
        select(tab, "probe.turtleedtprobe")
        ProbeProvider.probedOnEdt.clear()

        tab.openSelectedEntry()

        waitFor("archive check") { ProbeProvider.probedOnEdt.isNotEmpty() }
        assertEquals(listOf(false), ProbeProvider.probedOnEdt.distinct())
    }

    fun testEnterStillOpensAnArchive() {
        val tab = localTab()
        select(tab, "data.zip")

        tab.openSelectedEntry()

        waitFor("archive opened") { tab.isInsideArchive && "inside.txt" in tableNames(tab) }
    }

    companion object {
        private var registered = false
    }
}
