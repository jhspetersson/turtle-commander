package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.enterVfs
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DoubleArchiveOpenIntegrationTest : BasePlatformTestCase() {

    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path
    private lateinit var zipPath: Path
    private val parentDisposable = Disposer.newDisposable("turtle-test-double-archive-open")

    override fun setUp() {
        super.setUp()
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-double-open-")
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        zipPath = tempDir.resolve("data.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            for (i in 1..200) {
                zos.putNextEntry(ZipEntry("dir$i/file$i.txt"))
                zos.write(ByteArray(2048) { (it % 7).toByte() })
                zos.closeEntry()
            }
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

    fun testEnteringAnArchiveTwiceAttachesItOnce() {
        val panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        val tab = panel.getActiveTab()!!
        waitFor("archive listed") { "data.zip" in tableNames(tab) }
        val entry = (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it) }.first { it.name == "data.zip" }

        tab.enterVfs(entry)
        tab.enterVfs(entry)

        waitFor("archive opened") { tab.isInsideArchive && "dir1" in tableNames(tab) }
        repeat(10) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(100)
        }
        assertEquals(1, tab.vfsStack.size)
    }
}
