package io.github.jhspetersson.turtlecommander.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBList
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.ui.favoriteIcon
import java.awt.*
import javax.swing.*

internal class FavoritesEditor {
    private val listModel = DefaultListModel<FileManagerStateService.FavoriteEntry>()
    private val list: JBList<FileManagerStateService.FavoriteEntry>
    val panel: JPanel

    init {
        loadFavorites()

        list = JBList(listModel)
        list.visibleRowCount = 6
        list.cellRenderer = FavoriteListCellRenderer()

        val addButton = JButton("Add").apply {
            addActionListener {
                val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
                    title = "Add Favorite"
                    description = "Select a directory to add to favorites"
                }
                val chosen = FileChooser.chooseFile(descriptor, null, null)
                if (chosen != null) {
                    listModel.addElement(FileManagerStateService.FavoriteEntry(chosen.path))
                }
            }
        }
        val removeButton = JButton("Remove").apply {
            addActionListener {
                val idx = list.selectedIndex
                if (idx >= 0) listModel.remove(idx)
            }
        }
        val moveUpButton = JButton("Up").apply {
            addActionListener {
                val idx = list.selectedIndex
                if (idx > 0) {
                    val item = listModel.remove(idx)
                    listModel.add(idx - 1, item)
                    list.selectedIndex = idx - 1
                }
            }
        }
        val moveDownButton = JButton("Down").apply {
            addActionListener {
                val idx = list.selectedIndex
                if (idx >= 0 && idx < listModel.size() - 1) {
                    val item = listModel.remove(idx)
                    listModel.add(idx + 1, item)
                    list.selectedIndex = idx + 1
                }
            }
        }
        val colorButton = JButton("Color...")
        colorButton.addActionListener {
            val idx = list.selectedIndex
            if (idx < 0) return@addActionListener
            showColorChooser(listModel.getElementAt(idx), idx, colorButton)
        }
        val iconButton = JButton("Icon...")
        iconButton.addActionListener {
            val idx = list.selectedIndex
            if (idx < 0) return@addActionListener
            val entry = listModel.getElementAt(idx)
            showIconChooserPopup(iconButton) { key ->
                listModel.set(idx, FileManagerStateService.FavoriteEntry(entry.path, entry.color, key))
                list.repaint()
            }
        }

        val favButtons = listOf(addButton, removeButton, moveUpButton, moveDownButton, colorButton, iconButton)
        for (btn in favButtons) {
            btn.maximumSize = Dimension(Int.MAX_VALUE, btn.preferredSize.height)
        }

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(addButton)
            add(removeButton)
            add(Box.createVerticalStrut(8))
            add(moveUpButton)
            add(moveDownButton)
            add(Box.createVerticalStrut(8))
            add(colorButton)
            add(iconButton)
        }

        panel = JPanel(BorderLayout(8, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createTitledBorder("Favorites")
            add(JScrollPane(list), BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.EAST)
            preferredSize = Dimension(Int.MAX_VALUE, 260)
            maximumSize = Dimension(Int.MAX_VALUE, 260)
        }
    }

    fun isModified(): Boolean {
        val currentProject = ProjectManager.getInstance().openProjects.firstOrNull() ?: return false
        val stateService = currentProject.service<FileManagerStateService>()
        val saved = stateService.getFavoriteEntries()
        if (listModel.size() != saved.size) return true
        for (i in 0 until listModel.size()) {
            val m = listModel.getElementAt(i)
            val s = saved[i]
            if (m.path != s.path || m.color != s.color || m.icon != s.icon) return true
        }
        return false
    }

    fun apply() {
        val currentProject = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val stateService = currentProject.service<FileManagerStateService>()
        val newEntries = (0 until listModel.size()).map { listModel.getElementAt(it) }
        stateService.setFavoriteEntries(newEntries)
    }

    fun reset() {
        listModel.clear()
        loadFavorites()
    }

    private fun loadFavorites() {
        val currentProject = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val stateService = currentProject.service<FileManagerStateService>()
        stateService.getFavoriteEntries().forEach {
            listModel.addElement(FileManagerStateService.FavoriteEntry(it.path, it.color, it.icon))
        }
    }

    private fun showColorChooser(entry: FileManagerStateService.FavoriteEntry, idx: Int, anchor: JComponent) {
        val current = entry.color.takeIf { it.isNotBlank() }?.let { runCatching { Color.decode(it) }.getOrNull() }
        showColorPopup(anchor, "Choose Favorite Color", current) { picked ->
            val hex = if (picked == null) "" else String.format("#%02X%02X%02X", picked.red, picked.green, picked.blue)
            listModel.set(idx, FileManagerStateService.FavoriteEntry(entry.path, hex, entry.icon))
            list.repaint()
        }
    }
}

private class FavoriteListCellRenderer : ListCellRenderer<FileManagerStateService.FavoriteEntry> {
    private val delegate = SimpleColoredComponent()

    override fun getListCellRendererComponent(
        list: JList<out FileManagerStateService.FavoriteEntry>,
        value: FileManagerStateService.FavoriteEntry?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        delegate.clear()
        delegate.background = if (isSelected) list.selectionBackground else list.background
        delegate.foreground = if (isSelected) list.selectionForeground else list.foreground
        delegate.isOpaque = true
        if (value != null) {
            delegate.icon = favoriteIcon(value.color, value.icon)
            delegate.append(value.path)
        }
        return delegate
    }
}
