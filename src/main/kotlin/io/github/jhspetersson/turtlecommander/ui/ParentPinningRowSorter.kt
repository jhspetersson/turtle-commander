package io.github.jhspetersson.turtlecommander.ui
import io.github.jhspetersson.turtlecommander.settings.ComponentStyle
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings

import javax.swing.SortOrder
import javax.swing.table.TableRowSorter

/**
 * Wraps a cell value with directory/parent metadata so comparators can group
 * directories before files without needing extra sort keys.
 */
internal data class DirAwareValue(val value: Any, val isDirectory: Boolean, val isParent: Boolean)

/**
 * Row sorter that pins the parent ".." entry at the top and optionally groups
 * directories before files. Uses a [DirAwareModelWrapper] to enrich cell values
 * with directory metadata, so every column's comparator can detect directories.
 *
 * @param sortWithDirectories supplier returning true when directories should be
 *        sorted together with files (no grouping), false to group dirs first.
 */
internal class ParentPinningRowSorter(
    fileModel: FileTableModel,
    private val sortWithDirectories: () -> Boolean = {
        TurtleCommanderSettings.getInstance().state.sortWithDirectories
    },
) : TableRowSorter<FileTableModel>(fileModel) {

    /**
     * Model wrapper that enriches cell values with directory metadata.
     * The row sorter uses this wrapper internally; the table renderer still
     * reads from the real model, so display is unaffected.
     */
    private inner class DirAwareModelWrapper(
        private val tableModel: FileTableModel,
    ) : ModelWrapper<FileTableModel, Int>() {
        override fun getModel(): FileTableModel = tableModel
        override fun getColumnCount(): Int = tableModel.columnCount
        override fun getRowCount(): Int = tableModel.rowCount
        override fun getIdentifier(row: Int): Int = row
        override fun getValueAt(row: Int, column: Int): Any {
            val value = tableModel.getValueAt(row, column)
            val entry = tableModel.getEntryAt(row)
            return DirAwareValue(value, entry?.isDirectory == true, entry?.isParentLink == true)
        }
        override fun getStringValueAt(row: Int, column: Int): String {
            return tableModel.getValueAt(row, column).toString()
        }
    }

    init {
        setModelWrapper(DirAwareModelWrapper(fileModel))
        for (col in 0 until fileModel.columnCount) {
            setComparator(col, ParentFirstComparator(this, col, sortWithDirectories))
        }
    }

    override fun setSortKeys(keys: MutableList<out SortKey>?) {
        if (keys.isNullOrEmpty()) {
            super.setSortKeys(keys)
            return
        }
        val primary = keys[0]
        if (primary.column == FileTableModel.COL_NAME) {
            super.setSortKeys(mutableListOf(primary))
            return
        }
        // Always use name ascending as secondary for stable ordering
        val combined = mutableListOf(primary,
            SortKey(FileTableModel.COL_NAME, SortOrder.ASCENDING)
        )
        super.setSortKeys(combined)
    }
}

internal class ParentFirstComparator(
    private val sorter: ParentPinningRowSorter,
    private val column: Int,
    private val sortWithDirectories: () -> Boolean,
) : Comparator<Any> {
    override fun compare(o1: Any?, o2: Any?): Int {
        val dav1 = o1 as? DirAwareValue ?: return 0
        val dav2 = o2 as? DirAwareValue ?: return 0

        // Parent ".." always sorts first.
        // TableRowSorter negates the result for DESC, so we counter that.
        if (dav1.isParent || dav2.isParent) {
            if (dav1.isParent && dav2.isParent) return 0
            val parentFirst = if (dav1.isParent) -1 else 1
            return if (isDescending()) -parentFirst else parentFirst
        }

        // Group directories before files when the setting is off.
        // Same DESC counter-reversal to keep dirs on top.
        if (!sortWithDirectories()) {
            if (dav1.isDirectory != dav2.isDirectory) {
                val dirFirst = if (dav1.isDirectory) -1 else 1
                return if (isDescending()) -dirFirst else dirFirst
            }
        }

        val v1 = dav1.value
        val v2 = dav2.value

        if (column == FileTableModel.COL_SIZE || column == FileTableModel.COL_CREATED || column == FileTableModel.COL_DATE) {
            val l1 = (v1 as? Long) ?: 0L
            val l2 = (v2 as? Long) ?: 0L
            return l1.compareTo(l2)
        }

        val s1 = v1.toString()
        val s2 = v2.toString()
        return s1.compareTo(s2, ignoreCase = true)
    }

    private fun isDescending(): Boolean {
        val keys = sorter.sortKeys
        val key = keys.firstOrNull { it.column == column } ?: return false
        return key.sortOrder == SortOrder.DESCENDING
    }
}
