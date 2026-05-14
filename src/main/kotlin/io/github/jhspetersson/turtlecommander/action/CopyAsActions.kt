package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.settings.ColumnConfig
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.getDisplayPath
import io.github.jhspetersson.turtlecommander.ui.getSelectedEntries
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private fun copyToClipboard(text: String) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val contents = StringSelection(text)
    // Windows OpenClipboard rejects with IllegalStateException ("cannot open
    // system clipboard") whenever another process — clipboard manager,
    // password manager, browser — holds the clipboard at that instant. The
    // contention window is typically <100ms, so a short backoff loop turns
    // a hard failure into a silent retry. Same situation also makes the
    // CopyAs unit tests flaky on dev machines.
    var lastError: IllegalStateException? = null
    var sleep = 20L
    repeat(5) {
        try {
            clipboard.setContents(contents, null)
            return
        } catch (e: IllegalStateException) {
            lastError = e
            try { Thread.sleep(sleep) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            sleep *= 2
        }
    }
    // Final attempt — let the exception surface if it still fails so callers
    // can log or notify rather than silently dropping the user's copy.
    if (lastError != null) {
        clipboard.setContents(contents, null)
    }
}

private fun resolveEntry(): FileEntry? = FileContextMenuState.clickedEntry

private fun resolveTab(e: AnActionEvent): FileTab? =
    FileContextMenuState.clickedTab ?: findActiveTab(e)

// --- File/directory context menu actions ---

class CopyAsNameAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = resolveEntry()
        e.presentation.isEnabled = entry != null && !entry.isParentLink
        e.presentation.text = if (entry?.isDirectory == true) "Directory Name" else "Filename"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = resolveEntry() ?: return
        copyToClipboard(entry.name)
    }
}

class CopyAsFullPathAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = resolveEntry()
        e.presentation.isEnabled = entry != null && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = resolveEntry() ?: return
        val tab = resolveTab(e)
        if (tab != null && tab.isInsideArchive) {
            // For VFS entries, build the full display path + entry name
            val displayPath = tab.getDisplayPath()
            val separator = if (displayPath.contains("\\")) "\\" else "/"
            copyToClipboard(displayPath + separator + entry.name)
        } else {
            copyToClipboard(entry.path.toString())
        }
    }
}

class CopyAsParentPathAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = resolveEntry()
        e.presentation.isEnabled = entry != null && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = resolveEntry() ?: return
        val tab = resolveTab(e)
        if (tab != null && tab.isInsideArchive) {
            copyToClipboard(tab.getDisplayPath())
        } else {
            copyToClipboard(entry.path.parent?.toString() ?: "")
        }
    }
}

/**
 * Returns the set of entries the user wants to operate on: the tab's current
 * selection if any, otherwise the right-clicked entry. This makes the action
 * work both when the user right-clicked an unselected row (acts on that row)
 * and when they right-clicked inside a multi-selection (acts on all selected).
 */
internal fun collectTargetEntries(tab: FileTab?): List<FileEntry> {
    val selected = tab?.getSelectedEntries().orEmpty().filter { !it.isParentLink }
    if (selected.isNotEmpty()) return selected
    val clicked = FileContextMenuState.clickedEntry
    return if (clicked != null && !clicked.isParentLink) listOf(clicked) else emptyList()
}

/**
 * Returns the column ids the user currently has visible, in their configured order.
 * Falls back to all default columns when the settings service is unavailable
 * (e.g. headless unit tests).
 */
internal fun visibleColumnIds(): List<String> {
    val columns = runCatching { TurtleCommanderSettings.getInstance().getEffectiveColumns() }
        .getOrDefault(ColumnConfig.defaults())
    return columns.filter { it.visible }.map { it.id }
}

/**
 * Columns used for CSV/JSON exports: always prepend "Filename" (full name with
 * extension) and "Path" (full filesystem path) so the exported rows are
 * self-describing even when the user has narrowed the visible Name/Ext columns.
 */
internal fun exportColumnIds(): List<String> = listOf("Filename", "Path") + visibleColumnIds()

/**
 * Computes the raw value for [entry] under the column named [columnId]. Returns
 * `Long` for Size, `String` for everything else, `null` when missing (e.g. size
 * of a directory or a date that isn't recorded). Mirrors the panel's split of
 * Name vs. Ext so the export matches what the user sees.
 */
internal fun columnValue(entry: FileEntry, columnId: String): Any? = when (columnId) {
    "Filename" -> entry.name
    "Path" -> entry.path.toString()
    "Name" -> when {
        entry.isDirectory -> entry.name
        !entry.name.contains('.') || entry.name.startsWith('.') -> entry.name
        else -> entry.name.substringBeforeLast('.')
    }
    "Ext" -> when {
        entry.isDirectory -> ""
        !entry.name.contains('.') || entry.name.startsWith('.') -> ""
        else -> entry.name.substringAfterLast('.')
    }
    "Size" -> if (entry.isDirectory) null else entry.size
    "Date Created" -> formatFileTime(entry.creationTime)
    "Date Modified" -> formatFileTime(entry.lastModified)
    "User" -> entry.owner
    "Group" -> entry.group
    "Permissions" -> entry.permissions
    else -> ""
}

