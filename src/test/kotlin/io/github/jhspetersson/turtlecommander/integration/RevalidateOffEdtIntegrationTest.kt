package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.service.FileOperationService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList

class RevalidateOffEdtIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var fileOps: FileOperationService
    private lateinit var originalStamp: (Path) -> FileTime
    private val parentDisposable = Disposer.newDisposable("turtle-test-revalidate-off-edt")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-revalidate-")
        Files.writeString(tempDir.resolve("a.txt"), "a")
        fileOps = project.service()
        originalStamp = fileOps.directoryStamp
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
            fileOps.directoryStamp = originalStamp
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

    fun testTheDirectoryIsNotStattedOnTheEdtWhenATabBecomesActive() {
        val tab = panel.getActiveTab()!!
        val onEdt = CopyOnWriteArrayList<Boolean>()
        fileOps.directoryStamp = { path ->
            onEdt.add(ApplicationManager.getApplication().isDispatchThread)
            originalStamp(path)
        }

        tab.revalidateIfStale()

        waitFor("freshness check") { onEdt.isNotEmpty() }
        assertEquals(listOf(false), onEdt.distinct())
    }

    fun testAnExternallyChangedDirectoryIsRelisted() {
        val tab = panel.getActiveTab()!!
        Files.writeString(tempDir.resolve("b.txt"), "b")
        Files.setLastModifiedTime(tempDir, FileTime.fromMillis(System.currentTimeMillis() + 5000))

        tab.revalidateIfStale()

        waitFor("relisting") { "b.txt" in names(tab) }
    }

    fun testAnUnchangedDirectoryIsNotRelisted() {
        val tab = panel.getActiveTab()!!
        val generation = tab.navigationGeneration.get()
        val checks = CopyOnWriteArrayList<Path>()
        fileOps.directoryStamp = { path ->
            checks.add(path)
            originalStamp(path)
        }

        tab.revalidateIfStale()

        waitFor("freshness check") { checks.isNotEmpty() }
        repeat(5) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(50)
        }
        assertEquals(generation, tab.navigationGeneration.get())
    }
}
