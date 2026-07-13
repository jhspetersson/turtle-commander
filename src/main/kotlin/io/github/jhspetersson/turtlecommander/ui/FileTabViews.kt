package io.github.jhspetersson.turtlecommander.ui
import com.intellij.ui.SpeedSearchBase
import javax.swing.SwingUtilities

import com.intellij.openapi.application.EDT
import io.github.jhspetersson.turtlecommander.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

fun FileTab.setViewMode(mode: ViewMode) {
    val previousMode = viewMode
    if (previousMode == mode) return

    // If a speed search is active on the old view, capture its prefix so we can
    // re-activate it on the new view after the switch.
    val carriedPrefix = when (previousMode) {
        ViewMode.TABLE -> tableSpeedSearch?.enteredPrefix
        ViewMode.LIST -> listSpeedSearch?.enteredPrefix
        ViewMode.THUMBNAIL -> thumbnailSpeedSearch?.enteredPrefix
        ViewMode.TREE -> treeSpeedSearch?.enteredPrefix
    }?.takeIf { it.isNotEmpty() }

    // Capture selection from the current (old) view before switching
    val selectedEntries = getSelectedEntries()
    val selectedNames = selectedEntries.map { it.name }.toSet()

    // For tree -> flat: navigate to the parent directory of the selected entries and select them.
    // Skip tree-based navigation when inside a VFS — the tree may show stale entries from
    // the real filesystem, and navigating to those paths would corrupt the VFS state.
    val treeNavigation = if (previousMode == ViewMode.TREE && selectedEntries.isNotEmpty() && !isInsideArchive) {
        val lastEntry = selectedEntries.last()
        val targetDir = lastEntry.path.parent ?: currentPath
        val namesInDir = selectedEntries
            .filter { (it.path.parent ?: currentPath) == targetDir }
            .map { it.name }
            .toSet()
        Pair(targetDir, namesInDir)
    } else null

    viewMode = mode
    val card = when (mode) {
        ViewMode.TABLE -> FileTab.VIEW_TABLE
        ViewMode.LIST -> FileTab.VIEW_LIST
        ViewMode.THUMBNAIL -> FileTab.VIEW_THUMBNAIL
        ViewMode.TREE -> FileTab.VIEW_TREE
    }
    viewCardLayout.show(viewPanel, card)

    if (mode == ViewMode.TABLE && !hasTabColumnState(currentPath.toString())) {
        applyDefaultColumnWidths(this)
    }

    if (mode == ViewMode.TREE) {
        // The hierarchical tree can't be name-filtered: drop an active quick filter on the
        // way in rather than leave the other views' models filtered behind an unfiltered tree.
        if (!showAllNestedFiles) hideQuickFilterIfActive()
        // In show-all-nested-files mode the tree shows the flat recursive file list (leaves),
        // consistent with the other views; otherwise it builds the real directory hierarchy.
        if (showAllNestedFiles) populateFlatTree() else rebuildFullTree(selectedNames)
        tree.requestFocusInWindow()
    } else {
        if (treeNavigation != null) {
            val (targetDir, namesInDir) = treeNavigation
            if (targetDir != currentPath) {
                fileOps.launch {
                    navigateTo(targetDir, selectName = namesInDir.firstOrNull())
                    if (namesInDir.size > 1) {
                        withContext(Dispatchers.EDT) {
                            selectEntriesByNameThenApplyMarks(namesInDir)
                        }
                    } else if (markedPaths.isNotEmpty()) {
                        withContext(Dispatchers.EDT) { applyMarksForCurrentView() }
                    }
                }
            } else {
                selectEntriesByNameThenApplyMarks(namesInDir)
            }
        } else {
            selectEntriesByNameThenApplyMarks(selectedNames)
        }
        when (mode) {
            ViewMode.THUMBNAIL -> thumbnailList.requestFocusInWindow()
            ViewMode.LIST -> list.requestFocusInWindow()
            else -> table.requestFocusInWindow()
        }
    }

    if (carriedPrefix != null) {
        // Defer so the focus/card transition completes before the popup reattaches.
        SwingUtilities.invokeLater { restoreSpeedSearchPrefix(mode, carriedPrefix) }
    }
}

