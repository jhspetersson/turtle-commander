package io.github.jhspetersson.turtlecommander.ui
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.util.DateTimeFormatters
import io.github.jhspetersson.turtlecommander.util.formatSize

import java.nio.file.Path
import javax.swing.table.AbstractTableModel

class FileTableModel : AbstractTableModel() {

    companion object {
        const val COL_NAME = 0
        const val COL_EXT = 1
        const val COL_SIZE = 2
        const val COL_CREATED = 3
        const val COL_DATE = 4
        const val COL_OWNER = 5
        const val COL_GROUP = 6
        const val COL_PERMS = 7
        const val COL_INODE = 8
        const val COL_LINKS = 9
        const val PARENT_MARKER = ".."
        const val PARENT_NUMERIC = Long.MIN_VALUE
        const val DIR_NUMERIC = -1L
    }

    private val columns = arrayOf("Name", "Ext", "Size", "Date Created", "Date Modified", "User", "Group", "Permissions", "Inode", "Links")
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
            COL_CREATED -> Long::class.javaObjectType
            COL_DATE -> Long::class.javaObjectType
            COL_INODE -> Long::class.javaObjectType
            COL_LINKS -> Long::class.javaObjectType
            else -> String::class.java
        }
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val entry = entries.getOrNull(rowIndex) ?: return ""
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
            COL_CREATED -> if (entry.isParentLink) PARENT_NUMERIC else entry.creationTime?.toMillis() ?: 0L
            COL_DATE -> if (entry.isParentLink) PARENT_NUMERIC else entry.lastModified?.toMillis() ?: 0L
            COL_OWNER -> if (entry.isParentLink) PARENT_MARKER else entry.owner
            COL_GROUP -> if (entry.isParentLink) PARENT_MARKER else entry.group
            COL_PERMS -> if (entry.isParentLink) PARENT_MARKER else entry.permissions
            COL_INODE -> if (entry.isParentLink) PARENT_NUMERIC else entry.inode ?: 0L
            COL_LINKS -> if (entry.isParentLink) PARENT_NUMERIC else entry.nlink?.toLong() ?: 0L
            else -> ""
        }
    }

    fun getDisplayValue(rowIndex: Int, columnIndex: Int): String {
        val entry = entries.getOrNull(rowIndex) ?: return ""
        return when {
            entry.isParentLink && columnIndex == COL_NAME -> ".."
            entry.isParentLink -> ""
            columnIndex == COL_SIZE -> if (entry.isDirectory) {
                val calcSize = directorySizeProvider?.invoke(entry.path)
                if (calcSize != null) formatSize(calcSize) else "<DIR>"
            } else formatSize(entry.size)
            columnIndex == COL_CREATED -> entry.creationTime?.let {
                DateTimeFormatters.format(it.toMillis())
            } ?: ""
            columnIndex == COL_DATE -> entry.lastModified?.let {
                DateTimeFormatters.format(it.toMillis())
            } ?: ""
            columnIndex == COL_INODE -> entry.inode?.toString() ?: ""
            columnIndex == COL_LINKS -> entry.nlink?.toString() ?: ""
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
