package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.nio.file.ExtendedOpenOption
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.service.FileOperationService
import io.github.jhspetersson.turtlecommander.service.OverwritePolicy
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.archiveWriteObstacle
import io.github.jhspetersson.turtlecommander.ui.changeArchiveAndRelist
import io.github.jhspetersson.turtlecommander.ui.runTransfer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ArchiveInUseIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var tarPath: Path
    private lateinit var panel: FileManagerPanel
    private val parentDisposable = Disposer.newDisposable("turtle-test-archive-in-use")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-archive-in-use-")
        Files.writeString(tempDir.resolve("source.txt"), "payload")
        tarPath = tempDir.resolve("data.tar")
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            val entry = TarArchiveEntry("keep.txt")
            entry.size = 1
            tar.putArchiveEntry(entry)
            tar.write(byteArrayOf(1))
            tar.closeArchiveEntry()
        }
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
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

    private fun settle() {
        repeat(5) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(50)
        }
    }

    private fun tableNames(tab: FileTab): List<String> =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it)?.name }

    private fun openTar(): FileTab {
        panel.openArchiveEntryInNewTab(tarPath, "keep.txt", false)
        val tab = panel.getActiveTab()!!
        waitFor("tar opened") { tab.isInsideArchive && "keep.txt" in tableNames(tab) }
        return tab
    }

    fun testAChangeIsRefusedBeforeItStartsWhenTheArchiveIsInUse() {
        val tab = openTar()
        val original = Files.readAllBytes(tarPath)
        val probed = AtomicReference<Path>()
        tab.archiveWriteProbe = { path ->
            probed.set(path)
            IOException("held by another program")
        }
        val ran = AtomicBoolean()
        val failure = AtomicReference<IOException>()

        project.service<FileOperationService>().launch {
            try {
                tab.changeArchiveAndRelist { ran.set(true) }
            } catch (e: IOException) {
                failure.set(e)
            }
        }

        waitFor("refusal") { failure.get() != null }
        assertFalse(ran.get())
        assertEquals(tarPath, probed.get())
        assertTrue(failure.get().message!!.contains("data.tar"))
        assertTrue(failure.get().message!!.contains("held by another program"))
        assertTrue(original.contentEquals(Files.readAllBytes(tarPath)))
    }

    fun testATransferIntoAnArchiveInUseDoesNotStart() {
        val tarTab = openTar()
        val probes = AtomicInteger()
        tarTab.archiveWriteProbe = {
            probes.incrementAndGet()
            IOException("held by another program")
        }
        val ran = AtomicBoolean()

        tarTab.runTransfer(
            sources = listOf(tempDir.resolve("source.txt")),
            destination = tarTab.currentPath,
            initialPolicy = OverwritePolicy.OVERWRITE_ALL,
            taskTitle = "Copying files",
            progressVerb = "Copying",
            errorVerb = "copy",
            op = { _, _, _, _, _, _, _ -> ran.set(true) },
        )

        waitFor("probe") { probes.get() > 0 }
        settle()
        assertFalse(ran.get())
    }

    private fun namesOnDisk(): Set<String> =
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(Files.newInputStream(tarPath)).use { tar ->
            generateSequence { tar.nextEntry }.map { it.name }.toSet()
        }

    fun testTheArchiveIsHeldWhileAChangeRunsAndReleasedBeforeTheSave() {
        val tab = openTar()
        val held = AtomicReference<Path>()
        val released = AtomicBoolean()
        val heldDuringChange = AtomicBoolean()
        tab.archiveHold = { path ->
            held.set(path)
            java.io.Closeable { released.set(true) }
        }
        val doomed = tab.currentPath.resolve("keep.txt")
        Files.writeString(tab.currentPath.resolve("added.txt"), "x")

        project.service<FileOperationService>().launch {
            tab.changeArchiveAndRelist {
                heldDuringChange.set(held.get() == tarPath && !released.get())
                Files.delete(doomed)
            }
        }

        waitFor("archive saved") { namesOnDisk() == setOf("added.txt") }
        assertTrue(heldDuringChange.get())
        assertTrue(released.get())
    }

    fun testAnotherProgramCannotLockTheArchiveWhileAChangeRuns() {
        if (!SystemInfo.isWindows) return
        val tab = openTar()
        val doomed = tab.currentPath.resolve("keep.txt")
        Files.writeString(tab.currentPath.resolve("added.txt"), "x")
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)

        project.service<FileOperationService>().launch {
            tab.changeArchiveAndRelist {
                started.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                Files.delete(doomed)
            }
        }
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val lockAttempt = runCatching {
            FileChannel.open(tarPath, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE).close()
        }
        release.countDown()

        assertTrue(lockAttempt.exceptionOrNull() is java.nio.file.FileSystemException)
        waitFor("archive saved") { namesOnDisk() == setOf("added.txt") }
    }

    fun testAnArchiveNobodyHoldsHasNoObstacle() {
        assertNull(archiveWriteObstacle(tarPath))
        assertNull(archiveWriteObstacle(tempDir.resolve("missing.tar")))
    }

    fun testAnArchiveHeldWithoutWriteSharingIsAnObstacle() {
        if (!SystemInfo.isWindows) return
        FileChannel.open(tarPath, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE).use {
            assertNotNull(archiveWriteObstacle(tarPath))
        }
        assertNull(archiveWriteObstacle(tarPath))
    }
}
