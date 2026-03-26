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
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
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
    private var calculateDirectorySizeCheckBox: JCheckBox? = null
    private var defaultViewModeCombo: ComboBox<String>? = null
    private var favoritesListModel: DefaultListModel<FileManagerStateService.FavoriteEntry>? = null
    private var favoritesList: JBList<FileManagerStateService.FavoriteEntry>? = null

    // Legacy font combos kept for backward compat during migration
    private var panelFontCombo: ComboBox<String>? = null
    private var panelFontSizeSpinner: JSpinner? = null
    private var tabFontCombo: ComboBox<String>? = null
    private var tabFontSizeSpinner: JSpinner? = null

    private val styleEditors = mutableMapOf<String, ComponentStyleEditor>()

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
        calculateDirectorySizeCheckBox = JCheckBox("Calculate directory size on selection", settings.calculateDirectorySize)

        val viewModeItems = arrayOf("Table", "List", "Thumbnail", "Tree")
        defaultViewModeCombo = ComboBox(DefaultComboBoxModel(viewModeItems)).apply {
            selectedItem = when (settings.defaultViewMode) {
                "LIST" -> "List"
                "THUMBNAIL" -> "Thumbnail"
                "TREE" -> "Tree"
                else -> "Table"
            }
        }

        val defaultLabel = "(Default)"
        val fontItems = arrayOf(defaultLabel) + fontFamilies

        // Keep legacy combos wired to panelStyle/tabStyle
        panelFontCombo = ComboBox(DefaultComboBoxModel(fontItems)).apply {
            selectedItem = effectivePanelFamily(settings).ifEmpty { defaultLabel }
        }
        panelFontSizeSpinner = JSpinner(SpinnerNumberModel(effectivePanelSize(settings), 8, 48, 1))
        tabFontCombo = ComboBox(DefaultComboBoxModel(fontItems)).apply {
            selectedItem = effectiveTabFamily(settings).ifEmpty { defaultLabel }
        }
        tabFontSizeSpinner = JSpinner(SpinnerNumberModel(effectiveTabSize(settings), 8, 48, 1))

        // Style editors for each component
        val panelEditor = ComponentStyleEditor("File panel", fontItems, settings.panelStyle, effectivePanelFamily(settings), effectivePanelSize(settings))
        val tabEditor = ComponentStyleEditor("Tab bar", fontItems, settings.tabStyle, effectiveTabFamily(settings), effectiveTabSize(settings))
        val pathBarEditor = ComponentStyleEditor("Path bar", fontItems, settings.pathBarStyle)
        val statusBarEditor = ComponentStyleEditor("Status bar", fontItems, settings.statusBarStyle)
        val commandBarEditor = ComponentStyleEditor("Command bar", fontItems, settings.commandBarStyle)
        val driveSelectorEditor = ComponentStyleEditor("Drive selector", fontItems, settings.driveSelectorStyle)
        val columnHeaderEditor = ComponentStyleEditor("Column headers", fontItems, settings.columnHeaderStyle)
        styleEditors["panel"] = panelEditor
        styleEditors["tab"] = tabEditor
        styleEditors["pathBar"] = pathBarEditor
        styleEditors["statusBar"] = statusBarEditor
        styleEditors["commandBar"] = commandBarEditor
        styleEditors["driveSelector"] = driveSelectorEditor
        styleEditors["columnHeader"] = columnHeaderEditor

        val viewModeRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("Default tab view:  "))
            add(defaultViewModeCombo!!)
        }

        val styleGrid = JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.NONE
                insets = JBUI.insets(2, 4, 2, 4)
            }

            // Header row
            gbc.gridy = 0
            gbc.gridx = 0; gbc.insets = JBUI.insets(2, 0, 2, 4)
            add(JBLabel("").apply { minimumSize = Dimension(90, 0); preferredSize = Dimension(90, preferredSize.height) }, gbc)
            gbc.insets = JBUI.insets(2, 4, 2, 4)
            gbc.gridx = 1; add(JBLabel("Font"), gbc)
            gbc.gridx = 2; add(JBLabel("Size"), gbc)
            gbc.gridx = 3; add(JBLabel("Style"), gbc)
            gbc.gridx = 4; add(JBLabel("Color"), gbc)

            val editors = listOf(tabEditor, driveSelectorEditor, pathBarEditor, columnHeaderEditor, panelEditor, statusBarEditor, commandBarEditor)
            for ((i, editor) in editors.withIndex()) {
                gbc.gridy = i + 1
                gbc.gridx = 0; gbc.insets = JBUI.insets(2, 0, 2, 4)
                val lbl = JBLabel("${editor.label}:").apply { minimumSize = Dimension(90, 0) }
                add(lbl, gbc)
                gbc.insets = JBUI.insets(2, 4, 2, 4)
                gbc.gridx = 1; add(editor.fontCombo, gbc)
                gbc.gridx = 2; add(editor.sizeSpinner, gbc)
                gbc.gridx = 3; add(editor.styleCombo, gbc)
                gbc.gridx = 4; add(editor.colorButton, gbc)
            }
        }

        val resetStylesButton = JButton("Reset to Defaults").apply {
            addActionListener {
                for (editor in styleEditors.values) {
                    editor.resetFrom(ComponentStyle())
                }
            }
        }

        val appearancePanel = JPanel(BorderLayout(8, 4)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createTitledBorder("Appearance")
            add(styleGrid, BorderLayout.CENTER)
            val bottomRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                border = JBUI.Borders.empty(4)
                add(resetStylesButton)
            }
            add(bottomRow, BorderLayout.SOUTH)
        }

        highlightingCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        commandBarCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        hideDriveSelectorCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        hideStatusBarCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        overwriteCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        sortWithDirectoriesCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        calculateDirectorySizeCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT

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

        val favButtons = listOf(addButton, removeButton, moveUpButton, moveDownButton, colorButton)
        for (btn in favButtons) {
            btn.maximumSize = Dimension(Int.MAX_VALUE, btn.preferredSize.height)
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
            add(calculateDirectorySizeCheckBox)
            add(Box.createVerticalStrut(8))
            add(viewModeRow)
            add(Box.createVerticalStrut(8))
            add(appearancePanel)
            add(Box.createVerticalStrut(8))
            add(favoritesPanel)
            add(Box.createVerticalStrut(8))
            add(thumbnailCachePanel)
        }

        return JPanel(BorderLayout()).apply {
            add(inner, BorderLayout.NORTH)
        }
    }

    override fun isModified(): Boolean {
        val settings = TurtleCommanderSettings.getInstance().state
        return highlightingCheckBox?.isSelected != settings.enableFileNameHighlighting
            || commandBarCheckBox?.isSelected != settings.showCommandBar
            || hideDriveSelectorCheckBox?.isSelected != settings.hideDriveSelector
            || hideStatusBarCheckBox?.isSelected != settings.hideStatusBar
            || overwriteCheckBox?.isSelected != settings.alwaysOverwriteFiles
            || sortWithDirectoriesCheckBox?.isSelected != settings.sortWithDirectories
            || calculateDirectorySizeCheckBox?.isSelected != settings.calculateDirectorySize
            || getSelectedViewMode() != settings.defaultViewMode
            || styleEditors["panel"]?.isModified(settings.panelStyle, effectivePanelFamily(settings), effectivePanelSize(settings)) == true
            || styleEditors["tab"]?.isModified(settings.tabStyle, effectiveTabFamily(settings), effectiveTabSize(settings)) == true
            || styleEditors["pathBar"]?.isModified(settings.pathBarStyle) == true
            || styleEditors["statusBar"]?.isModified(settings.statusBarStyle) == true
            || styleEditors["commandBar"]?.isModified(settings.commandBarStyle) == true
            || styleEditors["driveSelector"]?.isModified(settings.driveSelectorStyle) == true
            || styleEditors["columnHeader"]?.isModified(settings.columnHeaderStyle) == true
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
        settings.enableFileNameHighlighting = highlightingCheckBox?.isSelected ?: settings.enableFileNameHighlighting
        settings.showCommandBar = commandBarCheckBox?.isSelected ?: settings.showCommandBar
        settings.hideDriveSelector = hideDriveSelectorCheckBox?.isSelected ?: settings.hideDriveSelector
        settings.hideStatusBar = hideStatusBarCheckBox?.isSelected ?: settings.hideStatusBar
        settings.alwaysOverwriteFiles = overwriteCheckBox?.isSelected ?: settings.alwaysOverwriteFiles
        settings.sortWithDirectories = sortWithDirectoriesCheckBox?.isSelected ?: settings.sortWithDirectories
        settings.calculateDirectorySize = calculateDirectorySizeCheckBox?.isSelected ?: settings.calculateDirectorySize
        settings.defaultViewMode = getSelectedViewMode()

        styleEditors["panel"]?.applyTo(settings.panelStyle)
        styleEditors["tab"]?.applyTo(settings.tabStyle)
        styleEditors["pathBar"]?.applyTo(settings.pathBarStyle)
        styleEditors["statusBar"]?.applyTo(settings.statusBarStyle)
        styleEditors["commandBar"]?.applyTo(settings.commandBarStyle)
        styleEditors["driveSelector"]?.applyTo(settings.driveSelectorStyle)
        styleEditors["columnHeader"]?.applyTo(settings.columnHeaderStyle)

        // Sync legacy fields from panelStyle/tabStyle
        settings.panelFontFamily = settings.panelStyle.fontFamily
        settings.panelFontSize = settings.panelStyle.fontSize
        settings.tabFontFamily = settings.tabStyle.fontFamily
        settings.tabFontSize = settings.tabStyle.fontSize

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
        highlightingCheckBox?.isSelected = settings.enableFileNameHighlighting
        commandBarCheckBox?.isSelected = settings.showCommandBar
        hideDriveSelectorCheckBox?.isSelected = settings.hideDriveSelector
        hideStatusBarCheckBox?.isSelected = settings.hideStatusBar
        overwriteCheckBox?.isSelected = settings.alwaysOverwriteFiles
        sortWithDirectoriesCheckBox?.isSelected = settings.sortWithDirectories
        calculateDirectorySizeCheckBox?.isSelected = settings.calculateDirectorySize
        defaultViewModeCombo?.selectedItem = when (settings.defaultViewMode) {
            "LIST" -> "List"
            "THUMBNAIL" -> "Thumbnail"
            "TREE" -> "Tree"
            else -> "Table"
        }

        styleEditors["panel"]?.resetFrom(settings.panelStyle, effectivePanelFamily(settings), effectivePanelSize(settings))
        styleEditors["tab"]?.resetFrom(settings.tabStyle, effectiveTabFamily(settings), effectiveTabSize(settings))
        styleEditors["pathBar"]?.resetFrom(settings.pathBarStyle)
        styleEditors["statusBar"]?.resetFrom(settings.statusBarStyle)
        styleEditors["commandBar"]?.resetFrom(settings.commandBarStyle)
        styleEditors["driveSelector"]?.resetFrom(settings.driveSelectorStyle)
        styleEditors["columnHeader"]?.resetFrom(settings.columnHeaderStyle)

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
        calculateDirectorySizeCheckBox = null
        panelFontCombo = null
        panelFontSizeSpinner = null
        tabFontCombo = null
        tabFontSizeSpinner = null
        defaultViewModeCombo = null
        favoritesListModel = null
        favoritesList = null
        styleEditors.clear()
    }

    private fun effectivePanelFamily(settings: TurtleCommanderSettings.State): String =
        settings.panelStyle.fontFamily.ifEmpty { settings.panelFontFamily }

    private fun effectivePanelSize(settings: TurtleCommanderSettings.State): Int {
        val s = settings.panelStyle.fontSize
        return if (s > 0) s else if (settings.panelFontSize > 0) settings.panelFontSize else 13
    }

    private fun effectiveTabFamily(settings: TurtleCommanderSettings.State): String =
        settings.tabStyle.fontFamily.ifEmpty { settings.tabFontFamily }

    private fun effectiveTabSize(settings: TurtleCommanderSettings.State): Int {
        val s = settings.tabStyle.fontSize
        return if (s > 0) s else if (settings.tabFontSize > 0) settings.tabFontSize else 12
    }

    private fun getSelectedViewMode(): String {
        return when (defaultViewModeCombo?.selectedItem as? String) {
            "List" -> "LIST"
            "Thumbnail" -> "THUMBNAIL"
            "Tree" -> "TREE"
            else -> "TABLE"
        }
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

private class ComponentStyleEditor(
    val label: String,
    fontItems: Array<String>,
    style: ComponentStyle,
    legacyFamily: String = "",
    legacySize: Int = 0,
) {
    private val defaultLabel = "(Default)"
    val fontCombo = ComboBox(DefaultComboBoxModel(fontItems)).apply {
        preferredSize = Dimension(160, preferredSize.height)
        maximumSize = Dimension(160, maximumSize.height)
    }
    val sizeSpinner = JSpinner(SpinnerNumberModel(13, 8, 48, 1)).apply {
        preferredSize = Dimension(60, preferredSize.height)
    }
    val styleCombo = ComboBox(DefaultComboBoxModel(arrayOf("Plain", "Bold", "Italic", "Bold Italic")))
    val colorButton = ColorPickerButton("...")

    init {
        resetFrom(style, legacyFamily, legacySize)
    }

    fun resetFrom(style: ComponentStyle, legacyFamily: String = "", legacySize: Int = 0) {
        val family = style.fontFamily.ifEmpty { legacyFamily }
        fontCombo.selectedItem = family.ifEmpty { defaultLabel }
        val size = if (style.fontSize > 0) style.fontSize else if (legacySize > 0) legacySize else 13
        sizeSpinner.value = size
        styleCombo.selectedIndex = when (style.fontStyle) {
            Font.BOLD -> 1
            Font.ITALIC -> 2
            Font.BOLD or Font.ITALIC -> 3
            else -> 0
        }
        colorButton.setColor(style.getFontColor())
    }

    fun applyTo(style: ComponentStyle) {
        val sel = fontCombo.selectedItem as? String ?: ""
        style.fontFamily = if (sel == defaultLabel) "" else sel
        style.fontSize = (sizeSpinner.value as? Number)?.toInt() ?: 0
        style.fontStyle = when (styleCombo.selectedIndex) {
            1 -> Font.BOLD
            2 -> Font.ITALIC
            3 -> Font.BOLD or Font.ITALIC
            else -> Font.PLAIN
        }
        style.fontColor = colorButton.getColorHex()
    }

    fun isModified(style: ComponentStyle, legacyFamily: String = "", legacySize: Int = 0): Boolean {
        val tmp = ComponentStyle()
        applyTo(tmp)
        val expected = ComponentStyle().apply {
            fontFamily = style.fontFamily.ifEmpty { legacyFamily }
            fontSize = if (style.fontSize > 0) style.fontSize else if (legacySize > 0) legacySize else 13
            fontStyle = style.fontStyle
            fontColor = style.fontColor
        }
        // Compare what the UI shows vs. what would be stored
        return tmp.fontFamily != expected.fontFamily
            || tmp.fontSize != expected.fontSize
            || tmp.fontStyle != expected.fontStyle
            || tmp.fontColor != expected.fontColor
    }
}

private class ColorPickerButton(text: String) : JButton(text) {
    private var selectedColor: Color? = null

    init {
        preferredSize = Dimension(28, 28)
        isFocusable = false
        addActionListener {
            val popup = JPopupMenu()
            val presets = FAVORITE_PRESET_COLORS
            for ((name, hex) in presets) {
                val item = JMenuItem(name)
                if (hex.isNotEmpty()) {
                    item.icon = ColorSwatchIcon(Color.decode(hex))
                }
                item.addActionListener {
                    setColor(if (hex.isEmpty()) null else Color.decode(hex))
                }
                popup.add(item)
            }
            popup.addSeparator()
            val customItem = JMenuItem("Custom...")
            customItem.addActionListener {
                val chosen = ColorChooserService.getInstance().showDialog(
                    this, "Choose Color", selectedColor ?: JBColor.GRAY, true, emptyList(), true
                )
                if (chosen != null) {
                    setColor(chosen)
                }
            }
            popup.add(customItem)
            popup.show(this, 0, height)
        }
    }

    fun setColor(color: Color?) {
        selectedColor = color
        icon = if (color != null) ColorSwatchIcon(color) else null
        text = if (color != null) "" else "..."
        toolTipText = if (color != null) String.format("#%02X%02X%02X", color.red, color.green, color.blue) else "Default"
    }

    fun getColorHex(): String {
        val c = selectedColor ?: return ""
        return String.format("#%02X%02X%02X", c.red, c.green, c.blue)
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
        val g2 = (g.create() as Graphics2D).apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        }
        try {
            g2.color = color
            g2.fillRoundRect(x + 1, y + 1, iconWidth - 2, iconHeight - 2, 4, 4)
        } finally {
            g2.dispose()
        }
    }
    override fun getIconWidth(): Int = 14
    override fun getIconHeight(): Int = 14
}
