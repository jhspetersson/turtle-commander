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

fun FileTab.toggleSelectionAndMoveDown() {
    insideToggle = true
    try {
    when (viewMode) {
        ViewMode.TABLE -> {
            val row = table.selectionModel.leadSelectionIndex
            if (row < 0) return
            val modelRow = table.convertRowIndexToModel(row)
            val entry = tableModel.getEntryAt(modelRow)
            if (entry != null && !entry.isParentLink) {
                if (row in toggledRows) {
                    toggledRows.remove(row)
                } else {
                    toggledRows.add(row)
                    if (entry.isDirectory) calculateDirectorySize(entry)
                }
            }
            val nextRow = if (row + 1 < table.rowCount) row + 1 else row
            val nextModelRow = table.convertRowIndexToModel(nextRow)
            val nextEntry = tableModel.getEntryAt(nextModelRow)
            if (nextEntry != null && nextEntry.isDirectory && !nextEntry.isParentLink) {
                calculateDirectorySize(nextEntry)
            }
            applyToggledSelection(nextRow)
            table.selectionModel.leadSelectionIndex = nextRow
            table.scrollRectToVisible(table.getCellRect(nextRow, 0, true))
        }
        ViewMode.LIST -> toggleListSelectionAndMoveDown(list, listModel, toggledListIndices)
        ViewMode.THUMBNAIL -> toggleListSelectionAndMoveDown(thumbnailList, thumbnailListModel, toggledThumbnailIndices)
        ViewMode.TREE -> {
            val leadRow = tree.leadSelectionRow
            if (leadRow < 0) return
            val node = (tree.getPathForRow(leadRow)?.lastPathComponent) as? DefaultMutableTreeNode
            val entry = node?.userObject as? FileEntry
            if (entry != null && !entry.isParentLink) {
                if (leadRow in toggledTreeRows) {
                    toggledTreeRows.remove(leadRow)
                } else {
                    toggledTreeRows.add(leadRow)
                    if (entry.isDirectory) calculateDirectorySize(entry)
                }
            }
            val nextRow = if (leadRow + 1 < tree.rowCount) leadRow + 1 else leadRow
            val nextNode = (tree.getPathForRow(nextRow)?.lastPathComponent) as? DefaultMutableTreeNode
            val nextEntry = nextNode?.userObject as? FileEntry
            if (nextEntry != null && nextEntry.isDirectory && !nextEntry.isParentLink) {
                calculateDirectorySize(nextEntry)
            }
            applyToggledTreeSelection(nextRow)
            tree.scrollRowToVisible(nextRow)
        }
    }
    } finally {
        insideToggle = false
    }
}

private fun <T : JList<FileEntry>> FileTab.toggleListSelectionAndMoveDown(
    jList: T, model: DefaultListModel<FileEntry>, toggled: MutableSet<Int>,
) {
    val index = jList.selectionModel.leadSelectionIndex
    if (index < 0 || index >= model.size()) return
    val entry = model.getElementAt(index)
    if (entry != null && !entry.isParentLink) {
        if (index in toggled) {
            toggled.remove(index)
        } else {
            toggled.add(index)
            if (entry.isDirectory) calculateDirectorySize(entry)
        }
    }
    val nextIndex = if (index + 1 < model.size()) index + 1 else index
    val nextEntry = model.getElementAt(nextIndex)
    if (nextEntry != null && nextEntry.isDirectory && !nextEntry.isParentLink) {
        calculateDirectorySize(nextEntry)
    }
    val indicesToSelect = toggled.toMutableSet()
    indicesToSelect.add(nextIndex)
    jList.clearSelection()
    for (i in indicesToSelect) {
        if (i in 0 until model.size()) jList.addSelectionInterval(i, i)
    }
    jList.selectionModel.leadSelectionIndex = nextIndex
    jList.ensureIndexIsVisible(nextIndex)
}

internal fun FileTab.applyToggledSelection(cursorRow: Int) {
    val rowsToSelect = toggledRows.toMutableSet()
    rowsToSelect.add(cursorRow)
    table.clearSelection()
    for (row in rowsToSelect) {
        if (row in 0 until table.rowCount) {
            table.addRowSelectionInterval(row, row)
        }
    }
}

internal fun FileTab.applyToggledTreeSelection(cursorRow: Int) {
    val rowsToSelect = toggledTreeRows.toMutableSet()
    rowsToSelect.add(cursorRow)
    tree.clearSelection()
    for (row in rowsToSelect) {
        if (row in 0 until tree.rowCount) {
            tree.addSelectionRow(row)
        }
    }
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
