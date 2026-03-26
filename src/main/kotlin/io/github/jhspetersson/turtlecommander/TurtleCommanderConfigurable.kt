package io.github.jhspetersson.turtlecommander

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColorChooserService
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.ListCellRenderer
import javax.swing.SpinnerNumberModel

class TurtleCommanderConfigurable : Configurable {

    private var highlightingCheckBox: JCheckBox? = null
    private var commandBarCheckBox: JCheckBox? = null
    private var hideDriveSelectorCheckBox: JCheckBox? = null
    private var hideStatusBarCheckBox: JCheckBox? = null
    private var overwriteCheckBox: JCheckBox? = null
    private var sortWithDirectoriesCheckBox: JCheckBox? = null
    private var panelFontCombo: ComboBox<String>? = null
    private var panelFontSizeSpinner: JSpinner? = null
    private var tabFontCombo: ComboBox<String>? = null
    private var tabFontSizeSpinner: JSpinner? = null
    private var defaultViewModeCombo: ComboBox<String>? = null
    private var favoritesListModel: DefaultListModel<FileManagerStateService.FavoriteEntry>? = null
    private var favoritesList: JBList<FileManagerStateService.FavoriteEntry>? = null

    override fun getDisplayName(): String = "Turtle Commander"

    override fun createComponent(): JComponent {
        val settings = TurtleCommanderSettings.getInstance().state
        val fontFamilies = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames

        highlightingCheckBox = JCheckBox("Enable file name highlighting for project directories", settings.enableFileNameHighlighting)
        commandBarCheckBox = JCheckBox("Show command bar (F5 Copy, F6 Move, etc.)", settings.showCommandBar)
        hideDriveSelectorCheckBox = JCheckBox("Hide drive selector", settings.hideDriveSelector)
        hideStatusBarCheckBox = JCheckBox("Hide status bar", settings.hideStatusBar)
        overwriteCheckBox = JCheckBox("Always overwrite existing files during copy/move", settings.alwaysOverwriteFiles)
        sortWithDirectoriesCheckBox = JCheckBox("Sort directories together with files", settings.sortWithDirectories)

        val defaultLabel = "(Default)"
        val fontItems = arrayOf(defaultLabel) + fontFamilies

        panelFontCombo = ComboBox(DefaultComboBoxModel(fontItems)).apply {
            selectedItem = settings.panelFontFamily.ifEmpty { defaultLabel }
        }
        panelFontSizeSpinner = JSpinner(SpinnerNumberModel(
            if (settings.panelFontSize > 0) settings.panelFontSize else 13, 8, 48, 1
        ))

        tabFontCombo = ComboBox(DefaultComboBoxModel(fontItems)).apply {
            selectedItem = settings.tabFontFamily.ifEmpty { defaultLabel }
        }
        tabFontSizeSpinner = JSpinner(SpinnerNumberModel(
            if (settings.tabFontSize > 0) settings.tabFontSize else 12, 8, 48, 1
        ))

        val viewModeItems = arrayOf("Table", "List", "Thumbnail", "Tree")
        defaultViewModeCombo = ComboBox(DefaultComboBoxModel(viewModeItems)).apply {
            selectedItem = when (settings.defaultViewMode) {
                "LIST" -> "List"
                "THUMBNAIL" -> "Thumbnail"
                "TREE" -> "Tree"
                else -> "Table"
            }
        }

        val fontGrid = JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(2, 4, 2, 4)
            }

            gbc.gridx = 0; gbc.gridy = 0
            gbc.insets = JBUI.insets(2, 0, 2, 4)
            add(JBLabel("Default tab view:"), gbc)
            gbc.insets = JBUI.insets(2, 4, 2, 4)
            gbc.gridx = 1
            add(defaultViewModeCombo!!, gbc)

            gbc.gridx = 0; gbc.gridy = 1
            gbc.insets = JBUI.insets(2, 0, 2, 4)
            add(JBLabel("File panel font:"), gbc)
            gbc.insets = JBUI.insets(2, 4, 2, 4)
            gbc.gridx = 1
            add(panelFontCombo!!, gbc)
            gbc.gridx = 2
            add(JBLabel("Size:"), gbc)
            gbc.gridx = 3
            add(panelFontSizeSpinner!!, gbc)

