package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.dialog.validateExtractDestination
import io.github.jhspetersson.turtlecommander.dialog.validatePackArchivePath
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.resolveDestinationPath
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackBaseInsideArchiveIntegrationTest : BasePlatformTestCase() {

    private lateinit var projectPath: Path
    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path
    private lateinit var zipPath: Path
    private val parentDisposable = Disposer.newDisposable("turtle-test-pack-base-inside-archive")

    override fun setUp() {
        super.setUp()
        projectPath = Path.of(project.basePath!!)
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-pack-base-")
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        zipPath = tempDir.resolve("lib.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            for (name in listOf("README.txt", "docs/guide.txt")) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(byteArrayOf(1, 2, 3))
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

    private fun createPanel(): FileManagerPanel {
        val panel = FileManagerPanel(
            project = project,
            initialPath = projectPath,
            otherPanelPathProvider = { projectPath },
        )
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return panel
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

    fun testRelativeDialogInputInsideZipResolvesNextToTheArchive() {
        val panel = createPanel()
        panel.openArchiveEntryInNewTab(zipPath, "docs", true)
        val tab = panel.getActiveTab()!!
        waitFor("tab inside lib.zip at docs") { tab.isInsideArchive && "guide.txt" in tableNames(tab) }

        assertNotSame(FileSystems.getDefault(), tab.currentPath.fileSystem)
        assertEquals(zipPath.parent, tab.realFilesystemPath)
        val base = tab.destinationBasePath
        assertSame(FileSystems.getDefault(), base.fileSystem)
        assertEquals(zipPath.parent, base)
        assertNull(validatePackArchivePath("out.zip", base))
        assertNull(validateExtractDestination("out", base))
        assertEquals(zipPath.parent.resolve("out.zip"), resolveDestinationPath("out.zip", base))
    }
}
