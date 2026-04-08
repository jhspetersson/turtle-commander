package io.github.jhspetersson.turtlecommander.ui

import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import io.github.jhspetersson.turtlecommander.model.DirectoryType
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.ThumbnailCache
import io.github.jhspetersson.turtlecommander.settings.ComponentStyle
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer

private val DEFAULT_COLUMN_WIDTHS = mapOf(
    "Name" to 275,
    "Ext" to 80,
    "Size" to 100,
    "Date Created" to 150,
    "Date Modified" to 150,
    "User" to 100,
    "Group" to 100,
    "Permissions" to 100,
)

internal val COLUMN_NAME_TO_MODEL_INDEX = mapOf(
    "Name" to FileTableModel.COL_NAME,
    "Ext" to FileTableModel.COL_EXT,
    "Size" to FileTableModel.COL_SIZE,
    "Date Created" to FileTableModel.COL_CREATED,
    "Date Modified" to FileTableModel.COL_DATE,
    "User" to FileTableModel.COL_OWNER,
    "Group" to FileTableModel.COL_GROUP,
    "Permissions" to FileTableModel.COL_PERMS,
)

internal fun applyColumnConfig(tab: FileTab) {
    val table = tab.table
    val columns = TurtleCommanderSettings.getInstance().getEffectiveColumns()
    val columnStyleMap = mutableMapOf<Int, ComponentStyle>()

    // Remove hidden columns (iterate in reverse to avoid index shifting)
    val hiddenModelIndices = mutableSetOf<Int>()
    for (col in columns) {
        if (!col.visible) {
            val modelIdx = COLUMN_NAME_TO_MODEL_INDEX[col.id] ?: continue
            hiddenModelIndices.add(modelIdx)
        }
        if (!col.style.isDefault()) {
            val modelIdx = COLUMN_NAME_TO_MODEL_INDEX[col.id] ?: continue
            columnStyleMap[modelIdx] = col.style
        }
    }

    // Set renderers on all columns first (before removing any)
    for (i in 0 until table.columnModel.columnCount) {
        val tc = table.columnModel.getColumn(i)
        val modelIdx = tc.modelIndex
        val style = columnStyleMap[modelIdx]
        if (modelIdx == FileTableModel.COL_NAME) {
            tc.cellRenderer = StyledFileNameCellRenderer(tab, style)
        } else {
            tc.cellRenderer = StyledDisplayValueRenderer(tab, style)
        }
        val width = DEFAULT_COLUMN_WIDTHS[
            COLUMN_NAME_TO_MODEL_INDEX.entries.find { it.value == modelIdx }?.key
        ] ?: 80
        tc.preferredWidth = width
        tc.width = width
    }

    // Remove hidden columns
    for (modelIdx in hiddenModelIndices) {
        for (i in table.columnModel.columnCount - 1 downTo 0) {
            if (table.columnModel.getColumn(i).modelIndex == modelIdx) {
                table.removeColumn(table.columnModel.getColumn(i))
                break
            }
        }
    }

    // Reorder visible columns to match settings order
    val desiredOrder = columns.filter { it.visible }.mapNotNull { COLUMN_NAME_TO_MODEL_INDEX[it.id] }
    for (targetViewIdx in desiredOrder.indices) {
        val wantedModelIdx = desiredOrder[targetViewIdx]
        var currentViewIdx = -1
        for (i in 0 until table.columnModel.columnCount) {
            if (table.columnModel.getColumn(i).modelIndex == wantedModelIdx) {
                currentViewIdx = i
                break
            }
        }
        if (currentViewIdx >= 0 && currentViewIdx != targetViewIdx && targetViewIdx < table.columnModel.columnCount) {
            table.columnModel.moveColumn(currentViewIdx, targetViewIdx)
        }
    }
}

internal class StyledFileNameCellRenderer(
    private val tab: FileTab,
    private val style: ComponentStyle?,
) : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
        if (isSelected && !table.hasFocus()) {
            background = tab.inactiveSelectionBackground()
            foreground = tab.inactiveSelectionForeground()
        }
        val modelRow = table.convertRowIndexToModel(row)
        val entry = tab.tableModel.getEntryAt(modelRow)
        icon = if (entry != null) fileEntryIcon(entry, tab.enableFileNameHighlighting) else null
        if (isSelected && !table.hasFocus()) {
            foreground = tab.inactiveSelectionForeground()
        } else if (!isSelected) {
            background = table.background
            foreground = if (tab.enableFileNameHighlighting && entry != null && entry.isDirectory && entry.directoryType != DirectoryType.NONE) {
                DirectoryIcons.getColor(entry.directoryType)
            } else {
                style?.parsedFontColor() ?: table.foreground
            }
        }
        if (style != null) {
            val f = style.getFont(table.font)
            if (f != null) font = f
        }
        return this
    }
}