            gbc.gridx = 0; gbc.gridy = 2
            gbc.insets = JBUI.insets(2, 0, 2, 4)
            add(JBLabel("Tab font:"), gbc)
            gbc.insets = JBUI.insets(2, 4, 2, 4)
            gbc.gridx = 1
            add(tabFontCombo!!, gbc)
            gbc.gridx = 2
            add(JBLabel("Size:"), gbc)
            gbc.gridx = 3
            add(tabFontSizeSpinner!!, gbc)
        }

        highlightingCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        commandBarCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        hideDriveSelectorCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        hideStatusBarCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        overwriteCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        sortWithDirectoriesCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT

        // Favorites editor
        val favListModel = DefaultListModel<FileManagerStateService.FavoriteEntry>()
        favoritesListModel = favListModel
        val currentProject = ProjectManager.getInstance().openProjects.firstOrNull()
        if (currentProject != null) {
            val stateService = currentProject.service<FileManagerStateService>()
            stateService.getFavoriteEntries().forEach { favListModel.addElement(FileManagerStateService.FavoriteEntry(it.path, it.color)) }
        }

        val favList = JBList(favListModel)
        favoritesList = favList
        favList.visibleRowCount = 6
        favList.cellRenderer = FavoriteListCellRenderer()

        val addButton = JButton("Add").apply {
            addActionListener {
                val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
                    title = "Add Favorite"
                    description = "Select a directory to add to favorites"
                }
                val chosen = FileChooser.chooseFile(descriptor, null, null)
                if (chosen != null) {
                    favListModel.addElement(FileManagerStateService.FavoriteEntry(chosen.path))
                }
            }
        }
        val removeButton = JButton("Remove").apply {
            addActionListener {
                val idx = favList.selectedIndex
                if (idx >= 0) favListModel.remove(idx)
            }
        }
        val moveUpButton = JButton("Up").apply {
            addActionListener {
                val idx = favList.selectedIndex
                if (idx > 0) {
                    val item = favListModel.remove(idx)
                    favListModel.add(idx - 1, item)
                    favList.selectedIndex = idx - 1
                }
            }
        }
        val moveDownButton = JButton("Down").apply {
            addActionListener {
                val idx = favList.selectedIndex
                if (idx >= 0 && idx < favListModel.size() - 1) {
                    val item = favListModel.remove(idx)
                    favListModel.add(idx + 1, item)
                    favList.selectedIndex = idx + 1
                }
            }
        }
        val colorButton = JButton("Color...").apply {
            addActionListener {
                val idx = favList.selectedIndex
                if (idx < 0) return@addActionListener
                val entry = favListModel.getElementAt(idx)
                showColorChooser(favList, entry, idx, favListModel)
            }
        }

        val favButtonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(addButton)
            add(removeButton)
            add(Box.createVerticalStrut(8))
            add(moveUpButton)
            add(moveDownButton)
            add(Box.createVerticalStrut(8))
            add(colorButton)
        }

        val favoritesPanel = JPanel(BorderLayout(8, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createTitledBorder("Favorites")
            add(JScrollPane(favList), BorderLayout.CENTER)
            add(favButtonPanel, BorderLayout.EAST)
            preferredSize = Dimension(Int.MAX_VALUE, 260)
            maximumSize = Dimension(Int.MAX_VALUE, 260)
        }

        val thumbnailCacheSizeLabel = JBLabel(formatSize(ThumbnailCache.getCacheSize()))
        val clearCacheButton = JButton("Clear").apply {
            addActionListener {
                ThumbnailCache.clearCache()
                thumbnailCacheSizeLabel.text = formatSize(ThumbnailCache.getCacheSize())
            }
        }
        val thumbnailCachePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("Thumbnail cache: "))
            add(thumbnailCacheSizeLabel)
            add(Box.createHorizontalStrut(8))
            add(clearCacheButton)
        }

        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(highlightingCheckBox)
            add(commandBarCheckBox)
            add(hideDriveSelectorCheckBox)
            add(hideStatusBarCheckBox)
            add(overwriteCheckBox)
            add(sortWithDirectoriesCheckBox)
            add(Box.createVerticalStrut(8))
            add(fontGrid)
            add(Box.createVerticalStrut(8))
            add(thumbnailCachePanel)
            add(Box.createVerticalStrut(8))
            add(favoritesPanel)
        }

        return JPanel(BorderLayout()).apply {
            add(inner, BorderLayout.NORTH)
        }
    }

    override fun isModified(): Boolean {
        val settings = TurtleCommanderSettings.getInstance().state
        val defaultLabel = "(Default)"
        return highlightingCheckBox?.isSelected != settings.enableFileNameHighlighting
            || commandBarCheckBox?.isSelected != settings.showCommandBar
            || hideDriveSelectorCheckBox?.isSelected != settings.hideDriveSelector
            || hideStatusBarCheckBox?.isSelected != settings.hideStatusBar
            || overwriteCheckBox?.isSelected != settings.alwaysOverwriteFiles
            || sortWithDirectoriesCheckBox?.isSelected != settings.sortWithDirectories
            || getSelectedFontFamily(panelFontCombo, defaultLabel) != settings.panelFontFamily
            || ((panelFontSizeSpinner?.value as? Number)?.toInt() ?: 0) != (if (settings.panelFontSize > 0) settings.panelFontSize else 13)
            || getSelectedFontFamily(tabFontCombo, defaultLabel) != settings.tabFontFamily
            || ((tabFontSizeSpinner?.value as? Number)?.toInt() ?: 0) != (if (settings.tabFontSize > 0) settings.tabFontSize else 12)
            || getSelectedViewMode() != settings.defaultViewMode
            || isFavoritesModified()
    }

    private fun isFavoritesModified(): Boolean {
        val model = favoritesListModel ?: return false
        val currentProject = ProjectManager.getInstance().openProjects.firstOrNull() ?: return false
        val stateService = currentProject.service<FileManagerStateService>()
        val saved = stateService.getFavoriteEntries()
        if (model.size() != saved.size) return true
        for (i in 0 until model.size()) {
            val m = model.getElementAt(i)
            val s = saved[i]
            if (m.path != s.path || m.color != s.color) return true
        }
        return false
    }

    override fun apply() {
        val service = TurtleCommanderSettings.getInstance()
        val settings = service.state
        val defaultLabel = "(Default)"
        settings.enableFileNameHighlighting = highlightingCheckBox?.isSelected ?: settings.enableFileNameHighlighting
        settings.showCommandBar = commandBarCheckBox?.isSelected ?: settings.showCommandBar
        settings.hideDriveSelector = hideDriveSelectorCheckBox?.isSelected ?: settings.hideDriveSelector
        settings.hideStatusBar = hideStatusBarCheckBox?.isSelected ?: settings.hideStatusBar
        settings.alwaysOverwriteFiles = overwriteCheckBox?.isSelected ?: settings.alwaysOverwriteFiles
        settings.sortWithDirectories = sortWithDirectoriesCheckBox?.isSelected ?: settings.sortWithDirectories
        settings.panelFontFamily = getSelectedFontFamily(panelFontCombo, defaultLabel)
        settings.panelFontSize = (panelFontSizeSpinner?.value as? Number)?.toInt() ?: 0
        settings.tabFontFamily = getSelectedFontFamily(tabFontCombo, defaultLabel)
        settings.tabFontSize = (tabFontSizeSpinner?.value as? Number)?.toInt() ?: 0
        settings.defaultViewMode = getSelectedViewMode()
        service.fireSettingsChanged()

        val model = favoritesListModel
        if (model != null) {
            val currentProject = ProjectManager.getInstance().openProjects.firstOrNull()
            if (currentProject != null) {
                val stateService = currentProject.service<FileManagerStateService>()
                val newEntries = (0 until model.size()).map { model.getElementAt(it) }
                stateService.setFavoriteEntries(newEntries)
            }
        }
    }

    override fun reset() {
        val settings = TurtleCommanderSettings.getInstance().state
        val defaultLabel = "(Default)"
        highlightingCheckBox?.isSelected = settings.enableFileNameHighlighting
        commandBarCheckBox?.isSelected = settings.showCommandBar
        hideDriveSelectorCheckBox?.isSelected = settings.hideDriveSelector
        hideStatusBarCheckBox?.isSelected = settings.hideStatusBar
        overwriteCheckBox?.isSelected = settings.alwaysOverwriteFiles
        sortWithDirectoriesCheckBox?.isSelected = settings.sortWithDirectories
        panelFontCombo?.selectedItem = settings.panelFontFamily.ifEmpty { defaultLabel }
        panelFontSizeSpinner?.value = if (settings.panelFontSize > 0) settings.panelFontSize else 13
        tabFontCombo?.selectedItem = settings.tabFontFamily.ifEmpty { defaultLabel }
        tabFontSizeSpinner?.value = if (settings.tabFontSize > 0) settings.tabFontSize else 12
        defaultViewModeCombo?.selectedItem = when (settings.defaultViewMode) {
            "LIST" -> "List"
            "THUMBNAIL" -> "Thumbnail"
            "TREE" -> "Tree"
            else -> "Table"
        }

        val model = favoritesListModel
        if (model != null) {
            model.clear()
            val currentProject = ProjectManager.getInstance().openProjects.firstOrNull()
            if (currentProject != null) {
                val stateService = currentProject.service<FileManagerStateService>()
                stateService.getFavoriteEntries().forEach { model.addElement(FileManagerStateService.FavoriteEntry(it.path, it.color)) }
            }
        }
    }

    override fun disposeUIResources() {
        highlightingCheckBox = null
        commandBarCheckBox = null
        hideDriveSelectorCheckBox = null
        hideStatusBarCheckBox = null
        overwriteCheckBox = null
        sortWithDirectoriesCheckBox = null
        panelFontCombo = null
        panelFontSizeSpinner = null
        tabFontCombo = null
        tabFontSizeSpinner = null
        defaultViewModeCombo = null
        favoritesListModel = null
        favoritesList = null
    }

    private fun getSelectedViewMode(): String {
        return when (defaultViewModeCombo?.selectedItem as? String) {
            "List" -> "LIST"
            "Thumbnail" -> "THUMBNAIL"
            "Tree" -> "TREE"
            else -> "TABLE"
        }
    }

    private fun getSelectedFontFamily(combo: ComboBox<String>?, defaultLabel: String): String {
        val selected = combo?.selectedItem as? String ?: return ""
        return if (selected == defaultLabel) "" else selected
    }

    private fun showColorChooser(
        favList: JBList<FileManagerStateService.FavoriteEntry>,
        entry: FileManagerStateService.FavoriteEntry,
        idx: Int,
        model: DefaultListModel<FileManagerStateService.FavoriteEntry>,
    ) {
        val presets = FAVORITE_PRESET_COLORS
        val popup = JPopupMenu()
        for ((name, hex) in presets) {
            val item = JMenuItem(name)
            if (hex.isNotEmpty()) {
                item.icon = ColorSwatchIcon(Color.decode(hex))
            }
            item.addActionListener {
                model.set(idx, FileManagerStateService.FavoriteEntry(entry.path, hex))
                favList.repaint()
            }
            popup.add(item)
        }
        popup.addSeparator()
        val customItem = JMenuItem("Custom...")
        customItem.addActionListener {
            val initial = if (entry.color.isNotBlank()) {
                try { Color.decode(entry.color) } catch (_: Exception) { null }
            } else null
            val chosen = ColorChooserService.getInstance().showDialog(
                favList, "Choose Favorite Color", initial ?: JBColor.GRAY, true, emptyList(), true
            )
            if (chosen != null) {
                val hex = String.format("#%02X%02X%02X", chosen.red, chosen.green, chosen.blue)
                model.set(idx, FileManagerStateService.FavoriteEntry(entry.path, hex))
                favList.repaint()
            }
        }
        popup.add(customItem)
        popup.show(favList, favList.width / 2, favList.indexToLocation(idx)?.y ?: 0)
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
            val icon = if (value.color.isNotBlank()) {
                try { ColorSwatchIcon(Color.decode(value.color)) } catch (_: Exception) { AllIcons.Nodes.Folder }
            } else {
                AllIcons.Nodes.Folder
            }
            delegate.icon = icon
            delegate.append(value.path)
        }
        return delegate
    }
}

private class ColorSwatchIcon(private val color: Color) : Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create()
        try {
            g2.color = color
            g2.fillRoundRect(x + 1, y + 1, iconWidth - 2, iconHeight - 2, 3, 3)
        } finally {
            g2.dispose()
        }
    }
    override fun getIconWidth(): Int = 14
    override fun getIconHeight(): Int = 14
}
