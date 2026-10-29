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
import io.github.jhspetersson.turtlecommander.ui.RenameCollision
import io.github.jhspetersson.turtlecommander.ui.RenameCollisionChoice
import io.github.jhspetersson.turtlecommander.ui.performRename
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JTextField

class RenameCollisionIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var tab: FileTab
    private val parentDisposable = Disposer.newDisposable("turtle-test-rename-collision")
    private val collisions = mutableListOf<RenameCollision>()
    private val existingNameRequests = mutableListOf<Path>()

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-rename-collision-")
        Files.writeString(tempDir.resolve("a.txt"), "a")
        Files.writeString(tempDir.resolve("b.txt"), "b")
        Files.writeString(tempDir.resolve("d.txt"), "d")
        Files.createDirectory(tempDir.resolve("full"))
        Files.writeString(tempDir.resolve("full").resolve("child.txt"), "c")
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab = panel.getActiveTab()!!
        waitFor("listing") { "d.txt" in tableNames(tab) }
        tab.askRenameCollision = { collisions.add(it); RenameCollisionChoice.CANCEL }
        tab.askRenameExistingName = { existingNameRequests.add(it); null }
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
        repeat(200) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for $description")
    }

    private fun settle() {
        repeat(10) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(50)
        }
    }

    private fun tableNames(tab: FileTab): List<String> =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it)?.name }

    private fun entryNamed(tab: FileTab, name: String): FileEntry =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it) }.first { it.name == name }

    private fun content(name: String): String? =
        tempDir.resolve(name).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }

    fun testRenameWithoutCollisionNeverAsks() {
        tab.performRename(entryNamed(tab, "a.txt"), "z.txt")
        waitFor("renamed") { content("z.txt") == "a" && content("a.txt") == null }
        assertTrue(collisions.isEmpty())
    }

    fun testOverwriteReplacesTheExistingFile() {
        tab.askRenameCollision = { collisions.add(it); RenameCollisionChoice.OVERWRITE }

        tab.performRename(entryNamed(tab, "a.txt"), "b.txt")

        waitFor("b.txt holds a's content") { content("b.txt") == "a" && content("a.txt") == null }
        assertEquals(1, collisions.size)
        assertEquals("b.txt", collisions[0].newName)
        assertTrue(collisions[0].canOverwrite)
        assertFalse(collisions[0].targetIsDirectory)
    }

    fun testRenameExistingMovesTheTargetAsideFirst() {
        tab.askRenameCollision = { RenameCollisionChoice.RENAME_TARGET }
        tab.askRenameExistingName = { existingNameRequests.add(it); "c.txt" }

        tab.performRename(entryNamed(tab, "a.txt"), "b.txt")

        waitFor("both renamed") { content("c.txt") == "b" && content("b.txt") == "a" && content("a.txt") == null }
        assertEquals(listOf(tempDir.resolve("b.txt")), existingNameRequests)
    }

    fun testRenameExistingToTakenNameChangesNothing() {
        tab.askRenameCollision = { RenameCollisionChoice.RENAME_TARGET }
        tab.askRenameExistingName = { "d.txt" }

        tab.performRename(entryNamed(tab, "a.txt"), "b.txt")

        settle()
        assertEquals("a", content("a.txt"))
        assertEquals("b", content("b.txt"))
        assertEquals("d", content("d.txt"))
    }

    fun testCancellingTheExistingNamePromptChangesNothing() {
        tab.askRenameCollision = { RenameCollisionChoice.RENAME_TARGET }
        tab.askRenameExistingName = { null }

        tab.performRename(entryNamed(tab, "a.txt"), "b.txt")

        settle()
        assertEquals("a", content("a.txt"))
        assertEquals("b", content("b.txt"))
    }

    fun testKeepEditingReopensTheInlineEditorWithTheRejectedName() {
        tab.askRenameCollision = { RenameCollisionChoice.KEEP_EDITING }

        tab.performRename(entryNamed(tab, "a.txt"), "b.txt")

        waitFor("inline editor open") { tab.table.isEditing }
        assertEquals("b.txt", (tab.table.editorComponent as JTextField).text)
        assertEquals("a", content("a.txt"))
        assertEquals("b", content("b.txt"))
        tab.table.cellEditor.cancelCellEditing()
    }

    fun testCancelChangesNothing() {
        tab.performRename(entryNamed(tab, "a.txt"), "b.txt")

        settle()
        assertEquals(1, collisions.size)
        assertEquals("a", content("a.txt"))
        assertEquals("b", content("b.txt"))
        assertTrue(existingNameRequests.isEmpty())
    }

    fun testNonEmptyDirectoryTargetIsReportedAsNotOverwritable() {
        tab.performRename(entryNamed(tab, "a.txt"), "full")

        waitFor("asked") { collisions.size == 1 }
        assertTrue(collisions[0].targetIsDirectory)
        assertFalse(collisions[0].canOverwrite)
        settle()
        assertEquals("a", content("a.txt"))
        assertEquals("c", Files.readString(tempDir.resolve("full").resolve("child.txt")))
    }

    fun testOverwriteInsideTarArchiveIsPersisted() {
        val tar = tempDir.resolve("top.tar")
        Files.write(tar, tarBytes("a.txt" to "a".toByteArray(), "b.txt" to "b".toByteArray()))
        panel.openArchiveEntryInNewTab(tar, "a.txt", false)
        val archiveTab = panel.getTabAt(1)!!
        waitFor("tab inside top.tar") { archiveTab.isInsideArchive && "b.txt" in tableNames(archiveTab) }
        archiveTab.askRenameCollision = { RenameCollisionChoice.OVERWRITE }

        archiveTab.performRename(entryNamed(archiveTab, "a.txt"), "b.txt")

        waitFor("only b.txt listed") { tableNames(archiveTab).filter { it.endsWith(".txt") } == listOf("b.txt") }
        waitFor("tar on disk rewritten") { tarEntries(Files.readAllBytes(tar)) == mapOf("b.txt" to "a") }
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

    private fun tarEntries(bytes: ByteArray): Map<String, String> =
        TarArchiveInputStream(ByteArrayInputStream(bytes)).use { tis ->
            generateSequence { tis.nextEntry }.associate { it.name to String(tis.readAllBytes()) }
        }
}
