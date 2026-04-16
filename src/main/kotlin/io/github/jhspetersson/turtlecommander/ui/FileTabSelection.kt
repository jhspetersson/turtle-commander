package io.github.jhspetersson.turtlecommander.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import io.github.jhspetersson.turtlecommander.action.FileContextMenuState
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.swing.DefaultListModel
import javax.swing.DefaultListSelectionModel
import javax.swing.JList
import javax.swing.tree.DefaultMutableTreeNode

internal fun FileTab.getSelectedEntry(): FileEntry? {
    if (viewMode == ViewMode.LIST) {
        return list.selectedValue
    }
    if (viewMode == ViewMode.THUMBNAIL) {
        return thumbnailList.selectedValue
    }
    if (viewMode == ViewMode.TREE) {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? FileEntry
    }
    val viewRow = table.selectedRow
    if (viewRow < 0) return null
    val modelRow = table.convertRowIndexToModel(viewRow)
    return tableModel.getEntryAt(modelRow)
}

fun FileTab.getSelectedEntries(): List<FileEntry> {
    if (viewMode == ViewMode.LIST) {
        return list.selectedValuesList.filter { !it.isParentLink }
    }
    if (viewMode == ViewMode.THUMBNAIL) {
        return thumbnailList.selectedValuesList.filter { !it.isParentLink }
    }
    if (viewMode == ViewMode.TREE) {
        return (tree.selectionPaths ?: emptyArray()).mapNotNull { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode
            node?.userObject as? FileEntry
        }.filter { !it.isParentLink }
    }
    return table.selectedRows.toList()
        .map { table.convertRowIndexToModel(it) }
        .mapNotNull { tableModel.getEntryAt(it) }
        .filter { !it.isParentLink }
}

fun FileTab.showContextMenu() {
    val entry = getSelectedEntry()
    FileContextMenuState.clickedEntry = entry
    FileContextMenuState.clickedTab = this

    val am = ActionManager.getInstance()
    val group = am.getAction("TurtleCommander.FileContextMenu") as? ActionGroup ?: return
    val popupMenu = am.createActionPopupMenu("TurtleCommander.FileContextMenu", group)

    when (viewMode) {
        ViewMode.TABLE -> {
            val row = table.selectedRow
            if (row >= 0) {
                val rect = table.getCellRect(row, 0, true)
                popupMenu.component.show(table, rect.x, rect.y + rect.height)
            } else {
                popupMenu.component.show(table, 0, 0)
            }
        }
        ViewMode.LIST -> {
            val index = list.selectedIndex
            val rect = if (index >= 0) list.getCellBounds(index, index) else null
            if (rect != null) {
                popupMenu.component.show(list, rect.x, rect.y + rect.height)
            } else {
                popupMenu.component.show(list, 0, 0)
            }
        }
        ViewMode.THUMBNAIL -> {
            val index = thumbnailList.selectedIndex
            val rect = if (index >= 0) thumbnailList.getCellBounds(index, index) else null
            if (rect != null) {
                popupMenu.component.show(thumbnailList, rect.x, rect.y + rect.height)
            } else {
                popupMenu.component.show(thumbnailList, 0, 0)
            }
        }
        ViewMode.TREE -> {
            val row = tree.leadSelectionRow
            val rect = if (row >= 0) tree.getRowBounds(row) else null
            if (rect != null) {
                popupMenu.component.show(tree, rect.x, rect.y + rect.height)
            } else {
                popupMenu.component.show(tree, 0, 0)
            }
        }
    }
}

fun FileTab.toggleSelection() {
    toggleSelectionInternal(moveCursorDown = false)
}

fun FileTab.toggleSelectionAndMoveDown() {
    toggleSelectionInternal(moveCursorDown = true)
}

private fun FileTab.toggleSelectionInternal(moveCursorDown: Boolean) {
    insideToggle = true
    try {
    when (viewMode) {
        ViewMode.TABLE -> {
            val row = table.selectionModel.leadSelectionIndex
            if (row < 0) return
            val modelRow = table.convertRowIndexToModel(row)
            val entry = tableModel.getEntryAt(modelRow)
            toggleMarkForEntry(entry)
            val cursorRow = if (moveCursorDown && row + 1 < table.rowCount) row + 1 else row
            applyToggledSelection()
            val tsm = table.selectionModel as? DefaultListSelectionModel
            tsm?.moveLeadSelectionIndex(cursorRow) ?: run { table.selectionModel.leadSelectionIndex = cursorRow }
            if (moveCursorDown) table.scrollRectToVisible(table.getCellRect(cursorRow, 0, true))
        }
        ViewMode.LIST -> toggleListSelection(list, listModel, moveCursorDown)
        ViewMode.THUMBNAIL -> toggleListSelection(thumbnailList, thumbnailListModel, moveCursorDown)
        ViewMode.TREE -> {
            val leadRow = tree.leadSelectionRow
            if (leadRow < 0) return
            val node = (tree.getPathForRow(leadRow)?.lastPathComponent) as? DefaultMutableTreeNode
            val entry = node?.userObject as? FileEntry
            toggleMarkForEntry(entry)
            val cursorRow = if (moveCursorDown && leadRow + 1 < tree.rowCount) leadRow + 1 else leadRow
            applyToggledTreeSelection()
            if (moveCursorDown) tree.scrollRowToVisible(cursorRow)
        }
    }
    } finally {
        insideToggle = false
    }
}

private fun FileTab.toggleMarkForEntry(entry: FileEntry?) {
    if (entry == null || entry.isParentLink) return
    if (entry.path in markedPaths) {
        markedPaths.remove(entry.path)
    } else {
        markedPaths.add(entry.path)
        if (entry.isDirectory) calculateDirectorySize(entry)
    }
}

