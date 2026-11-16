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
import io.github.jhspetersson.turtlecommander.ui.exitVfs
import io.github.jhspetersson.turtlecommander.ui.runTransfer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TransferFlushesDestinationArchiveIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var tarPath: Path
    private lateinit var leftPanel: FileManagerPanel
    private lateinit var rightPanel: FileManagerPanel
    private lateinit var stateService: FileManagerStateService
    private val parentDisposable = Disposer.newDisposable("turtle-test-transfer-flush")
    private val release = CountDownLatch(1)

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-transfer-flush-")
        stateService = project.service()
        val left = Files.createDirectories(tempDir.resolve("left"))
        Files.writeString(left.resolve("source.txt"), "payload")
        tarPath = tempDir.resolve("right").resolve("data.tar")
        Files.createDirectories(tarPath.parent)
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            val bytes = byteArrayOf(1)
            val entry = TarArchiveEntry("existing.txt")
            entry.size = bytes.size.toLong()
            tar.putArchiveEntry(entry)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
        leftPanel = createPanel(left)
        rightPanel = createPanel(tarPath.parent)
        leftPanel.otherPanel = rightPanel
        rightPanel.otherPanel = leftPanel
        stateService.registerPanels(leftPanel, rightPanel, null)
    }

    override fun tearDown() {
        try {
            release.countDown()
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

    private fun namesOnDisk(): Set<String> =
        TarArchiveInputStream(Files.newInputStream(tarPath)).use { tar ->
            generateSequence { tar.nextEntry }.map { it.name }.toSet()
        }

    private fun openTar(): FileTab {
        rightPanel.openArchiveEntryInNewTab(tarPath, "existing.txt", false)
        val tab = rightPanel.getActiveTab()!!
        waitFor("tar opened") { tab.isInsideArchive && "existing.txt" in tableNames(tab) }
        return tab
    }

    private fun transferInto(tarTab: FileTab, started: CountDownLatch) {
        val localTab = leftPanel.getTabAt(0)!!
        localTab.runTransfer(
            sources = listOf(tempDir.resolve("left/source.txt")),
            destination = tarTab.currentPath,
            initialPolicy = OverwritePolicy.OVERWRITE_ALL,
            taskTitle = "Moving files",
            progressVerb = "Moving",
            errorVerb = "move",
            op = { sources, destination, _, _, _, _, _ ->
                Files.copy(sources.single(), destination.resolve("moved.txt"))
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
            },
        )
    }

    fun testFilesReachTheArchiveWhenItsTabLeavesBeforeTheTransferEnds() {
        val tarTab = openTar()
        val started = CountDownLatch(1)

        transferInto(tarTab, started)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        tarTab.exitVfs()
        waitFor("tab left the archive") { !tarTab.isInsideArchive }
        release.countDown()

        waitFor("archive on disk holds the moved file") { namesOnDisk() == setOf("existing.txt", "moved.txt") }
    }

    fun testFilesReachTheArchiveWhenAnotherTabIsActiveAtTheEnd() {
        val tarTab = openTar()
        val started = CountDownLatch(1)

        transferInto(tarTab, started)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        rightPanel.openDirectoryInNewTab(tempDir)
        waitFor("another tab is active") { rightPanel.getActiveTab() !== tarTab }
        release.countDown()

        waitFor("archive on disk holds the moved file") { namesOnDisk() == setOf("existing.txt", "moved.txt") }
        waitFor("archive tab lists the moved file") { "moved.txt" in tableNames(tarTab) }
    }
}