private fun FileTab.restoreSpeedSearchPrefix(mode: ViewMode, prefix: String) {
    val target: SpeedSearchBase<*>? = when (mode) {
        ViewMode.TABLE -> tableSpeedSearch
        ViewMode.LIST -> listSpeedSearch
        ViewMode.THUMBNAIL -> thumbnailSpeedSearch
        ViewMode.TREE -> treeSpeedSearch
    }
    target?.showPopup(prefix)
}

internal fun FileTab.rebuildFullTree(selectNames: Set<String> = emptySet()) {
    fileOps.launch {
        val vfs = currentVfs

        // Collect ancestor paths from root to currentPath (inclusive)
        val ancestors = mutableListOf<Path>()
        if (vfs != null) {
            var p: Path? = currentPath
            while (p != null && !vfs.isRoot(p)) {
                ancestors.add(0, p)
                p = p.parent
            }
            ancestors.add(0, vfs.root)
        } else {
            var p: Path? = currentPath
            while (p != null) {
                ancestors.add(0, p)
                p = p.parent
            }
        }

        // For each ancestor (except the root), load its parent's children (siblings).
        // For the last ancestor (currentPath), load its own children too.
        // Structure: ancestorSiblings[i] = siblings of ancestors[i] (children of ancestors[i]'s parent)
        // For ancestors[0] (root), we load its children directly since it has no parent to list siblings from.
        data class LevelData(
            val ancestorPath: Path,
            val entries: List<FileEntry>,  // entries at this level (siblings of the ancestor)
        )

        val levels = mutableListOf<LevelData>()

        // Level 0: children of the root
        val rootEntries = try {
            vfs?.listFiles(ancestors[0]) ?: fileOps.listFiles(ancestors[0])
        } catch (_: Exception) {
            emptyList()
        }.filter { !it.isParentLink }
        levels.add(LevelData(ancestors[0], rootEntries))

        // Levels 1..n-1: for each non-root ancestor, list its parent's children
        // But we already have ancestors[i-1]'s children from the previous level if ancestors[i-1] == parent.
        // Actually, ancestors[i] is a child of ancestors[i-1], so we need children of ancestors[i-1].
        // We already loaded children of ancestors[0] above. For ancestors[1], its parent is ancestors[0],
        // and we already have those entries. So we only need to load children of ancestors[i] for i >= 1.
        for (i in 1 until ancestors.size) {
            val entries = try {
                vfs?.listFiles(ancestors[i]) ?: fileOps.listFiles(ancestors[i])
            } catch (_: Exception) {
                emptyList()
            }.filter { !it.isParentLink }
            levels.add(LevelData(ancestors[i], entries))
        }

        withContext(Dispatchers.EDT) {
            insideViewSwitch = true
            try {
            treeRootNode.removeAllChildren()

            val ancestorNodes = mutableListOf<DefaultMutableTreeNode>()
            var currentParentNode = treeRootNode

            for (levelIdx in levels.indices) {
                val level = levels[levelIdx]
                val entries = level.entries
                val nextAncestorPath = ancestors.getOrNull(levelIdx + 1)
                val ancestorPathNormalized = nextAncestorPath?.normalize()

                // First pass: find the ancestor node for the next level (if any)
                var nextParentNode: DefaultMutableTreeNode? = null

                for (entry in entries) {
                    val node = DefaultMutableTreeNode(entry)
                    if (entry.isDirectory && nextParentNode == null && nextAncestorPath != null &&
                            (entry.path == nextAncestorPath ||
                             entry.path.normalize() == ancestorPathNormalized ||
                             entry.path.toAbsolutePath().normalize() == nextAncestorPath.toAbsolutePath().normalize())) {
                        // This directory is on the ancestor path — its children come from the next level
                        nextParentNode = node
                        ancestorNodes.add(node)
                        currentParentNode.add(node)
                    } else if (entry.isDirectory) {
                        // Sibling directory — add lazy placeholder
                        node.add(DefaultMutableTreeNode("Loading..."))
                        currentParentNode.add(node)
                    } else {
                        currentParentNode.add(node)
                    }
                }

                // If we didn't find the ancestor entry in the listing (e.g., hidden dir, permission issue),
                // create an explicit node for it so the tree structure stays correct
                if (nextParentNode == null && nextAncestorPath != null) {
                    val syntheticEntry = FileEntry(
                        name = nextAncestorPath.fileName?.toString() ?: nextAncestorPath.toString(),
                        path = nextAncestorPath,
                        isDirectory = true,
                        size = 0,
                        lastModified = null,
                        permissions = "",
                    )
                    nextParentNode = DefaultMutableTreeNode(syntheticEntry)
                    ancestorNodes.add(nextParentNode)
                    currentParentNode.add(nextParentNode)
                }

                // Advance to the next level's parent AFTER all siblings have been added
                if (nextParentNode != null) {
                    currentParentNode = nextParentNode
                }
            }

            treeModel.nodeStructureChanged(treeRootNode)

            // Expand all ancestor nodes so the path to current directory is visible
            for (node in ancestorNodes) {
                tree.expandPath(TreePath(node.path))
            }

            // Select entries by name in the current directory node
            if (selectNames.isNotEmpty()) {
                val currentDirNode = if (ancestorNodes.isNotEmpty()) ancestorNodes.last() else treeRootNode
                val treePaths = (0 until currentDirNode.childCount).mapNotNull { i ->
                    val child = currentDirNode.getChildAt(i) as DefaultMutableTreeNode
                    val entry = child.userObject as? FileEntry
                    if (entry != null && entry.name in selectNames) TreePath(child.path) else null
                }.toTypedArray()
                if (treePaths.isNotEmpty()) {
                    tree.selectionPaths = treePaths
                    tree.scrollPathToVisible(treePaths.first())
                }
            }
            } finally {
                insideViewSwitch = false
            }

            if (markedPaths.isNotEmpty()) restoreTreeMarks()
        }
    }
}

