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
import java.nio.file.Path

class FileManagerToolWindowTest : BasePlatformTestCase() {

    fun testToolWindowFactoryCreatesContentViaDirectInvocation() {
        // In headless mode, tool windows from plugin.xml aren't auto-registered.
        // Instead, directly invoke the factory and verify it populates the tool window content.
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindowId = "Turtle Commander"

        // Register a tool window programmatically in the headless manager
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

        // List files via the same service the plugin uses internally
        val entries = runBlocking { fileOps.listFiles(projectPath) }

        // BasePlatformTestCase creates a light project with at least a src directory
        assertTrue("File listing should return entries for the project directory", entries.isNotEmpty())

        // Verify entries have valid structure
        for (entry in entries) {
            assertTrue("Entry name must not be blank", entry.name.isNotBlank())
            assertNotNull("Entry path must not be null", entry.path)
        }

        // There should be a parent link (..) since the project is not a root directory
        val parentLink = entries.find { it.isParentLink }
        assertNotNull("File listing should include a parent link", parentLink)
    }

    fun testFileOperationServiceListsFilesWithCorrectAttributes() {
        val projectPath = Path.of(project.basePath!!)
        val fileOps = project.service<FileOperationService>()

        val entries = runBlocking { fileOps.listFiles(projectPath) }

        // Filter out the parent link for attribute checking
        val realEntries = entries.filter { !it.isParentLink }

        for (entry in realEntries) {
            // Every real entry should have a path that starts with the project path
            assertTrue(
                "Entry path ${entry.path} should be under project path $projectPath",
                entry.path.startsWith(projectPath)
            )
            // Name should match the file name component of the path
            assertEquals(
                "Entry name should match path file name",
                entry.path.fileName.toString(),
                entry.name
            )
        }
    }

    fun testFileManagerPanelCreatesWithTab() {
        val projectPath = Path.of(project.basePath!!)
        val stateService = project.service<FileManagerStateService>()

        val panel = FileManagerPanel(
            project = project,
            initialPath = projectPath,
            otherPanelPathProvider = { projectPath },
        )
        // restoreState triggers tab creation (same as the factory does)
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
