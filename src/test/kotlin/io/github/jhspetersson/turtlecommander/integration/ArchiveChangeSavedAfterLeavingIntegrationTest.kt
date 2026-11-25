package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.service.FileOperationService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.changeArchiveAndRelist
import io.github.jhspetersson.turtlecommander.ui.exitVfs
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ArchiveChangeSavedAfterLeavingIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var tarPath: Path
    private lateinit var panel: FileManagerPanel
    private val parentDisposable = Disposer.newDisposable("turtle-test-archive-change-leave")
    private val release = CountDownLatch(1)

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-archive-change-leave-")
        tarPath = tempDir.resolve("data.tar")
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            for (name in listOf("keep.txt", "doomed.txt")) {
                val entry = TarArchiveEntry(name)
                entry.size = 1
                tar.putArchiveEntry(entry)
                tar.write(byteArrayOf(1))
                tar.closeArchiveEntry()
            }
        }
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
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
        panel.openArchiveEntryInNewTab(tarPath, "keep.txt", false)
        val tab = panel.getActiveTab()!!
        waitFor("tar opened") { tab.isInsideArchive && "doomed.txt" in tableNames(tab) }
        return tab
    }

    fun testADeleteReachesTheArchiveWhenTheTabLeavesItBeforeTheDeleteEnds() {
        val tab = openTar()
        val doomed = tab.currentPath.resolve("doomed.txt")
        val started = CountDownLatch(1)

        project.service<FileOperationService>().launch {
            tab.changeArchiveAndRelist {
                Files.delete(doomed)
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        tab.exitVfs()
        waitFor("tab left the archive") { !tab.isInsideArchive }
        release.countDown()

        waitFor("archive on disk lost the deleted file") { namesOnDisk() == setOf("keep.txt") }
        assertEquals(tempDir, tab.currentPath)
    }

    fun testADeleteIsSavedAndRelistedWhenTheTabStays() {
        val tab = openTar()
        val doomed = tab.currentPath.resolve("doomed.txt")

        project.service<FileOperationService>().launch {
            tab.changeArchiveAndRelist { Files.delete(doomed) }
        }

        waitFor("archive on disk lost the deleted file") { namesOnDisk() == setOf("keep.txt") }
        waitFor("tab relisted") { "doomed.txt" !in tableNames(tab) && "keep.txt" in tableNames(tab) }
        assertTrue(tab.isInsideArchive)
    }
}
