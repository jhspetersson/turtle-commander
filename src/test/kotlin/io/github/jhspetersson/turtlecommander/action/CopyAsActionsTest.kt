package io.github.jhspetersson.turtlecommander.action

import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.model.FileEntry
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class CopyAsActionsTest : BasePlatformTestCase() {

    private fun fileEntry(name: String, dir: String = "/test") = FileEntry(
        name = name,
        path = Path.of(dir, name),
        isDirectory = false,
        size = 100,
        lastModified = FileTime.fromMillis(0),
        permissions = "rw-r--r--",
    )

    private fun dirEntry(name: String, dir: String = "/test") = FileEntry(
        name = name,
        path = Path.of(dir, name),
        isDirectory = true,
        size = 0,
        lastModified = FileTime.fromMillis(0),
        permissions = "rwxr-xr-x",
    )

    private fun parentEntry() = FileEntry(
        name = "..",
        path = Path.of("/test"),
        isDirectory = true,
        size = 0,
        lastModified = null,
        permissions = "",
        isParentLink = true,
    )

    override fun setUp() {
        super.setUp()
        FileContextMenuState.clickedEntry = null
        FileContextMenuState.clickedTab = null
        SearchContextMenuState.clickedEntry = null
    }

    // --- File context: CopyAsNameAction ---

    fun testCopyAsNameShowsFilenameForFile() {
        FileContextMenuState.clickedEntry = fileEntry("report.pdf")
        val action = CopyAsNameAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertEquals("Filename", event.presentation.text)
        assertTrue(event.presentation.isEnabled)
    }

    fun testCopyAsNameShowsDirectoryNameForDirectory() {
        FileContextMenuState.clickedEntry = dirEntry("Documents")
        val action = CopyAsNameAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertEquals("Directory Name", event.presentation.text)
        assertTrue(event.presentation.isEnabled)
    }

    fun testCopyAsNameDisabledForParentLink() {
        FileContextMenuState.clickedEntry = parentEntry()
        val action = CopyAsNameAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
    }

    fun testCopyAsNameDisabledWhenNoEntry() {
        val action = CopyAsNameAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
    }

    // --- File context: CopyAsFullPathAction ---

    fun testCopyAsFullPathEnabledForFile() {
        FileContextMenuState.clickedEntry = fileEntry("data.csv")
        val action = CopyAsFullPathAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
    }

    fun testCopyAsFullPathDisabledForParentLink() {
        FileContextMenuState.clickedEntry = parentEntry()
        val action = CopyAsFullPathAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
    }

    // --- File context: CopyAsParentPathAction ---

    fun testCopyAsParentPathEnabledForFile() {
        FileContextMenuState.clickedEntry = fileEntry("data.csv")
        val action = CopyAsParentPathAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
    }

    fun testCopyAsParentPathDisabledForParentLink() {
        FileContextMenuState.clickedEntry = parentEntry()
        val action = CopyAsParentPathAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
    }

    // --- File context: clipboard ---

    fun testCopyAsNameCopiesToClipboard() {
        if (GraphicsEnvironment.isHeadless()) return
        FileContextMenuState.clickedEntry = fileEntry("hello.txt")
        val action = CopyAsNameAction()
        action.actionPerformed(TestActionEvent.createTestEvent(action))
        assertEquals("hello.txt", clipboardText())
    }

    fun testCopyAsFullPathCopiesToClipboard() {
        if (GraphicsEnvironment.isHeadless()) return
        FileContextMenuState.clickedEntry = fileEntry("hello.txt", "/home/user")
        val action = CopyAsFullPathAction()
        action.actionPerformed(TestActionEvent.createTestEvent(action))
        assertEquals(Path.of("/home/user", "hello.txt").toString(), clipboardText())
    }

    fun testCopyAsParentPathCopiesToClipboard() {
        if (GraphicsEnvironment.isHeadless()) return
        FileContextMenuState.clickedEntry = fileEntry("hello.txt", "/home/user")
        val action = CopyAsParentPathAction()
        action.actionPerformed(TestActionEvent.createTestEvent(action))
        assertEquals(Path.of("/home/user").toString(), clipboardText())
    }

    // --- Search context: state ---

    fun testSearchContextMenuStateInitiallyNull() {
        assertNull(SearchContextMenuState.clickedEntry)
    }

    fun testSearchContextMenuStateStoresEntry() {
        val entry = fileEntry("result.txt")
        SearchContextMenuState.clickedEntry = entry
        assertSame(entry, SearchContextMenuState.clickedEntry)
    }

    // --- Search context: update ---

    fun testSearchCopyAsNameShowsFilenameForFile() {
        SearchContextMenuState.clickedEntry = fileEntry("found.txt")
        val action = SearchCopyAsNameAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertEquals("Filename", event.presentation.text)
        assertTrue(event.presentation.isEnabled)
    }

    fun testSearchCopyAsNameShowsDirectoryNameForDirectory() {
        SearchContextMenuState.clickedEntry = dirEntry("results")
        val action = SearchCopyAsNameAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertEquals("Directory Name", event.presentation.text)
        assertTrue(event.presentation.isEnabled)
    }

    fun testSearchCopyAsNameDisabledWhenNoEntry() {
        val action = SearchCopyAsNameAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
    }

    fun testSearchCopyAsFullPathEnabledForEntry() {
        SearchContextMenuState.clickedEntry = fileEntry("found.txt")
        val action = SearchCopyAsFullPathAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
    }

    fun testSearchCopyAsFullPathDisabledWhenNoEntry() {
        val action = SearchCopyAsFullPathAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
    }

    fun testSearchCopyAsParentPathEnabledForEntry() {
        SearchContextMenuState.clickedEntry = fileEntry("found.txt")
        val action = SearchCopyAsParentPathAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
    }

    fun testSearchCopyAsParentPathDisabledWhenNoEntry() {
        val action = SearchCopyAsParentPathAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
    }

    // --- Search context: clipboard ---

    fun testSearchCopyAsNameCopiesToClipboard() {
        if (GraphicsEnvironment.isHeadless()) return
        SearchContextMenuState.clickedEntry = fileEntry("found.txt")
        val action = SearchCopyAsNameAction()
        action.actionPerformed(TestActionEvent.createTestEvent(action))
        assertEquals("found.txt", clipboardText())
    }

    fun testSearchCopyAsFullPathCopiesToClipboard() {
        if (GraphicsEnvironment.isHeadless()) return
        SearchContextMenuState.clickedEntry = fileEntry("found.txt", "/search/results")
        val action = SearchCopyAsFullPathAction()
        action.actionPerformed(TestActionEvent.createTestEvent(action))
        assertEquals(Path.of("/search/results", "found.txt").toString(), clipboardText())
    }

    fun testSearchCopyAsParentPathCopiesToClipboard() {
        if (GraphicsEnvironment.isHeadless()) return
        SearchContextMenuState.clickedEntry = fileEntry("found.txt", "/search/results")
        val action = SearchCopyAsParentPathAction()
        action.actionPerformed(TestActionEvent.createTestEvent(action))
        assertEquals(Path.of("/search/results").toString(), clipboardText())
    }

    private fun clipboardText(): String =
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
}
