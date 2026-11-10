package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.vfsRelativePath
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NewTabInsideArchiveIntegrationTest : BasePlatformTestCase() {

    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path
    private lateinit var jarPath: Path
    private lateinit var panel: FileManagerPanel
    private val parentDisposable = Disposer.newDisposable("turtle-test-new-tab-inside-archive")

    override fun setUp() {
        super.setUp()
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-new-tab-archive-")
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        jarPath = tempDir.resolve("lib.jar")
        ZipOutputStream(Files.newOutputStream(jarPath)).use { zos ->
            for (name in listOf("README.txt", "com/example/Foo.class", "com/example/Bar.class")) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
        }
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
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

    private fun relativeDir(tab: FileTab): String =
        vfsRelativePath(tab.vfsStack.single().vfs, tab.currentPath).replace('\\', '/')

    private fun openArchiveDirectory(): FileTab {
        panel.openArchiveEntryInNewTab(jarPath, "com/example", true)
        waitFor("archive directory opened") {
            val tab = panel.getActiveTab()
            tab != null && tab.isInsideArchive && "Foo.class" in tableNames(tab)
        }
        return panel.getActiveTab()!!
    }

    fun testNewTabFromInsideAnArchiveStaysInThatArchiveDirectory() {
        val archiveTab = openArchiveDirectory()

        panel.openNewTab()
        waitFor("new tab opened inside the archive") {
            val tab = panel.getActiveTab()
            tab != null && tab !== archiveTab && tab.isInsideArchive && "Foo.class" in tableNames(tab)
        }

        val opened = panel.getActiveTab()!!
        assertEquals("com/example", relativeDir(opened))
        assertEquals(jarPath, opened.vfsStack.single().parentPath)
    }

    fun testOpeningAnArchiveDirectoryPathInNewTabEntersTheArchive() {
        val archiveTab = openArchiveDirectory()
        val comDir = archiveTab.currentPath.parent

        panel.openDirectoryInNewTab(comDir, selectName = "example")
        waitFor("new tab opened at the parent directory") {
            val tab = panel.getActiveTab()
            tab != null && tab !== archiveTab && tab.isInsideArchive && "example" in tableNames(tab)
        }

        val opened = panel.getActiveTab()!!
        assertEquals("com", relativeDir(opened))
        assertEquals(jarPath, opened.vfsStack.single().parentPath)
    }
}
