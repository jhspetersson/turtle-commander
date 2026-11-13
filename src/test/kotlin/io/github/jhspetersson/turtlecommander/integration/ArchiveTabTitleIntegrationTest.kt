package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveTabTitleIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var jarPath: Path
    private lateinit var tarPath: Path
    private lateinit var leftPanel: FileManagerPanel
    private lateinit var rightPanel: FileManagerPanel
    private lateinit var stateService: FileManagerStateService
    private val parentDisposable = Disposer.newDisposable("turtle-test-archive-tab-title")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-archive-tab-title-")
        jarPath = tempDir.resolve("lib.jar")
        ZipOutputStream(Files.newOutputStream(jarPath)).use { zos ->
            for (name in listOf("README.txt", "com/example/Foo.class")) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
        }
        tarPath = tempDir.resolve("lib.tar")
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tos ->
            val entry = TarArchiveEntry("com/example/Foo.class")
            entry.size = 3
            tos.putArchiveEntry(entry)
            tos.write(byteArrayOf(1, 2, 3))
            tos.closeArchiveEntry()
        }
        stateService = project.service()
        leftPanel = createPanel(tempDir)
        rightPanel = createPanel(Files.createDirectories(tempDir.resolve("right")))
        leftPanel.otherPanel = rightPanel
        rightPanel.otherPanel = leftPanel
        stateService.registerPanels(leftPanel, rightPanel, null)
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
        val panel = FileManagerPanel(project = project, initialPath = root, otherPanelPathProvider = { root })
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

    private fun openArchiveDirectory(archive: Path, directory: String, marker: String): FileTab {
        leftPanel.openArchiveEntryInNewTab(archive, directory, true)
        waitFor("archive directory $directory opened") {
            val tab = leftPanel.getActiveTab()
            tab != null && tab.isInsideArchive && marker in tableNames(tab)
        }
        return leftPanel.getActiveTab()!!
    }

    fun testTabTitleInsideAnArchiveIsTheDirectoryName() {
        openArchiveDirectory(jarPath, "com/example", "Foo.class")

        assertEquals("example", leftPanel.tabTitleAt(leftPanel.getActiveTabIndex()))
    }

    fun testTabTitleAtTheZipRootIsTheArchiveName() {
        openArchiveDirectory(jarPath, "", "README.txt")

        assertEquals("lib.jar", leftPanel.tabTitleAt(leftPanel.getActiveTabIndex()))
    }

    fun testTabTitleAtTheTarRootIsTheArchiveName() {
        openArchiveDirectory(tarPath, "", "com")

        assertEquals("lib.tar", leftPanel.tabTitleAt(leftPanel.getActiveTabIndex()))
    }

    fun testTabTitleSurvivesMovingTheTabToTheOtherPanel() {
        val tab = openArchiveDirectory(jarPath, "", "README.txt")

        leftPanel.moveTabToOtherPanel(leftPanel.getActiveTabIndex(), rightPanel, 1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertSame(tab, rightPanel.getActiveTab())
        assertEquals("lib.jar", rightPanel.tabTitleAt(rightPanel.getActiveTabIndex()))
    }
}