internal class StyledDisplayValueRenderer(
    private val tab: FileTab,
    private val style: ComponentStyle?,
) : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val modelRow = table.convertRowIndexToModel(row)
        val modelCol = table.convertColumnIndexToModel(column)
        val displayValue = tab.tableModel.getDisplayValue(modelRow, modelCol)
        val comp = super.getTableCellRendererComponent(table, displayValue, isSelected, false, row, column)
        if (isSelected && !table.hasFocus()) {
            background = tab.inactiveSelectionBackground()
            foreground = tab.inactiveSelectionForeground()
        } else if (!isSelected) {
            background = table.background
            foreground = style?.parsedFontColor() ?: table.foreground
        }
        if (style != null) {
            val f = style.getFont(table.font)
            if (f != null) font = f
        }
        return comp
    }
}

internal fun FileTab.inactiveSelectionBackground(): Color {
    val panelStyle = TurtleCommanderSettings.getInstance().state.styles.panelStyle
    val custom = panelStyle.parsedSelectedColor()
    if (custom != null) return custom
    val active = table.selectionBackground
    val bg = table.background
    fun blend(a: Int, b: Int) = (a + b) / 2
    return JBColor(
        Color(blend(active.red, bg.red), blend(active.green, bg.green), blend(active.blue, bg.blue)),
        Color(blend(active.red, bg.red), blend(active.green, bg.green), blend(active.blue, bg.blue)),
    )
}

internal fun FileTab.inactiveSelectionForeground(): Color {
    return table.foreground
}

internal class FileListCellRenderer(private val tab: FileTab) : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val entry = value as? FileEntry ?: return this
        text = entry.name
        icon = fileEntryIcon(entry, tab.enableFileNameHighlighting)
        if (!isSelected && tab.enableFileNameHighlighting && entry.isDirectory && entry.directoryType != DirectoryType.NONE) {
            foreground = DirectoryIcons.getColor(entry.directoryType)
        }
        return this
    }
}

internal class FileThumbnailCellRenderer(private val tab: FileTab) : ListCellRenderer<FileEntry> {
    private val panel = JPanel(BorderLayout())
    private val iconLabel = JLabel()
    private val nameLabel = JLabel()

    init {
        panel.isOpaque = true
        panel.border = BorderFactory.createEmptyBorder(6, 4, 4, 4)
        iconLabel.horizontalAlignment = JLabel.CENTER
        iconLabel.verticalAlignment = JLabel.CENTER
        nameLabel.horizontalAlignment = JLabel.CENTER
        panel.add(iconLabel, BorderLayout.CENTER)
        panel.add(nameLabel, BorderLayout.SOUTH)
    }

    override fun getListCellRendererComponent(
        list: JList<out FileEntry>,
        value: FileEntry?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val entry = value ?: return panel
        val thumbnail = if (!entry.isDirectory && !entry.isParentLink && !tab.isInsideArchive
            && ThumbnailCache.isImageFile(entry.name)) {
            val cached = ThumbnailCache.getCachedThumbnail(entry.path)
            if (cached == null) {
                ThumbnailCache.requestThumbnail(
                    entry.path, entry.lastModified,
                    isStillVisible = {
                        index in tab.thumbnailList.firstVisibleIndex..tab.thumbnailList.lastVisibleIndex
                    },
                ) {
                    SwingUtilities.invokeLater { tab.thumbnailList.repaint() }
                }
            }
            cached
        } else null

        if (thumbnail != null) {
            iconLabel.icon = thumbnail
        } else {
            val entryIcon = fileEntryIcon(entry, tab.enableFileNameHighlighting)
            iconLabel.icon = if (entryIcon != null) IconUtil.scale(entryIcon, list, 2.0f) else null
        }
        val name = entry.name
        nameLabel.text = if (name.length > 16) name.substring(0, 14) + "\u2026" else name
        nameLabel.toolTipText = name
        nameLabel.font = list.font
        if (isSelected) {
            panel.background = list.selectionBackground
            nameLabel.foreground = list.selectionForeground
        } else {
            panel.background = list.background
            if (tab.enableFileNameHighlighting && entry.isDirectory && entry.directoryType != DirectoryType.NONE) {
                nameLabel.foreground = DirectoryIcons.getColor(entry.directoryType)
            } else {
                nameLabel.foreground = list.foreground
            }
        }
        return panel
    }
}

internal class FileTreeCellRenderer(private val tab: FileTab) : DefaultTreeCellRenderer() {
    init {
        backgroundNonSelectionColor = tab.table.background
    }

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        sel: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
        val node = value as? DefaultMutableTreeNode ?: return this
        val entry = node.userObject as? FileEntry
        if (entry != null) {
            text = entry.name
            icon = fileEntryIcon(entry, tab.enableFileNameHighlighting)
            if (!sel && tab.enableFileNameHighlighting && entry.isDirectory && entry.directoryType != DirectoryType.NONE) {
                foreground = DirectoryIcons.getColor(entry.directoryType)
            }
        }
        return this
    }
}