internal fun entriesToCsv(entries: List<FileEntry>, columns: List<String>): String {
    val sb = StringBuilder()
    columns.forEachIndexed { i, id ->
        if (i > 0) sb.append(',')
        sb.append(csvField(id))
    }
    sb.append('\n')
    for (entry in entries) {
        columns.forEachIndexed { i, id ->
            if (i > 0) sb.append(',')
            when (val value = columnValue(entry, id)) {
                null -> {} // empty cell
                is Long -> sb.append(value.toString())
                else -> sb.append(csvField(value.toString()))
            }
        }
        sb.append('\n')
    }
    return sb.toString()
}

internal fun entriesToJson(entries: List<FileEntry>, columns: List<String>): String {
    val keys = columns.map { jsonKeyFor(it) }
    val sb = StringBuilder()
    sb.append("[\n")
    entries.forEachIndexed { index, entry ->
        sb.append("  {\n")
        columns.forEachIndexed { i, id ->
            sb.append("    ").append(jsonString(keys[i])).append(": ")
            when (val value = columnValue(entry, id)) {
                null -> sb.append("null")
                is Long -> sb.append(value.toString())
                else -> sb.append(jsonString(value.toString()))
            }
            if (i < columns.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  }")
        if (index < entries.lastIndex) sb.append(',')
        sb.append('\n')
    }
    sb.append("]\n")
    return sb.toString()
}

private fun jsonKeyFor(columnId: String): String = columnId.lowercase().replace(' ', '_')

private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

private fun formatFileTime(ft: FileTime?): String {
    if (ft == null) return ""
    return runCatching { isoFormatter.format(Instant.ofEpochMilli(ft.toMillis()).atOffset(ZoneOffset.UTC)) }.getOrDefault("")
}

private fun csvField(value: String): String {
    if (value.indexOfAny(charArrayOf(',', '"', '\n', '\r')) < 0) return value
    val escaped = value.replace("\"", "\"\"")
    return "\"$escaped\""
}

private fun jsonString(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}

class CopyAsCsvAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = collectTargetEntries(resolveTab(e)).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entries = collectTargetEntries(resolveTab(e))
        if (entries.isEmpty()) return
        copyToClipboard(entriesToCsv(entries, exportColumnIds()))
    }
}

class CopyAsJsonAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = collectTargetEntries(resolveTab(e)).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entries = collectTargetEntries(resolveTab(e))
        if (entries.isEmpty()) return
        copyToClipboard(entriesToJson(entries, exportColumnIds()))
    }
}

// --- Tab context menu actions ---

class TabCopyAsNameAction : TabContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        val tab = panel?.getTabAt(tabIndex) ?: return
        val name = tab.currentPath.fileName?.toString() ?: tab.currentPath.toString()
        copyToClipboard(name)
    }
}

class TabCopyAsFullPathAction : TabContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        val tab = panel?.getTabAt(tabIndex) ?: return
        if (tab.isInsideArchive) {
            copyToClipboard(tab.getDisplayPath())
        } else {
            copyToClipboard(tab.currentPath.toString())
        }
    }
}

class TabCopyAsParentPathAction : TabContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        val tab = panel?.getTabAt(tabIndex) ?: return
        if (tab.isInsideArchive) {
            copyToClipboard(tab.realFilesystemPath.toString())
        } else {
            copyToClipboard(tab.currentPath.parent?.toString() ?: "")
        }
    }
}

// --- Search results context menu actions ---

class SearchCopyAsNameAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = SearchContextMenuState.clickedEntry
        e.presentation.isEnabled = entry != null
        e.presentation.text = if (entry?.isDirectory == true) "Directory Name" else "Filename"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = SearchContextMenuState.clickedEntry ?: return
        copyToClipboard(entry.name)
    }
}

class SearchCopyAsFullPathAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = SearchContextMenuState.clickedEntry != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = SearchContextMenuState.clickedEntry ?: return
        copyToClipboard(entry.path.toString())
    }
}

class SearchCopyAsParentPathAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = SearchContextMenuState.clickedEntry != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = SearchContextMenuState.clickedEntry ?: return
        copyToClipboard(entry.path.parent?.toString() ?: "")
    }
}

/**
 * Returns the search panel target entries: the user's table selection if any,
 * otherwise the right-clicked entry. Same fallback shape as [collectTargetEntries].
 */
internal fun collectSearchTargetEntries(): List<FileEntry> {
    val selected = SearchContextMenuState.selectedEntries.filter { !it.isParentLink }
    if (selected.isNotEmpty()) return selected
    val clicked = SearchContextMenuState.clickedEntry
    return if (clicked != null && !clicked.isParentLink) listOf(clicked) else emptyList()
}

class SearchCopyAsCsvAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = collectSearchTargetEntries().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entries = collectSearchTargetEntries()
        if (entries.isEmpty()) return
        copyToClipboard(entriesToCsv(entries, exportColumnIds()))
    }
}

class SearchCopyAsJsonAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = collectSearchTargetEntries().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entries = collectSearchTargetEntries()
        if (entries.isEmpty()) return
        copyToClipboard(entriesToJson(entries, exportColumnIds()))
    }
}

object SearchContextMenuState {
    var clickedEntry: FileEntry? = null
    var selectedEntries: List<FileEntry> = emptyList()
}
