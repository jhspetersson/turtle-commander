package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.enterVfs
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DoubleArchiveOpenIntegrationTest : BasePlatformTestCase() {

    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path
    private lateinit var zipPath: Path
    private lateinit var nestedPath: Path
    private val parentDisposable = Disposer.newDisposable("turtle-test-double-archive-open")

    override fun setUp() {
        super.setUp()
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-double-open-")
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        zipPath = tempDir.resolve("data.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            for (i in 1..200) {
                zos.putNextEntry(ZipEntry("dir$i/file$i.txt"))
                zos.write(ByteArray(2048) { (it % 7).toByte() })
                zos.closeEntry()
            }
        }
        nestedPath = tempDir.resolve("nested.zip")
        ZipOutputStream(Files.newOutputStream(nestedPath)).use { zos ->
            zos.putNextEntry(ZipEntry("inner.zip"))
            zos.write(zipBytes("old.txt"))
            zos.closeEntry()
        }
    }

    private fun zipBytes(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry(name))
            zos.write(byteArrayOf(1))
            zos.closeEntry()
        }
        return out.toByteArray()
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

    private fun entryNamed(tab: FileTab, name: String) =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it) }.first { it.name == name }

    private fun settle() {
        repeat(10) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(100)
        }
    }

    private fun localTab(): FileTab {
        val panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        val tab = panel.getActiveTab()!!
        waitFor("archives listed") { "data.zip" in tableNames(tab) && "nested.zip" in tableNames(tab) }
        return tab
    }

    fun testEnteringAnArchiveTwiceAttachesItOnce() {
        val tab = localTab()
        val entry = entryNamed(tab, "data.zip")

        tab.enterVfs(entry)
        tab.enterVfs(entry)

        waitFor("archive opened") { tab.isInsideArchive && "dir1" in tableNames(tab) }
        settle()
        assertEquals(1, tab.vfsStack.size)
    }

    fun testEnteringTheArchiveTheTabIsAlreadyInsideIsIgnored() {
        val tab = localTab()
        val entry = entryNamed(tab, "data.zip")

        tab.enterVfs(entry)
        waitFor("archive opened") { tab.isInsideArchive && "dir1" in tableNames(tab) }
        settle()
        tab.enterVfs(entry)
        settle()

        assertEquals(1, tab.vfsStack.size)
        assertTrue("dir1" in tableNames(tab))
    }

    fun testNestedArchiveCanBeEnteredAsSoonAsTheOuterListingAppears() {
        val tab = localTab()

        tab.enterVfs(entryNamed(tab, "nested.zip"))
        waitFor("outer archive listed") { tab.vfsStack.size == 1 && "inner.zip" in tableNames(tab) }
        tab.enterVfs(entryNamed(tab, "inner.zip"))
        Thread.sleep(500)

        waitFor("nested archive opened") { tab.vfsStack.size == 2 && "old.txt" in tableNames(tab) }
    }
}
