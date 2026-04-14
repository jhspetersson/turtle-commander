package io.github.jhspetersson.turtlecommander.ui

import com.intellij.ui.*
import com.intellij.ui.speedSearch.SpeedSearchSupply
import com.intellij.ui.speedSearch.SpeedSearchUtil
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

/**
 * Wraps matched speed-search substrings in an HTML <span> so they appear highlighted
 * in JLabel-based renderers (which don't support SimpleColoredComponent's native path).
 * Returns the original text unchanged when no speed search is active or no match exists.
 */
internal fun highlightSpeedSearchMatches(speedSearchComponent: JComponent, text: String): String {
    val supply = SpeedSearchSupply.getSupply(speedSearchComponent) ?: return text
    val fragments = supply.matchingFragments(text) ?: return text
    val ordered = fragments.sortedBy { it.startOffset }
    if (ordered.isEmpty()) return text
    val bg = UIManager.getColor("SearchMatch.startBackground")
        ?: UIManager.getColor("SpeedSearch.background")
        ?: JBColor(Color(0xFFC800), Color(0x5E5339))
    val fg = UIManager.getColor("SearchMatch.foreground")
        ?: UIManager.getColor("SpeedSearch.foreground")
    val style = buildString {
        append("background:").append(toHex(bg)).append(';')
        if (fg != null) append("color:").append(toHex(fg)).append(';')
    }
    val sb = StringBuilder("<html>")
    var cursor = 0
    for (range in ordered) {
        val start = range.startOffset.coerceIn(0, text.length)
        val end = range.endOffset.coerceIn(start, text.length)
        if (start > cursor) sb.append(escapeHtml(text.substring(cursor, start)))
        sb.append("<span style='").append(style).append("'>")
        sb.append(escapeHtml(text.substring(start, end)))
        sb.append("</span>")
        cursor = end
    }
    if (cursor < text.length) sb.append(escapeHtml(text.substring(cursor)))
    sb.append("</html>")
    return sb.toString()
}

private fun toHex(c: Color): String = "#%02X%02X%02X".format(c.red, c.green, c.blue)

private fun escapeHtml(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '&' -> append("&amp;")
        else -> append(c)
    }
}

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
) : ColoredTableCellRenderer() {
    init {
        // Let us paint our own background (including inactive-selection tint).
        isOpaque = true
    }

    override fun customizeCellRenderer(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ) {
        val modelRow = table.convertRowIndexToModel(row)
        val entry = tab.tableModel.getEntryAt(modelRow)
        icon = if (entry != null) fileEntryIcon(entry, tab.enableFileNameHighlighting) else null

        if (selected && !table.hasFocus()) {
            background = tab.inactiveSelectionBackground()
        } else if (!selected) {
            background = table.background
        }

        val fg: Color? = when {
            selected && !table.hasFocus() -> tab.inactiveSelectionForeground()
            selected -> null // default selection foreground from ColoredTableCellRenderer
            tab.enableFileNameHighlighting && entry != null && entry.isDirectory && entry.directoryType != DirectoryType.NONE ->
                DirectoryIcons.getColor(entry.directoryType)
            else -> style?.parsedFontColor() ?: table.foreground
        }

        val fontStyle = style?.getFont(table.font)?.style ?: java.awt.Font.PLAIN
        val attrs = if (fg != null) SimpleTextAttributes(fontStyle, fg) else SimpleTextAttributes(fontStyle, null)
        val text = value as? String ?: value?.toString().orEmpty()
        append(text, attrs)

        if (style != null) {
            val f = style.getFont(table.font)
            if (f != null) font = f
        }

        SpeedSearchUtil.applySpeedSearchHighlighting(table, this, true, selected)

        // Suppress the focus rectangle the LAF paints around the focused cell.
        // ColoredTableCellRenderer may install a focus border in super; clear it.
        border = null
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

internal class FileListCellRenderer(private val tab: FileTab) : ColoredListCellRenderer<FileEntry>() {
    override fun customizeCellRenderer(
        list: JList<out FileEntry>,
        value: FileEntry?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        val entry = value ?: return
        icon = fileEntryIcon(entry, tab.enableFileNameHighlighting)
        val fg: Color? = when {
            selected -> null
            tab.enableFileNameHighlighting && entry.isDirectory && entry.directoryType != DirectoryType.NONE ->
                DirectoryIcons.getColor(entry.directoryType)
            else -> null
        }
        val attrs = if (fg != null) SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fg) else SimpleTextAttributes.REGULAR_ATTRIBUTES
        append(entry.name, attrs)
        SpeedSearchUtil.applySpeedSearchHighlighting(list, this, true, selected)
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
        val displayName = if (name.length > 16) name.substring(0, 14) + "\u2026" else name
        nameLabel.text = highlightSpeedSearchMatches(list, displayName)
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

internal class FileTreeCellRenderer(private val tab: FileTab) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        val entry = node.userObject as? FileEntry ?: run {
            append(node.userObject?.toString().orEmpty())
            return
        }
        icon = fileEntryIcon(entry, tab.enableFileNameHighlighting)
        val fg: Color? = when {
            selected -> null
            tab.enableFileNameHighlighting && entry.isDirectory && entry.directoryType != DirectoryType.NONE ->
                DirectoryIcons.getColor(entry.directoryType)
            else -> null
        }
        val attrs = if (fg != null) SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fg) else SimpleTextAttributes.REGULAR_ATTRIBUTES
        append(entry.name, attrs)
        SpeedSearchUtil.applySpeedSearchHighlighting(tree, this, true, selected)
    }
}
