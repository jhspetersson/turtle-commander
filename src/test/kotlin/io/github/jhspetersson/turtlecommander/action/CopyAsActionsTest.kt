package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.model.FileEntry
import java.awt.GraphicsEnvironment
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
        SearchContextMenuState.selectedEntries = emptyList()
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
        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor) ?: ""

    // --- CopyAsCsv / CopyAsJson: enablement ---

    fun testCopyAsCsvEnabledWithSingleEntry() {
        FileContextMenuState.clickedEntry = fileEntry("data.csv")
        val action = CopyAsCsvAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
    }

    fun testCopyAsJsonEnabledWithSingleEntry() {
        FileContextMenuState.clickedEntry = fileEntry("data.json")
        val action = CopyAsJsonAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
    }

    fun testCopyAsCsvDisabledWhenNoEntry() {
        val action = CopyAsCsvAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
    }

    fun testCopyAsJsonDisabledWhenNoEntry() {
        val action = CopyAsJsonAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
    }

    fun testCopyAsCsvDisabledForParentLink() {
        FileContextMenuState.clickedEntry = parentEntry()
        val action = CopyAsCsvAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
    }

    // --- CopyAsCsv / CopyAsJson: target entry collection ---

    fun testCollectTargetEntriesFallsBackToClickedEntry() {
        val entry = fileEntry("hello.txt")
        FileContextMenuState.clickedEntry = entry
        val targets = collectTargetEntries(null)
        assertEquals(1, targets.size)
        assertSame(entry, targets[0])
    }

    fun testCollectTargetEntriesSkipsParentLink() {
        FileContextMenuState.clickedEntry = parentEntry()
        assertTrue(collectTargetEntries(null).isEmpty())
    }

    // --- CopyAsCsv / CopyAsJson: serialization with configured columns ---

    fun testEntriesToCsvUsesProvidedColumnsAsHeader() {
        val entries = listOf(fileEntry("a.txt", "/dir"))
        val csv = entriesToCsv(entries, listOf("Name", "Ext", "Size", "Permissions"))
        val lines = csv.trimEnd('\n').split("\n")
        assertEquals(2, lines.size)
        assertEquals("Name,Ext,Size,Permissions", lines[0])
        assertEquals("a,txt,100,rw-r--r--", lines[1])
    }

    fun testEntriesToCsvSplitsNameAndExtensionLikePanel() {
        val entries = listOf(fileEntry("archive.tar.gz", "/dir"))
        val csv = entriesToCsv(entries, listOf("Name", "Ext"))
        val rows = csv.trimEnd('\n').split("\n")
        assertEquals("archive.tar,gz", rows[1])
    }

    fun testEntriesToCsvKeepsDotfileNameWhole() {
        val entries = listOf(fileEntry(".env"))
        val csv = entriesToCsv(entries, listOf("Name", "Ext"))
        val rows = csv.trimEnd('\n').split("\n")
        assertEquals(".env,", rows[1])
    }

    fun testEntriesToCsvLeavesDirectorySizeBlank() {
        val entries = listOf(dirEntry("sub", "/dir"))
        val csv = entriesToCsv(entries, listOf("Name", "Size"))
        val rows = csv.trimEnd('\n').split("\n")
        assertEquals("sub,", rows[1])
    }

    fun testEntriesToCsvQuotesFieldsWithCommasQuotesAndNewlines() {
        val name = "weird,name\"with\nstuff.txt"
        val entries = listOf(FileEntry(
            name = name,
            path = Path.of("/test", "placeholder.txt"),
            isDirectory = false,
            size = 1,
            lastModified = null,
            permissions = "",
        ))
        val csv = entriesToCsv(entries, listOf("Name", "Ext"))
        val rest = csv.removePrefix("Name,Ext\n")
        assertTrue("starts with quoted weird name: $rest", rest.startsWith("\"weird,name\"\"with\nstuff\","))
    }

    fun testEntriesToJsonUsesSnakeCaseKeysFromColumns() {
        val entries = listOf(fileEntry("a.txt", "/dir"))
        val json = entriesToJson(entries, listOf("Name", "Size", "Date Modified", "Permissions"))
        assertTrue(json.startsWith("["))
        assertTrue(json.trimEnd().endsWith("]"))
        assertTrue(json, json.contains("\"name\": \"a\""))
        assertTrue(json, json.contains("\"size\": 100"))
        assertTrue(json, json.contains("\"date_modified\":"))
        assertTrue(json, json.contains("\"permissions\": \"rw-r--r--\""))
    }

    fun testEntriesToJsonUsesNullSizeForDirectory() {
        val entries = listOf(dirEntry("docs"))
        val json = entriesToJson(entries, listOf("Name", "Size"))
        assertTrue(json.contains("\"size\": null"))
    }

    fun testEntriesToJsonEscapesSpecialCharacters() {
        val name = "a\"b\\c\nd.txt"
        val entries = listOf(FileEntry(
            name = name,
            path = Path.of("/test", "placeholder.txt"),
            isDirectory = false,
            size = 1,
            lastModified = null,
            permissions = "",
        ))
        val json = entriesToJson(entries, listOf("Name"))
        assertTrue("json contains escaped name: $json", json.contains("\"name\": \"a\\\"b\\\\c\\nd\""))
    }

    // --- Filename / Path columns (always prepended in CSV/JSON exports) ---

    fun testEntriesToCsvEmitsFilenameAndPathColumns() {
        val entries = listOf(fileEntry("report.pdf", "/home/user"))
        val csv = entriesToCsv(entries, listOf("Filename", "Path", "Size"))
        val rows = csv.trimEnd('\n').split("\n")
        assertEquals("Filename,Path,Size", rows[0])
        val expectedPath = Path.of("/home/user", "report.pdf").toString()
        assertEquals("report.pdf,${expectedPath},100", rows[1])
    }

    fun testEntriesToCsvFilenameKeepsExtensionForDotfiles() {
        val entries = listOf(fileEntry(".env", "/dir"))
        val csv = entriesToCsv(entries, listOf("Filename"))
        val rows = csv.trimEnd('\n').split("\n")
        assertEquals(".env", rows[1])
    }

    fun testEntriesToJsonEmitsFilenameAndPathKeys() {
        val entries = listOf(fileEntry("data.csv", "/home/user"))
        val json = entriesToJson(entries, listOf("Filename", "Path"))
        assertTrue(json, json.contains("\"filename\": \"data.csv\""))
        val expectedPath = Path.of("/home/user", "data.csv").toString()
        // Path JSON value escapes backslashes (Windows separators) the same way as any string field.
        val escapedPath = expectedPath.replace("\\", "\\\\")
        assertTrue(json, json.contains("\"path\": \"${escapedPath}\""))
    }

    fun testExportColumnIdsPrependsFilenameAndPath() {
        val ids = exportColumnIds()
        assertEquals("Filename", ids[0])
        assertEquals("Path", ids[1])
    }

    // --- CopyAsCsv / CopyAsJson: clipboard end-to-end ---

    fun testCopyAsCsvCopiesToClipboard() {
        if (GraphicsEnvironment.isHeadless()) return
        FileContextMenuState.clickedEntry = fileEntry("hello.txt", "/home/user")
        val action = CopyAsCsvAction()
        action.actionPerformed(TestActionEvent.createTestEvent(action))
        val text = clipboardText()
        // Header line first; CSV/JSON exports always prepend Filename and Path.
        assertTrue(text, text.startsWith("Filename,Path,"))
        assertTrue(text, text.contains("hello.txt"))
        assertTrue(text, text.contains(Path.of("/home/user", "hello.txt").toString()))
        assertTrue(text, text.contains("100"))
    }

    fun testCopyAsJsonCopiesToClipboard() {
        if (GraphicsEnvironment.isHeadless()) return
        FileContextMenuState.clickedEntry = fileEntry("hello.txt", "/home/user")
        val action = CopyAsJsonAction()
        action.actionPerformed(TestActionEvent.createTestEvent(action))
        val text = clipboardText()
        assertTrue(text.startsWith("["))
        assertTrue(text, text.contains("\"filename\": \"hello.txt\""))
        assertTrue(text, text.contains("\"path\":"))
        assertTrue(text, text.contains("\"name\": \"hello\""))
        assertTrue(text, text.contains("\"size\": 100"))
    }

    // --- Search context: CopyAsCsv / CopyAsJson ---

    fun testSearchCopyAsCsvEnabledForClickedEntry() {
        SearchContextMenuState.clickedEntry = fileEntry("found.txt")
        val action = SearchCopyAsCsvAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
    }

    fun testSearchCopyAsJsonEnabledForSelectedEntries() {
        SearchContextMenuState.selectedEntries = listOf(fileEntry("a.txt"), fileEntry("b.txt"))
        val action = SearchCopyAsJsonAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
    }

    fun testSearchCopyAsCsvDisabledWhenEmpty() {
        val action = SearchCopyAsCsvAction()
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertFalse(event.presentation.isEnabled)
    }

    fun testCollectSearchTargetEntriesPrefersSelectionOverClicked() {
        SearchContextMenuState.clickedEntry = fileEntry("clicked.txt")
        val selected = listOf(fileEntry("a.txt"), fileEntry("b.txt"))
        SearchContextMenuState.selectedEntries = selected
        val targets = collectSearchTargetEntries()
        assertEquals(2, targets.size)
        assertEquals(selected, targets)
    }

    fun testCollectSearchTargetEntriesFallsBackToClicked() {
        val entry = fileEntry("clicked.txt")
        SearchContextMenuState.clickedEntry = entry
        val targets = collectSearchTargetEntries()
        assertEquals(1, targets.size)
        assertSame(entry, targets[0])
    }

    fun testSearchCopyAsCsvCopiesMultiSelectionToClipboard() {
        if (GraphicsEnvironment.isHeadless()) return
        SearchContextMenuState.selectedEntries = listOf(
            fileEntry("a.txt", "/results"),
            fileEntry("b.txt", "/results"),
        )
        val action = SearchCopyAsCsvAction()
        action.actionPerformed(TestActionEvent.createTestEvent(action))
        val text = clipboardText()
        val lines = text.trimEnd('\n').split("\n")
        assertEquals("header + 2 rows: $text", 3, lines.size)
        assertTrue(text, text.contains("a"))
        assertTrue(text, text.contains("b"))
    }
}
