package io.github.jhspetersson.turtlecommander.ui

import io.github.jhspetersson.turtlecommander.service.ThumbnailCache
import io.github.jhspetersson.turtlecommander.model.DirectoryType
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings

import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer

internal fun FileTab.inactiveSelectionBackground(): Color {
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

internal class FileNameCellRenderer(private val tab: FileTab) : DefaultTableCellRenderer() {
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
                table.foreground
            }
        }
        return this
    }
}

internal class DisplayValueRenderer(private val tab: FileTab) : DefaultTableCellRenderer() {
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
            foreground = table.foreground
        }
        return comp
    }
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
