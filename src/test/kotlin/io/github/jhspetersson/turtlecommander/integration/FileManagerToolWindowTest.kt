package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import io.github.jhspetersson.turtlecommander.service.FileOperationService
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileManagerToolWindowFactory
import kotlinx.coroutines.runBlocking
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path

class FileManagerToolWindowTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("turtle-test-toolwindow-")
    }

    override fun tearDown() {
        try {
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testToolWindowFactoryCreatesContentViaDirectInvocation() {
        if (GraphicsEnvironment.isHeadless()) return

        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindowId = "Turtle Commander"

        val toolWindow = toolWindowManager.registerToolWindow(toolWindowId) {}

        val factory = FileManagerToolWindowFactory()
        factory.createToolWindowContent(project, toolWindow)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val contentManager = toolWindow.contentManager
        assertTrue("Tool window should have content after factory creates it", contentManager.contentCount > 0)

        val content = contentManager.getContent(0)
        assertNotNull("First content entry should not be null", content)
    }

    fun testFileOperationServiceListsProjectFiles() {
        Files.writeString(tempDir.resolve("a.txt"), "x")
        Files.createDirectory(tempDir.resolve("sub"))
        val fileOps = project.service<FileOperationService>()

        val entries = runBlocking { fileOps.listFiles(tempDir) }

        assertTrue("File listing should return entries for the directory", entries.isNotEmpty())

        for (entry in entries) {
            assertTrue("Entry name must not be blank", entry.name.isNotBlank())
            assertNotNull("Entry path must not be null", entry.path)
        }

        val parentLink = entries.find { it.isParentLink }
        assertNotNull("File listing should include a parent link", parentLink)
    }

    fun testFileOperationServiceListsFilesWithCorrectAttributes() {
        Files.writeString(tempDir.resolve("a.txt"), "x")
        Files.createDirectory(tempDir.resolve("sub"))
        val fileOps = project.service<FileOperationService>()

        val entries = runBlocking { fileOps.listFiles(tempDir) }

        val realEntries = entries.filter { !it.isParentLink }
        assertTrue("Should list the created entries", realEntries.isNotEmpty())

        for (entry in realEntries) {
            assertTrue(
                "Entry path ${entry.path} should be under temp dir $tempDir",
                entry.path.startsWith(tempDir)
            )
            assertEquals(
                "Entry name should match path file name",
                entry.path.fileName.toString(),
                entry.name
            )
        }
    }

    fun testFileOperationServiceThrowsOnUnlistableDirectory() {
        val fileOps = project.service<FileOperationService>()
        val missing = tempDir.resolve("does-not-exist")

        try {
            runBlocking { fileOps.listFiles(missing) }
            fail("listFiles should propagate an exception for an unlistable directory")
        } catch (_: java.nio.file.NoSuchFileException) {
            // expected
        } catch (_: java.io.IOException) {
            // some platforms throw a different IOException subtype
        }
    }

    fun testFileManagerPanelCreatesWithTab() {
        if (GraphicsEnvironment.isHeadless()) return

        val projectPath = Path.of(project.basePath!!)
        val stateService = project.service<FileManagerStateService>()

        val panel = FileManagerPanel(
            project = project,
            initialPath = projectPath,
            otherPanelPathProvider = { projectPath },
        )
        panel.restoreState(stateService.state.leftPanel, stateService)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val activeTab = panel.getActiveTab()
        assertNotNull("FileManagerPanel should have an active tab after restoreState", activeTab)
    }

    fun testFileManagerStateServiceAvailable() {
        val stateService = project.service<FileManagerStateService>()
        assertNotNull("FileManagerStateService should be available", stateService)
        assertNotNull("State should not be null", stateService.state)
    }
}
