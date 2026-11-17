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
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NestedInReadOnlyArchiveIntegrationTest : BasePlatformTestCase() {

    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path
    private val parentDisposable = Disposer.newDisposable("turtle-test-nested-in-read-only")

    override fun setUp() {
        super.setUp()
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-nested-in-read-only-")
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
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

    private fun zipBytes(entryName: String, content: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content)
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    private fun tableNames(tab: FileTab): List<String> =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it)?.name }

    private fun openNested(outer: Path): FileTab {
        val panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        panel.openArchiveEntryInNewTab(outer, "inner.zip", false)
        val tab = panel.getActiveTab()!!
        waitFor("outer archive opened") { tab.vfsStack.size == 1 && "inner.zip" in tableNames(tab) }
        val inner = (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it) }.first { it.name == "inner.zip" }
        tab.enterVfs(inner)
        waitFor("inner archive opened") { tab.vfsStack.size == 2 && "a.txt" in tableNames(tab) }
        return tab
    }

    fun testAnArchiveInsideAReadOnlyOneIsReadOnly() {
        val outer = tempDir.resolve("inner.zip.gz")
        GZIPOutputStream(Files.newOutputStream(outer)).use { it.write(zipBytes("a.txt", byteArrayOf(1))) }

        val tab = openNested(outer)

        assertTrue(tab.vfsStack.first().vfs.isReadOnly)
        assertTrue(tab.currentVfs!!.isReadOnly)
    }

    fun testAnArchiveInsideAWritableOneStaysWritable() {
        val outer = tempDir.resolve("outer.zip")
        Files.write(outer, zipBytes("inner.zip", zipBytes("a.txt", byteArrayOf(1))))

        val tab = openNested(outer)

        assertFalse(tab.vfsStack.first().vfs.isReadOnly)
        assertFalse(tab.currentVfs!!.isReadOnly)
    }
}
