package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import java.nio.file.Files
import java.nio.file.Path

class NewTabSingleListingIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var panel: FileManagerPanel
    private val parentDisposable = Disposer.newDisposable("turtle-test-new-tab-listing")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-new-tab-listing-")
        Files.writeString(tempDir.resolve("a.txt"), "a")
        panel = FileManagerPanel(
            project = project,
            initialPath = tempDir,
            otherPanelPathProvider = { tempDir },
        )
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        waitFor("initial tab") { panel.getActiveTab()?.tableModel?.rowCount?.let { it > 0 } == true }
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
            Thread.sleep(50)
        }
        fail("Timed out waiting for $description")
    }

    private fun names(tab: FileTab): List<String> =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it)?.name }

    fun testOpeningANewTabListsTheDirectoryOnce() {
        val sub = Files.createDirectory(tempDir.resolve("sub"))
        Files.writeString(sub.resolve("inner.txt"), "i")

        panel.openDirectoryInNewTab(sub)
        val tab = panel.getActiveTab()!!
        waitFor("new tab listing") { "inner.txt" in names(tab) }
        repeat(5) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(50)
        }

        assertEquals(1, tab.navigationGeneration.get())
    }
}
