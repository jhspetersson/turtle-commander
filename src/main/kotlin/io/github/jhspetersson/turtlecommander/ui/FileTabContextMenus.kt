package io.github.jhspetersson.turtlecommander.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import io.github.jhspetersson.turtlecommander.action.FileContextMenuState
import io.github.jhspetersson.turtlecommander.model.FileEntry
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListModel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Clear the shared context-menu state once [this] popup closes, so it stops pinning the target
 * FileTab / panel (and transitively the project) after the menu is dismissed. Deferred to a later
 * EDT event so the selected menu item's own action — which reads the state — runs first.
 */
internal fun JPopupMenu.clearStateOnClose(clear: () -> Unit) {
    addPopupMenuListener(object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {}
        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
            SwingUtilities.invokeLater { clear() }
        }
        override fun popupMenuCanceled(e: PopupMenuEvent) {
            SwingUtilities.invokeLater { clear() }
        }
    })
}

/**
 * Per-view right-click wiring for [FileTab]. Each view (table / list / thumbnail
 * grid / tree) resolves the entry under the cursor — selecting it first if it
 * isn't already part of the selection — then hands off to the shared
 * [showContextMenu], which stashes the target in [FileContextMenuState] and
 * shows the registered file context menu. Kept out of FileTab.kt itself so the
 * core tab class isn't padded with four near-identical mouse handlers; they only
 * touch the views and models, which are already module-visible.
 */
internal fun FileTab.handleTableContextMenu(e: MouseEvent) {
    showContextMenu(table, e) {
        val row = table.rowAtPoint(e.point)
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row)
        }
        if (row >= 0) tableModel.getEntryAt(table.convertRowIndexToModel(row)) else null
    }
}

internal fun FileTab.handleListContextMenu(e: MouseEvent) {
    showContextMenu(list, e) { listContextEntry(list, listModel, e.point) }
}

internal fun FileTab.handleThumbnailContextMenu(e: MouseEvent) {
    showContextMenu(thumbnailList, e) { listContextEntry(thumbnailList, thumbnailListModel, e.point) }
}

internal fun FileTab.handleTreeContextMenu(e: MouseEvent) {
    showContextMenu(tree, e) { treeContextEntry(e.point) }
}

internal fun listContextEntry(list: JList<FileEntry>, model: ListModel<FileEntry>, point: Point): FileEntry? {
    val index = list.locationToIndex(point).takeIf { it >= 0 && list.getCellBounds(it, it)?.contains(point) == true } ?: -1
    if (index >= 0 && !list.isSelectedIndex(index)) {
        list.selectedIndex = index
    }
    return if (index >= 0) model.getElementAt(index) else null
}

internal fun FileTab.treeContextEntry(point: Point): FileEntry? {
    val treePath = tree.getPathForLocation(point.x, point.y)
    if (treePath != null && !tree.isPathSelected(treePath)) {
        tree.selectionPath = treePath
    }
    return (treePath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? FileEntry
}

internal inline fun FileTab.showContextMenu(
    component: JComponent,
    e: MouseEvent,
    resolveEntry: () -> FileEntry?,
) {
    if (!e.isPopupTrigger) return
    FileContextMenuState.clickedEntry = resolveEntry()
    FileContextMenuState.clickedTab = this

    val am = ActionManager.getInstance()
    val group = am.getAction("TurtleCommander.FileContextMenu") as? ActionGroup ?: return
    val popupMenu = am.createActionPopupMenu("TurtleCommander.FileContextMenu", group)
    popupMenu.component.clearStateOnClose { FileContextMenuState.clear() }
    popupMenu.component.show(component, e.x, e.y)
}
