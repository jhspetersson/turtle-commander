package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.calculateDirectorySize
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DirectorySizeSingleWalkIntegrationTest : BasePlatformTestCase() {

    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path
    private var savedCalculate = false
    private val parentDisposable = Disposer.newDisposable("turtle-test-directory-size-single-walk")
    private val release = CountDownLatch(1)

    override fun setUp() {
        super.setUp()
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-dir-size-")
        Files.writeString(Files.createDirectory(tempDir.resolve("sub")).resolve("a.txt"), "12345")
        val state = TurtleCommanderSettings.getInstance().state
        savedCalculate = state.calculateDirectorySize
        state.calculateDirectorySize = true
        state.defaultViewMode = "TABLE"
    }

    override fun tearDown() {
        try {
            release.countDown()
            TurtleCommanderSettings.getInstance().state.calculateDirectorySize = savedCalculate
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

    private fun localTab(): FileTab {
        val panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        val tab = panel.getActiveTab()!!
        waitFor("directory listed") { subEntry(tab) != null }
        return tab
    }

    private fun subEntry(tab: FileTab) =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it) }.firstOrNull { it.name == "sub" }

    fun testASecondRequestWhileTheFirstIsRunningDoesNotWalkAgain() {
        val tab = localTab()
        val entry = subEntry(tab)!!
        val walks = AtomicInteger()
        tab.directorySizeWalker = {
            walks.incrementAndGet()
            release.await(10, TimeUnit.SECONDS)
            5L
        }

        tab.calculateDirectorySize(entry)
        waitFor("first walk started") { walks.get() == 1 }
        tab.calculateDirectorySize(entry)
        tab.calculateDirectorySize(entry)
        Thread.sleep(300)
        release.countDown()

        waitFor("size recorded") { tab.directorySizes[entry.path] == 5L }
        assertEquals(1, walks.get())
        assertTrue(tab.directorySizesInFlight.isEmpty())
    }

    fun testTheDefaultWalkerSumsFileSizes() {
        val tab = localTab()
        val entry = subEntry(tab)!!

        tab.calculateDirectorySize(entry)

        waitFor("size recorded") { tab.directorySizes[entry.path] == 5L }
    }
}
