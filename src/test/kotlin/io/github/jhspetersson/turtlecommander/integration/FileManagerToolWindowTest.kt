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
import java.nio.file.Path

class FileManagerToolWindowTest : BasePlatformTestCase() {

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
        val projectPath = Path.of(project.basePath!!)
        val fileOps = project.service<FileOperationService>()

        val entries = runBlocking { fileOps.listFiles(projectPath) }

        assertTrue("File listing should return entries for the project directory", entries.isNotEmpty())

        for (entry in entries) {
            assertTrue("Entry name must not be blank", entry.name.isNotBlank())
            assertNotNull("Entry path must not be null", entry.path)
        }

        val parentLink = entries.find { it.isParentLink }
        assertNotNull("File listing should include a parent link", parentLink)
    }

    fun testFileOperationServiceListsFilesWithCorrectAttributes() {
        val projectPath = Path.of(project.basePath!!)
        val fileOps = project.service<FileOperationService>()

        val entries = runBlocking { fileOps.listFiles(projectPath) }

        val realEntries = entries.filter { !it.isParentLink }

        for (entry in realEntries) {
            assertTrue(
                "Entry path ${entry.path} should be under project path $projectPath",
                entry.path.startsWith(projectPath)
            )
            assertEquals(
                "Entry name should match path file name",
                entry.path.fileName.toString(),
                entry.name
            )
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
