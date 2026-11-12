package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.service.OverwritePolicy
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.runTransfer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TransferVfsLockIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var zipPath: Path
    private lateinit var leftPanel: FileManagerPanel
    private lateinit var rightPanel: FileManagerPanel
    private lateinit var stateService: FileManagerStateService
    private val parentDisposable = Disposer.newDisposable("turtle-test-transfer-lock")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-transfer-lock-")
        stateService = project.service()
        val left = Files.createDirectories(tempDir.resolve("left"))
        Files.writeString(left.resolve("source.txt"), "payload")
        zipPath = tempDir.resolve("right").resolve("data.zip")
        Files.createDirectories(zipPath.parent)
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            zos.putNextEntry(ZipEntry("existing.txt"))
            zos.write(byteArrayOf(1))
            zos.closeEntry()
        }
        leftPanel = createPanel(left)
        rightPanel = createPanel(zipPath.parent)
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

    private fun openZipIn(panel: FileManagerPanel): FileTab {
        panel.openArchiveEntryInNewTab(zipPath, "existing.txt", false)
        val tab = panel.getActiveTab()!!
        waitFor("zip opened") { tab.isInsideArchive && "existing.txt" in tableNames(tab) }
        return tab
    }

    fun testTabsShowingTheSameArchiveShareOneWriteLock() {
        val a = openZipIn(rightPanel)
        val b = openZipIn(leftPanel)

        assertSame(a.vfsWriteMutex, b.vfsWriteMutex)
    }

    fun testCopyIntoAnArchiveHoldsThatArchivesWriteLockWhileItRuns() {
        val zipTab = openZipIn(rightPanel)
        val localTab = leftPanel.getTabAt(0)!!
        waitFor("source listed") { "source.txt" in tableNames(localTab) }
        val lockedDuringOp = AtomicReference<Boolean?>(null)

        localTab.runTransfer(
            sources = listOf(tempDir.resolve("left/source.txt")),
            destination = zipTab.currentPath,
            initialPolicy = OverwritePolicy.OVERWRITE_ALL,
            taskTitle = "Copying files",
            progressVerb = "Copying",
            errorVerb = "copy",
            op = { _, _, _, _, _, _, _ -> lockedDuringOp.set(zipTab.vfsWriteMutex.isLocked) },
        )

        waitFor("transfer op ran") { lockedDuringOp.get() != null }
        assertEquals(true, lockedDuringOp.get())
    }
}
