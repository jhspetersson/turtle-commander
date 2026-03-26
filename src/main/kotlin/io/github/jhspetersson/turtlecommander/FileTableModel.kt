package io.github.jhspetersson.turtlecommander

import java.nio.file.Path
import javax.swing.table.AbstractTableModel
import java.text.SimpleDateFormat
import java.util.Date

class FileTableModel : AbstractTableModel() {

    companion object {
        const val COL_NAME = 0
        const val COL_EXT = 1
        const val COL_SIZE = 2
        const val COL_DATE = 3
        const val COL_PERMS = 4
        const val PARENT_MARKER = ".."
        const val PARENT_NUMERIC = Long.MIN_VALUE
        const val DIR_NUMERIC = -1L
    }

    private val columns = arrayOf("Name", "Ext", "Size", "Date Modified", "Permissions")
    private var entries: List<FileEntry> = emptyList()
    var directorySizeProvider: ((Path) -> Long?)? = null

    fun setEntries(newEntries: List<FileEntry>) {
        entries = newEntries
        fireTableDataChanged()
    }

    fun getEntryAt(row: Int): FileEntry? = entries.getOrNull(row)

    override fun getRowCount(): Int = entries.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> {
        return when (columnIndex) {
            COL_SIZE -> Long::class.javaObjectType
            COL_DATE -> Long::class.javaObjectType
            else -> String::class.java
        }
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val entry = entries[rowIndex]
        return when (columnIndex) {
            COL_NAME -> when {
                entry.isParentLink -> ".."
                entry.isDirectory -> entry.name
                !entry.name.contains('.') || entry.name.startsWith('.') -> entry.name
                else -> entry.name.substringBeforeLast('.')
            }
            COL_EXT -> when {
                entry.isParentLink -> PARENT_MARKER
                entry.isDirectory -> ""
                !entry.name.contains('.') || entry.name.startsWith('.') -> ""
                else -> entry.name.substringAfterLast('.')
            }
            COL_SIZE -> if (entry.isParentLink) PARENT_NUMERIC else if (entry.isDirectory) {
                directorySizeProvider?.invoke(entry.path) ?: DIR_NUMERIC
            } else entry.size
            COL_DATE -> if (entry.isParentLink) PARENT_NUMERIC else entry.lastModified?.toMillis() ?: 0L
            COL_PERMS -> if (entry.isParentLink) PARENT_MARKER else entry.permissions
            else -> ""
        }
    }

    fun getDisplayValue(rowIndex: Int, columnIndex: Int): String {
        val entry = entries[rowIndex]
        return when {
            entry.isParentLink && columnIndex == COL_NAME -> ".."
            entry.isParentLink -> ""
            columnIndex == COL_SIZE -> if (entry.isDirectory) {
                val calcSize = directorySizeProvider?.invoke(entry.path)
                if (calcSize != null) formatSize(calcSize) else "<DIR>"
            } else formatSize(entry.size)
            columnIndex == COL_DATE -> entry.lastModified?.let {
                SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(it.toMillis()))
            } ?: ""
            else -> getValueAt(rowIndex, columnIndex).toString()
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
        if (columnIndex != COL_NAME) return false
        val entry = entries.getOrNull(rowIndex) ?: return false
        return !entry.isParentLink
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        // Rename is handled via CellEditor stop callback in FileTab
    }

}
