package io.github.jhspetersson.turtlecommander.integration
import java.io.IOException
import java.nio.file.NoSuchFileException

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
            // Compare by file identity, not string prefix: the service canonicalizes the
            // listing directory via toRealPath() (reparse-point/junction resolution), which on
            // Windows also expands 8.3 short names (e.g. RUNNER~1). The canonical entry paths
            // then no longer string-startWith the original temp dir, so assert same-directory.
            assertTrue(
                "Entry path ${entry.path} should be under temp dir $tempDir",
                Files.isSameFile(entry.path.parent, tempDir)
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
        } catch (_: NoSuchFileException) {
            // expected
        } catch (_: IOException) {
            // some platforms throw a different IOException subtype
        }
    }

    /**
     * A symbolic link that points to a directory must be classified as a directory so the UI
     * lets the user switch into it. Reported on macOS for redirected home folders (Desktop,
     * Documents) that are symlinks to a cloud-storage location.
     */
    fun testSymlinkToDirectoryIsListedAsDirectory() {
        val target = Files.createDirectory(tempDir.resolve("link-target"))
        Files.writeString(target.resolve("inside.txt"), "x")
        val link = tempDir.resolve("dirlink")
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: Exception) {
            return // host forbids symlink creation (e.g. Windows without Developer Mode)
        }

        val fileOps = project.service<FileOperationService>()
        val entries = runBlocking { fileOps.listFiles(tempDir, forceRefresh = true) }

        val linkEntry = entries.find { it.name == "dirlink" }
        assertNotNull("the directory symlink should be listed", linkEntry)
        assertTrue("a symlink to a directory must be classified as a directory", linkEntry!!.isDirectory)
        assertTrue("it should be flagged as a symbolic link", linkEntry.isSymbolicLink)
        assertFalse("a resolvable link is not broken", linkEntry.isBrokenSymlink)
    }

    /** Switching into a directory symlink (what navigateTo does) lists the target's contents. */
    fun testListingThroughDirectorySymlinkReturnsTargetChildren() {
        val target = Files.createDirectory(tempDir.resolve("thru-target"))
        Files.writeString(target.resolve("inside.txt"), "x")
        val link = tempDir.resolve("thru-link")
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: Exception) {
            return
        }

        val fileOps = project.service<FileOperationService>()
        val entries = runBlocking { fileOps.listFiles(link, forceRefresh = true) }

        assertTrue(
            "listing the symlink path should surface the target's child file",
            entries.any { it.name == "inside.txt" }
        )
    }

    // --- listAllNestedFiles (the "show all nested files" view mode) ---

    fun testListAllNestedFilesReturnsFilesRecursively() {
        Files.writeString(tempDir.resolve("top.txt"), "x")
        val sub = Files.createDirectory(tempDir.resolve("sub"))
        Files.writeString(sub.resolve("mid.txt"), "x")
        val deep = Files.createDirectory(sub.resolve("deep"))
        Files.writeString(deep.resolve("low.txt"), "x")
        Files.createDirectory(tempDir.resolve("empty"))

        val fileOps = project.service<FileOperationService>()
        val entries = runBlocking { fileOps.listAllNestedFiles(tempDir) }

        val real = entries.filter { !it.isParentLink }
        val names = real.map { it.name }.toSet()
        assertTrue("includes the top-level file", "top.txt" in names)
        assertTrue("includes a nested file", "mid.txt" in names)
        assertTrue("includes a deeply nested file", "low.txt" in names)
        assertTrue("excludes directories", real.none { it.isDirectory })
        assertEquals("only the three files", 3, real.size)
    }

    fun testListAllNestedFilesIncludesParentEntry() {
        val sub = Files.createDirectory(tempDir.resolve("withparent"))
        Files.writeString(sub.resolve("a.txt"), "x")

        val fileOps = project.service<FileOperationService>()
        val entries = runBlocking { fileOps.listAllNestedFiles(sub) }

        assertTrue("recursive listing still exposes a parent link", entries.any { it.isParentLink })
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
