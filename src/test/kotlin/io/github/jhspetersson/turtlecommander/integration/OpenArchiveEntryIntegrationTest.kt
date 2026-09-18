package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OpenArchiveEntryIntegrationTest : BasePlatformTestCase() {

    private lateinit var projectPath: Path
    private lateinit var stateService: FileManagerStateService
    private lateinit var tempDir: Path
    private lateinit var jarPath: Path
    private val parentDisposable = Disposer.newDisposable("turtle-test-open-archive-entry")

    override fun setUp() {
        super.setUp()
        projectPath = Path.of(project.basePath!!)
        stateService = project.service()
        tempDir = Files.createTempDirectory("turtle-test-open-archive-entry-")
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        jarPath = tempDir.resolve("lib.jar")
        ZipOutputStream(Files.newOutputStream(jarPath)).use { zos ->
            for (name in listOf("README.txt", "com/example/Foo.class", "com/example/Bar.class")) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
        }
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

    private fun skipIfHeadless(): Boolean = GraphicsEnvironment.isHeadless()

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
        repeat(100) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for $description")
    }

    private fun tableNames(tab: FileTab): List<String> =
        (0 until tab.tableModel.rowCount).mapNotNull { tab.tableModel.getEntryAt(it)?.name }

    private fun selectedName(tab: FileTab): String? {
        val row = tab.table.selectedRow
        if (row < 0) return null
        return tab.tableModel.getEntryAt(tab.table.convertRowIndexToModel(row))?.name
    }

    fun testFileEntryOpensItsDirectoryInsideArchiveAndSelectsIt() {
        if (skipIfHeadless()) return
        val panel = createPanel()

        panel.openArchiveEntryInNewTab(jarPath, "com/example/Foo.class", false)
        val tab = panel.getTabAt(1)!!

        waitFor("tab inside lib.jar at com/example") {
            tab.isInsideArchive && "Foo.class" in tableNames(tab) && "Bar.class" in tableNames(tab)
        }
        assertEquals("example", tab.currentPath.fileName?.toString())
        assertEquals("Foo.class", selectedName(tab))
    }

    fun testDirectoryEntryOpensThatDirectoryInsideArchive() {
        if (skipIfHeadless()) return
        val panel = createPanel()

        panel.openArchiveEntryInNewTab(jarPath, "com", true)
        val tab = panel.getTabAt(1)!!

        waitFor("tab inside lib.jar at com") { tab.isInsideArchive && "example" in tableNames(tab) }
        assertEquals("com", tab.currentPath.fileName?.toString())
    }

    fun testMissingEntryFallsBackToArchiveRoot() {
        if (skipIfHeadless()) return
        val panel = createPanel()

        panel.openArchiveEntryInNewTab(jarPath, "org/missing/Gone.class", false)
        val tab = panel.getTabAt(1)!!

        waitFor("tab inside lib.jar at root") { tab.isInsideArchive && "README.txt" in tableNames(tab) }
        assertTrue(tab.currentVfs!!.isRoot(tab.currentPath))
    }

    fun testJdkClassOpensInsideRuntimeImageAndSelectsIt() {
        if (skipIfHeadless()) return
        val panel = createPanel()
        val modules = Path.of(System.getProperty("java.home")).resolve("lib").resolve("modules")

        panel.openArchiveEntryInNewTab(modules, "java.base/java/lang/String.class", false)
        val tab = panel.getTabAt(1)!!

        waitFor("tab inside the JDK image at java.base/java/lang") {
            tab.isInsideArchive && "String.class" in tableNames(tab)
        }
        assertEquals("lang", tab.currentPath.fileName?.toString())
        assertEquals("String.class", selectedName(tab))
        assertTrue(tab.currentVfs!!.isReadOnly)
    }

    fun testLeavingArchiveReturnsToDirectoryWithArchiveSelected() {
        if (skipIfHeadless()) return
        val panel = createPanel()

        panel.openArchiveEntryInNewTab(jarPath, "com/example/Foo.class", false)
        val tab = panel.getTabAt(1)!!
        waitFor("tab inside lib.jar") { tab.isInsideArchive && "Foo.class" in tableNames(tab) }

        tab.goUp()
        waitFor("tab at com") { "example" in tableNames(tab) && "Foo.class" !in tableNames(tab) }
        tab.goUp()
        waitFor("tab at archive root") { "README.txt" in tableNames(tab) }
        tab.goUp()
        waitFor("tab back on the real filesystem") { !tab.isInsideArchive && "lib.jar" in tableNames(tab) }
        assertEquals(tempDir.toRealPath(), tab.currentPath.toRealPath())
        assertEquals("lib.jar", selectedName(tab))
    }
}
