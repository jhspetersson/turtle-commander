package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.settings.ColumnConfig
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.getDisplayPath
import io.github.jhspetersson.turtlecommander.ui.getSelectedEntries
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private fun resolveEntry(): FileEntry? = FileContextMenuState.clickedEntry

private fun resolveTab(e: AnActionEvent): FileTab? =
    FileContextMenuState.clickedTab ?: findActiveTab(e)

private fun nameLabel(entry: FileEntry?): String =
    if (entry?.isDirectory == true) "Directory Name" else "Filename"

/**
 * Full path for [entry]. When the entry lives inside an archive the on-disk
 * `entry.path` points at a temp-extracted file, so the user-facing path is the
 * tab's display path joined with the entry name instead.
 */
private fun entryFullPath(entry: FileEntry, tab: FileTab?): String =
    if (tab != null && tab.isInsideArchive) {
        val displayPath = tab.getDisplayPath()
        val separator = if (displayPath.contains("\\")) "\\" else "/"
        displayPath + separator + entry.name
    } else {
        entry.path.toString()
    }

private fun entryParentPath(entry: FileEntry, tab: FileTab?): String =
    if (tab != null && tab.isInsideArchive) {
        tab.getDisplayPath()
    } else {
        entry.path.parent?.toString() ?: ""
    }

// --- Shared bases for entry-targeted "Copy as" actions (file context + search results) ---

abstract class EntryCopyNameAction : EdtAction() {
    protected abstract fun entry(): FileEntry?

    override fun update(e: AnActionEvent) {
        val entry = entry()
        e.presentation.isEnabled = entry != null && !entry.isParentLink
        e.presentation.text = nameLabel(entry)
    }

    override fun actionPerformed(e: AnActionEvent) {
        CopyPasteManager.copyTextToClipboard(entry()?.name ?: return)
    }
}

abstract class EntryCopyFullPathAction : EdtAction() {
    protected abstract fun entry(): FileEntry?

    /** Owning tab, when archive-aware resolution is possible (null for search results). */
    protected open fun tab(e: AnActionEvent): FileTab? = null

    override fun update(e: AnActionEvent) {
        val entry = entry()
        e.presentation.isEnabled = entry != null && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = entry() ?: return
        CopyPasteManager.copyTextToClipboard(entryFullPath(entry, tab(e)))
    }
}

abstract class EntryCopyParentPathAction : EdtAction() {
    protected abstract fun entry(): FileEntry?

    protected open fun tab(e: AnActionEvent): FileTab? = null

    override fun update(e: AnActionEvent) {
        val entry = entry()
        e.presentation.isEnabled = entry != null && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = entry() ?: return
        CopyPasteManager.copyTextToClipboard(entryParentPath(entry, tab(e)))
    }
}

abstract class EntryCopyExportAction(
    private val format: (List<FileEntry>) -> String,
) : EdtAction() {
    protected abstract fun targets(e: AnActionEvent): List<FileEntry>

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = targets(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entries = targets(e)
        if (entries.isEmpty()) return
        CopyPasteManager.copyTextToClipboard(format(entries))
    }
}

// --- File/directory context menu actions ---

class CopyAsNameAction : EntryCopyNameAction() {
    override fun entry(): FileEntry? = resolveEntry()
}

class CopyAsFullPathAction : EntryCopyFullPathAction() {
    override fun entry(): FileEntry? = resolveEntry()
    override fun tab(e: AnActionEvent): FileTab? = resolveTab(e)
}

class CopyAsParentPathAction : EntryCopyParentPathAction() {
    override fun entry(): FileEntry? = resolveEntry()
    override fun tab(e: AnActionEvent): FileTab? = resolveTab(e)
}

class CopyAsCsvAction : EntryCopyExportAction({ entriesToCsv(it, exportColumnIds()) }) {
    override fun targets(e: AnActionEvent): List<FileEntry> = collectTargetEntries(resolveTab(e))
}

class CopyAsJsonAction : EntryCopyExportAction({ entriesToJson(it, exportColumnIds()) }) {
    override fun targets(e: AnActionEvent): List<FileEntry> = collectTargetEntries(resolveTab(e))
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
    "Inode" -> entry.inode
    "Links" -> entry.nlink?.toLong()
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

// --- Tab context menu actions (operate on the tab's directory, not a row) ---

class TabCopyAsNameAction : TabContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        val tab = panel?.getTabAt(tabIndex) ?: return
        val name = tab.currentPath.fileName?.toString() ?: tab.currentPath.toString()
        CopyPasteManager.copyTextToClipboard(name)
    }
}

class TabCopyAsFullPathAction : TabContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        val tab = panel?.getTabAt(tabIndex) ?: return
        if (tab.isInsideArchive) {
            CopyPasteManager.copyTextToClipboard(tab.getDisplayPath())
        } else {
            CopyPasteManager.copyTextToClipboard(tab.currentPath.toString())
        }
    }
}

class TabCopyAsParentPathAction : TabContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        val tab = panel?.getTabAt(tabIndex) ?: return
        if (tab.isInsideArchive) {
            CopyPasteManager.copyTextToClipboard(tab.realFilesystemPath.toString())
        } else {
            CopyPasteManager.copyTextToClipboard(tab.currentPath.parent?.toString() ?: "")
        }
    }
}

// --- Search results context menu actions ---

class SearchCopyAsNameAction : EntryCopyNameAction() {
    override fun entry(): FileEntry? = SearchContextMenuState.clickedEntry
}

class SearchCopyAsFullPathAction : EntryCopyFullPathAction() {
    override fun entry(): FileEntry? = SearchContextMenuState.clickedEntry
}

class SearchCopyAsParentPathAction : EntryCopyParentPathAction() {
    override fun entry(): FileEntry? = SearchContextMenuState.clickedEntry
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

class SearchCopyAsCsvAction : EntryCopyExportAction({ entriesToCsv(it, exportColumnIds()) }) {
    override fun targets(e: AnActionEvent): List<FileEntry> = collectSearchTargetEntries()
}

class SearchCopyAsJsonAction : EntryCopyExportAction({ entriesToJson(it, exportColumnIds()) }) {
    override fun targets(e: AnActionEvent): List<FileEntry> = collectSearchTargetEntries()
}

object SearchContextMenuState {
    var clickedEntry: FileEntry? = null
    var selectedEntries: List<FileEntry> = emptyList()
}
