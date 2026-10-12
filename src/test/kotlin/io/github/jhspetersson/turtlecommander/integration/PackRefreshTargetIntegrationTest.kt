package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.getSelectedEntry
import io.github.jhspetersson.turtlecommander.ui.refreshPanelsAfterPack
import java.nio.file.Files
import java.nio.file.Path

class PackRefreshTargetIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var leftDir: Path
    private lateinit var rightDir: Path
    private lateinit var leftPanel: FileManagerPanel
    private lateinit var rightPanel: FileManagerPanel
    private lateinit var stateService: FileManagerStateService
    private val parentDisposable = Disposer.newDisposable("turtle-test-pack-refresh")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-pack-refresh-")
        leftDir = Files.createDirectories(tempDir.resolve("left"))
        rightDir = Files.createDirectories(tempDir.resolve("right"))
        Files.writeString(leftDir.resolve("l.txt"), "l")
        Files.writeString(rightDir.resolve("r.txt"), "r")
        stateService = project.service()
        leftPanel = createPanel(leftDir, rightDir)
        rightPanel = createPanel(rightDir, leftDir)
        leftPanel.otherPanel = rightPanel
        rightPanel.otherPanel = leftPanel
        stateService.registerPanels(leftPanel, rightPanel, null)
        waitFor("left listing") { leftPanel.getActiveTab()!!.tableModel.rowCount >= 1 }
        waitFor("right listing") { rightPanel.getActiveTab()!!.tableModel.rowCount >= 1 }
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

    private fun createPanel(root: Path, other: Path): FileManagerPanel {
        val panel = FileManagerPanel(project = project, initialPath = root, otherPanelPathProvider = { other })
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

    private fun FileTab.lists(name: String): Boolean =
        (0 until tableModel.rowCount).any { tableModel.getEntryAt(it)?.name == name }

    private fun packedInto(source: FileTab, destinationDir: Path): Path {
        val archive = destinationDir.resolve("packed.zip")
        Files.writeString(archive, "PK")
        source.fileOps.launch { source.refreshPanelsAfterPack(archive) }
        return archive
    }

    fun testArchivePackedFromLeftIntoRightDirRefreshesAndSelectsItInTheRightPanel() {
        assertFalse(leftPanel.hasFocusInPanel())
        assertFalse(rightPanel.hasFocusInPanel())
        val rightTab = rightPanel.getActiveTab()!!
        packedInto(leftPanel.getActiveTab()!!, rightDir)
        waitFor("right panel to list the archive") { rightTab.lists("packed.zip") }
        waitFor("right panel to select the archive") { rightTab.getSelectedEntry()?.name == "packed.zip" }
    }

    fun testArchivePackedFromRightIntoLeftDirRefreshesAndSelectsItInTheLeftPanel() {
        val leftTab = leftPanel.getActiveTab()!!
        packedInto(rightPanel.getActiveTab()!!, leftDir)
        waitFor("left panel to list the archive") { leftTab.lists("packed.zip") }
        waitFor("left panel to select the archive") { leftTab.getSelectedEntry()?.name == "packed.zip" }
    }

    fun testArchivePackedIntoTheSourceDirRefreshesBothPanels() {
        val leftTab = leftPanel.getActiveTab()!!
        packedInto(leftTab, leftDir)
        waitFor("left panel to list the archive") { leftTab.lists("packed.zip") }
        waitFor("left panel to select the archive") { leftTab.getSelectedEntry()?.name == "packed.zip" }
    }
}