private fun <T : JList<FileEntry>> FileTab.toggleListSelection(
    jList: T, model: DefaultListModel<FileEntry>, moveCursorDown: Boolean,
) {
    val index = jList.selectionModel.leadSelectionIndex
    if (index < 0 || index >= model.size()) return
    val entry = model.getElementAt(index)
    toggleMarkForEntry(entry)
    val cursorIndex = if (moveCursorDown && index + 1 < model.size()) index + 1 else index
    applyMarksToList(jList, model)
    val lsm = jList.selectionModel as? DefaultListSelectionModel
    lsm?.moveLeadSelectionIndex(cursorIndex) ?: run { jList.selectionModel.leadSelectionIndex = cursorIndex }
    if (moveCursorDown) jList.ensureIndexIsVisible(cursorIndex)
}

internal fun FileTab.applyToggledSelection() {
    table.clearSelection()
    for (viewRow in 0 until table.rowCount) {
        val modelRow = table.convertRowIndexToModel(viewRow)
        val entry = tableModel.getEntryAt(modelRow) ?: continue
        if (entry.path in markedPaths) {
            table.addRowSelectionInterval(viewRow, viewRow)
        }
    }
}

internal fun FileTab.applyToggledTreeSelection() {
    tree.clearSelection()
    for (row in 0 until tree.rowCount) {
        val node = tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode ?: continue
        val entry = node.userObject as? FileEntry ?: continue
        if (entry.path in markedPaths) {
            tree.addSelectionRow(row)
        }
    }
}

internal fun <T : JList<FileEntry>> FileTab.applyMarksToList(jList: T, model: DefaultListModel<FileEntry>) {
    jList.clearSelection()
    for (i in 0 until model.size()) {
        val entry = model.getElementAt(i) ?: continue
        if (entry.path in markedPaths) jList.addSelectionInterval(i, i)
    }
}

// Re-apply the persistent mark set to the table's selection model after Swing's default key /
// mouse handler clobbered it (arrow keys, HOME/END, plain click), preserving the new lead so
// the cursor still moves visibly. Only the marked rows are highlighted; the cursor row gets the
// focus rectangle from the lead index when it isn't itself marked.
//
// Restoring the lead requires `moveLeadSelectionIndex` (DefaultListSelectionModel-specific):
// `setLeadSelectionIndex` would also extend or shrink the selection between anchor and the new
// lead — which is exactly the "selection follows the cursor" bug we're trying to avoid.
internal fun FileTab.restoreTableMarks() {
    val savedAnchor = table.selectionModel.anchorSelectionIndex
    val savedLead = table.selectionModel.leadSelectionIndex
    insideRestore = true
    try {
        applyToggledSelection()
        val sm = table.selectionModel as? DefaultListSelectionModel
        if (savedAnchor in 0 until table.rowCount) sm?.anchorSelectionIndex = savedAnchor
        if (savedLead in 0 until table.rowCount) sm?.moveLeadSelectionIndex(savedLead)
    } finally {
        insideRestore = false
    }
}

internal fun <T : JList<FileEntry>> FileTab.restoreListMarks(jList: T, model: DefaultListModel<FileEntry>) {
    val savedAnchor = jList.selectionModel.anchorSelectionIndex
    val savedLead = jList.selectionModel.leadSelectionIndex
    insideRestore = true
    try {
        applyMarksToList(jList, model)
        val sm = jList.selectionModel as? DefaultListSelectionModel
        if (savedAnchor in 0 until jList.model.size) sm?.anchorSelectionIndex = savedAnchor
        if (savedLead in 0 until jList.model.size) sm?.moveLeadSelectionIndex(savedLead)
    } finally {
        insideRestore = false
    }
}

// JTree exposes no public setter for the lead path, so we can't visually separate the cursor
// from the marked rows the way the table can. Instead the cursor path is appended to the
// selection — matching the look JTree had before this rework, while still preserving the
// persistent mark set across arrow / click navigation.
internal fun FileTab.restoreTreeMarks() {
    val cursorPath = tree.leadSelectionPath ?: tree.selectionPath
    insideRestore = true
    try {
        applyToggledTreeSelection()
        if (cursorPath != null) tree.addSelectionPath(cursorPath)
    } finally {
        insideRestore = false
    }
}

internal fun FileTab.clearAllToggledMarks() {
    markedPaths.clear()
}

internal fun FileTab.calculateDirectorySize(entry: FileEntry) {
    if (!entry.isDirectory || entry.isParentLink) return
    if (!TurtleCommanderSettings.getInstance().state.calculateDirectorySize) return
    if (directorySizes.containsKey(entry.path)) return
    fileOps.launch {
        val size = withContext(Dispatchers.IO) {
            var total = 0L
            try {
                Files.walkFileTree(entry.path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (_: Exception) {}
            total
        }
        directorySizes[entry.path] = size
        withContext(Dispatchers.EDT) {
            val modelRow = (0 until tableModel.rowCount).firstOrNull {
                tableModel.getEntryAt(it)?.path == entry.path
            }
            if (modelRow != null) {
                tableModel.fireTableCellUpdated(modelRow, FileTableModel.COL_SIZE)
            }
            updateStatusBar()
        }
    }
}

internal fun findPreservedSelectionIndex(entries: List<FileEntry>, selectedName: String?): Int {
    if (entries.isEmpty()) return -1
    if (selectedName == null) return 0
    val idx = entries.indexOfFirst { it.name == selectedName }
    return if (idx >= 0) idx else 0
}
