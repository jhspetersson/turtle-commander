package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.enterVfs
import io.github.jhspetersson.turtlecommander.ui.exitVfs
import io.github.jhspetersson.turtlecommander.ui.performRename
import io.github.jhspetersson.turtlecommander.ui.runMultiRename
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ArchiveRenameWriteBackIntegrationTest : BasePlatformTestCase() {

    private lateinit var projectPath: Path
    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path
    private lateinit var outerZip: Path
    private lateinit var topTar: Path
    private val parentDisposable = Disposer.newDisposable("turtle-test-archive-rename-write-back")

    override fun setUp() {
        super.setUp()
        projectPath = Path.of(project.basePath!!)
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-archive-rename-")
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        outerZip = tempDir.resolve("outer.zip")
        Files.write(
            outerZip,
            zipBytes(
                "inner.zip" to zipBytes("old.txt" to "payload".toByteArray()),
                "inner.tar" to tarBytes("old.txt" to "payload".toByteArray()),
            ),
        )
        topTar = tempDir.resolve("top.tar")
        Files.write(topTar, tarBytes("a.txt" to "a".toByteArray(), "b.txt" to "b".toByteArray()))
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

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun tarBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tos ->
            for ((name, data) in entries) {
                val entry = TarArchiveEntry(name)
                entry.size = data.size.toLong()
                tos.putArchiveEntry(entry)
                tos.write(data)
                tos.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private fun zipNames(bytes: ByteArray): Set<String> =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            generateSequence { zis.nextEntry }.map { it.name }.toSet()
        }

    private fun tarNames(bytes: ByteArray): Set<String> =
        TarArchiveInputStream(ByteArrayInputStream(bytes)).use { tis ->
            generateSequence { tis.nextEntry }.map { it.name }.toSet()
        }

    private fun innerNamesOnDisk(innerName: String): Set<String>? = try {
        ZipFile(outerZip.toFile()).use { zf ->
            val entry = zf.getEntry(innerName)
            if (entry == null) {
                null
            } else {
                val bytes = zf.getInputStream(entry).readBytes()
                if (innerName.endsWith(".zip")) zipNames(bytes) else tarNames(bytes)
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun topTarNamesOnDisk(): Set<String>? = try {
        tarNames(Files.readAllBytes(topTar))
    } catch (_: Exception) {
        null
    }

    private fun createPanel(): FileManagerPanel {
        val panel = FileManagerPanel(
            project = project,
            initialPath = projectPath,
            otherPanelPathProvider = { projectPath },
        )
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return panel
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        repeat(200) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for $description")
    }

    private fun tableNames(tab: FileTab): List<String> =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it)?.name }

    private fun entryNamed(tab: FileTab, name: String): FileEntry =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it) }.first { it.name == name }

    private fun openNested(panel: FileManagerPanel, innerName: String): FileTab {
        panel.openArchiveEntryInNewTab(outerZip, innerName, false)
        val tab = panel.getTabAt(1)!!
        waitFor("tab inside outer.zip") { tab.isInsideArchive && innerName in tableNames(tab) }
        tab.enterVfs(entryNamed(tab, innerName))
        waitFor("tab inside $innerName") { tab.vfsStack.size == 2 && "old.txt" in tableNames(tab) }
        return tab
    }

    private fun leaveArchives(tab: FileTab) {
        tab.exitVfs()
        waitFor("tab back in outer.zip") { tab.vfsStack.size == 1 && "inner.zip" in tableNames(tab) }
        tab.closeVfsStackAsync()
        waitFor("tab back on the real filesystem") { !tab.isInsideArchive }
    }

    fun testRenameInsideNestedZipIsWrittenBackToOuterZip() {
        val tab = openNested(createPanel(), "inner.zip")

        tab.performRename(entryNamed(tab, "old.txt"), "new.txt")
        waitFor("renamed entry listed") { "new.txt" in tableNames(tab) && "old.txt" !in tableNames(tab) }
        leaveArchives(tab)

        waitFor("outer.zip holds the renamed inner.zip entry") { innerNamesOnDisk("inner.zip") == setOf("new.txt") }
    }

    fun testRenameInsideNestedTarIsWrittenBackToOuterZip() {
        val tab = openNested(createPanel(), "inner.tar")

        tab.performRename(entryNamed(tab, "old.txt"), "new.txt")
        waitFor("renamed entry listed") { "new.txt" in tableNames(tab) && "old.txt" !in tableNames(tab) }
        leaveArchives(tab)

        waitFor("outer.zip holds the renamed inner.tar entry") { innerNamesOnDisk("inner.tar") == setOf("new.txt") }
    }

    fun testMultiRenameInsideNestedZipIsWrittenBackToOuterZip() {
        val tab = openNested(createPanel(), "inner.zip")

        tab.runMultiRename(listOf(entryNamed(tab, "old.txt") to "new.txt"))
        waitFor("renamed entry listed") { "new.txt" in tableNames(tab) && "old.txt" !in tableNames(tab) }
        leaveArchives(tab)

        waitFor("outer.zip holds the renamed inner.zip entry") { innerNamesOnDisk("inner.zip") == setOf("new.txt") }
    }

    fun testMultiRenameInsideTopLevelTarIsPersisted() {
        val panel = createPanel()
        panel.openArchiveEntryInNewTab(topTar, "a.txt", false)
        val tab = panel.getTabAt(1)!!
        waitFor("tab inside top.tar") { tab.isInsideArchive && "a.txt" in tableNames(tab) && "b.txt" in tableNames(tab) }

        tab.runMultiRename(listOf(entryNamed(tab, "a.txt") to "x_a.txt", entryNamed(tab, "b.txt") to "x_b.txt"))
        waitFor("renamed entries listed") { tableNames(tab).containsAll(listOf("x_a.txt", "x_b.txt")) && "a.txt" !in tableNames(tab) }
        tab.closeVfsStackAsync()
        waitFor("tab back on the real filesystem") { !tab.isInsideArchive }

        waitFor("top.tar holds the renamed entries") { topTarNamesOnDisk() == setOf("x_a.txt", "x_b.txt") }
    }
}