internal fun FileTab.applyMarksForCurrentView() {
    when (viewMode) {
        ViewMode.TABLE -> restoreTableMarks()
        ViewMode.LIST -> restoreListMarks(list, listModel)
        ViewMode.THUMBNAIL -> restoreListMarks(thumbnailList, thumbnailListModel)
        ViewMode.TREE -> restoreTreeMarks()
    }
}

// Suppresses the per-event mark-restore in the selection listeners so the inevitable
// clearSelection + N addRowSelectionInterval calls inside selectEntriesByName don't each
// fire a full O(rowCount) restoreTableMarks. One explicit applyMarksForCurrentView at the
// end does the work once.
private fun FileTab.selectEntriesByNameThenApplyMarks(names: Set<String>) {
    insideViewSwitch = true
    try {
        selectEntriesByName(names)
    } finally {
        insideViewSwitch = false
    }
    if (markedPaths.isNotEmpty()) applyMarksForCurrentView()
}

internal fun FileTab.selectEntriesByName(names: Set<String>) {
    if (viewMode == ViewMode.TABLE) {
        table.clearSelection()
        var firstSelectedRow = -1
        for (viewRow in 0 until table.rowCount) {
            val modelRow = table.convertRowIndexToModel(viewRow)
            val entry = tableModel.getEntryAt(modelRow) ?: continue
            if (entry.name in names) {
                table.addRowSelectionInterval(viewRow, viewRow)
                if (firstSelectedRow == -1) firstSelectedRow = viewRow
            }
        }
        if (firstSelectedRow >= 0) {
            table.scrollRectToVisible(table.getCellRect(firstSelectedRow, 0, true))
        }
    } else if (viewMode == ViewMode.LIST) {
        list.clearSelection()
        val indices = (0 until listModel.size()).filter { listModel.getElementAt(it).name in names }.toIntArray()
        if (indices.isNotEmpty()) {
            list.selectedIndices = indices
            list.ensureIndexIsVisible(indices.first())
        }
    } else if (viewMode == ViewMode.THUMBNAIL) {
        thumbnailList.clearSelection()
        val indices = (0 until thumbnailListModel.size()).filter { thumbnailListModel.getElementAt(it).name in names }.toIntArray()
        if (indices.isNotEmpty()) {
            thumbnailList.selectedIndices = indices
            thumbnailList.ensureIndexIsVisible(indices.first())
        }
    }
}
