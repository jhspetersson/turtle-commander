package io.github.jhspetersson.turtlecommander.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.service.ThumbnailCache
import io.github.jhspetersson.turtlecommander.util.formatSize
import java.awt.*
import java.io.File
import javax.swing.*

class TurtleCommanderConfigurable : Configurable {

    private var highlightingCheckBox: JCheckBox? = null
    private var commandBarCheckBox: JCheckBox? = null
    private var hideDriveSelectorCheckBox: JCheckBox? = null
    private var hideStatusBarCheckBox: JCheckBox? = null
    private var overwriteCheckBox: JCheckBox? = null
    private var sortWithDirectoriesCheckBox: JCheckBox? = null
    private var calculateDirectorySizeCheckBox: JCheckBox? = null
    private var defaultViewModeCombo: ComboBox<String>? = null
    private var columnsEditor: ColumnsEditor? = null
    private var favoritesEditor: FavoritesEditor? = null

    // Legacy font combos kept for backward compat during migration
    private var panelFontCombo: ComboBox<String>? = null
    private var panelFontSizeSpinner: JSpinner? = null
    private var tabFontCombo: ComboBox<String>? = null
    private var tabFontSizeSpinner: JSpinner? = null

    private val styleEditors = mutableMapOf<String, ComponentStyleEditor>()
    private var themeCombo: ComboBox<Theme>? = null
    private var suppressThemeAction = false

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
        val panelEditor = ComponentStyleEditor("File panel", fontItems, settings.styles.panelStyle, effectivePanelFamily(settings), effectivePanelSize(settings))
        val tabEditor = ComponentStyleEditor("Tab bar", fontItems, settings.styles.tabStyle, effectiveTabFamily(settings), effectiveTabSize(settings))
        val pathBarEditor = ComponentStyleEditor("Path bar", fontItems, settings.styles.pathBarStyle)
        val statusBarEditor = ComponentStyleEditor("Status bar", fontItems, settings.styles.statusBarStyle)
        val commandBarEditor = ComponentStyleEditor("Command bar", fontItems, settings.styles.commandBarStyle)
        val commandButtonEditor = ComponentStyleEditor("Cmd buttons", fontItems, settings.styles.commandButtonStyle)
        val driveSelectorEditor = ComponentStyleEditor("Drive selector", fontItems, settings.styles.driveSelectorStyle)
        val columnHeaderEditor = ComponentStyleEditor("Column headers", fontItems, settings.styles.columnHeaderStyle)
        styleEditors["panel"] = panelEditor
        styleEditors["tab"] = tabEditor
        styleEditors["pathBar"] = pathBarEditor
        styleEditors["statusBar"] = statusBarEditor
        styleEditors["commandBar"] = commandBarEditor
        styleEditors["commandButton"] = commandButtonEditor
        styleEditors["driveSelector"] = driveSelectorEditor
        styleEditors["columnHeader"] = columnHeaderEditor

        val viewModeRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("Default tab view:  "))
            add(defaultViewModeCombo!!)
        }

        val appearancePanel = createAppearancePanel(
            listOf(tabEditor, driveSelectorEditor, pathBarEditor, columnHeaderEditor, panelEditor, statusBarEditor, commandBarEditor, commandButtonEditor)
        )

        highlightingCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        commandBarCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        hideDriveSelectorCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        hideStatusBarCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        overwriteCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        sortWithDirectoriesCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        calculateDirectorySizeCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT

        // Columns editor
        val colEditor = ColumnsEditor(fontItems, TurtleCommanderSettings.getInstance().getEffectiveColumns())
        columnsEditor = colEditor

        // Favorites editor
        val favEditor = FavoritesEditor()
        favoritesEditor = favEditor

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
            add(colEditor.panel)
            add(Box.createVerticalStrut(8))
            add(favEditor.panel)
            add(Box.createVerticalStrut(8))
            add(thumbnailCachePanel)
        }

        return JPanel(BorderLayout()).apply {
            add(inner, BorderLayout.NORTH)
        }
    }

    private fun snapshotEditorsToPreTheme(state: TurtleCommanderSettings.State) {
        styleEditors["panel"]?.applyTo(state.preThemeStyles.panelStyle)
        styleEditors["tab"]?.applyTo(state.preThemeStyles.tabStyle)
        styleEditors["pathBar"]?.applyTo(state.preThemeStyles.pathBarStyle)
        styleEditors["statusBar"]?.applyTo(state.preThemeStyles.statusBarStyle)
        styleEditors["commandBar"]?.applyTo(state.preThemeStyles.commandBarStyle)
        styleEditors["commandButton"]?.applyTo(state.preThemeStyles.commandButtonStyle)
        styleEditors["driveSelector"]?.applyTo(state.preThemeStyles.driveSelectorStyle)
        styleEditors["columnHeader"]?.applyTo(state.preThemeStyles.columnHeaderStyle)
    }

    private fun applyThemeToEditors(theme: Theme, previousTheme: Theme = Theme.DEFAULT) {
        if (suppressThemeAction) return
        val state = TurtleCommanderSettings.getInstance().state
        if (theme.name == Theme.DEFAULT.name) {
            // Restore pre-theme styles
            styleEditors["panel"]?.resetFrom(state.preThemeStyles.panelStyle)
            styleEditors["tab"]?.resetFrom(state.preThemeStyles.tabStyle)
            styleEditors["pathBar"]?.resetFrom(state.preThemeStyles.pathBarStyle)
            styleEditors["statusBar"]?.resetFrom(state.preThemeStyles.statusBarStyle)
            styleEditors["commandBar"]?.resetFrom(state.preThemeStyles.commandBarStyle)
            styleEditors["commandButton"]?.resetFrom(state.preThemeStyles.commandButtonStyle)
            styleEditors["driveSelector"]?.resetFrom(state.preThemeStyles.driveSelectorStyle)
            styleEditors["columnHeader"]?.resetFrom(state.preThemeStyles.columnHeaderStyle)
            return
        }
        // Snapshot current editor state only when switching away from Default
        if (previousTheme.name == Theme.DEFAULT.name) {
            snapshotEditorsToPreTheme(state)
        }
        styleEditors["panel"]?.resetFrom(theme.panelStyle.toComponentStyle())
        styleEditors["tab"]?.resetFrom(theme.tabStyle.toComponentStyle())
        styleEditors["pathBar"]?.resetFrom(theme.pathBarStyle.toComponentStyle())
        styleEditors["statusBar"]?.resetFrom(theme.statusBarStyle.toComponentStyle())
        styleEditors["commandBar"]?.resetFrom(theme.commandBarStyle.toComponentStyle())
        styleEditors["commandButton"]?.resetFrom(theme.commandButtonStyle.toComponentStyle())
        styleEditors["driveSelector"]?.resetFrom(theme.driveSelectorStyle.toComponentStyle())
        styleEditors["columnHeader"]?.resetFrom(theme.columnHeaderStyle.toComponentStyle())
    }

    private fun buildThemeFromEditors(name: String): Theme {
        val styles = mapOf(
            "panel" to ComponentStyle(),
            "tab" to ComponentStyle(),
            "pathBar" to ComponentStyle(),
            "statusBar" to ComponentStyle(),
            "commandBar" to ComponentStyle(),
            "commandButton" to ComponentStyle(),
            "driveSelector" to ComponentStyle(),
            "columnHeader" to ComponentStyle(),
        )
        for ((key, style) in styles) {
            styleEditors[key]?.applyTo(style)
        }
        return Theme(
            name = name,
            panelStyle = ThemeStyle.fromComponentStyle(styles["panel"]!!),
            tabStyle = ThemeStyle.fromComponentStyle(styles["tab"]!!),
            pathBarStyle = ThemeStyle.fromComponentStyle(styles["pathBar"]!!),
            statusBarStyle = ThemeStyle.fromComponentStyle(styles["statusBar"]!!),
            commandBarStyle = ThemeStyle.fromComponentStyle(styles["commandBar"]!!),
            commandButtonStyle = ThemeStyle.fromComponentStyle(styles["commandButton"]!!),
            driveSelectorStyle = ThemeStyle.fromComponentStyle(styles["driveSelector"]!!),
            columnHeaderStyle = ThemeStyle.fromComponentStyle(styles["columnHeader"]!!),
        )
    }

    private fun rebuildThemeCombo() {
        val combo = themeCombo ?: return
        val state = TurtleCommanderSettings.getInstance().state
        val currentName = (combo.selectedItem as? Theme)?.name ?: Theme.DEFAULT.name
        val allThemes = ThemeManager.getAllThemes(state)
        suppressThemeAction = true
        combo.model = DefaultComboBoxModel(allThemes.toTypedArray())
        val idx = allThemes.indexOfFirst { it.name == currentName }.coerceAtLeast(0)
        combo.selectedIndex = idx
        suppressThemeAction = false
    }

    private fun createAppearancePanel(editors: List<ComponentStyleEditor>): JPanel {
        val state = TurtleCommanderSettings.getInstance().state
        val allThemes = ThemeManager.getAllThemes(state)
        val savedThemeName = state.themeName.ifEmpty { Theme.DEFAULT.name }
        val initialIndex = allThemes.indexOfFirst { it.name == savedThemeName }.coerceAtLeast(0)

        var previousTheme: Theme = allThemes[initialIndex]
        val combo = ComboBox(DefaultComboBoxModel(allThemes.toTypedArray())).apply {
            selectedIndex = initialIndex
        }
        combo.addActionListener {
            val theme = combo.selectedItem as? Theme ?: return@addActionListener
            val prev = previousTheme
            previousTheme = theme
            applyThemeToEditors(theme, prev)
        }
        themeCombo = combo

        val saveButton = JButton("Save").apply {
            toolTipText = "Save current styles as a new theme"
            addActionListener { saveCurrentTheme() }
        }
        val renameButton = JButton("Rename").apply {
            toolTipText = "Rename the selected custom theme"
            addActionListener { renameSelectedTheme() }
        }
        val deleteButton = JButton("Delete").apply {
            toolTipText = "Delete the selected custom theme"
            addActionListener { deleteSelectedTheme() }
        }
        val iconSize = AllIcons.ToolbarDecorator.Export.iconWidth + JBUI.scale(14)
        val exportButton = JButton(AllIcons.ToolbarDecorator.Export).apply {
            toolTipText = "Export the selected theme to a file"
            preferredSize = Dimension(iconSize, preferredSize.height)
            addActionListener { exportSelectedTheme() }
        }
        val importButton = JButton(AllIcons.ToolbarDecorator.Import).apply {
            toolTipText = "Import a theme from a file"
            preferredSize = Dimension(iconSize, preferredSize.height)
            addActionListener { importThemeFromFile() }
        }
        val resetStylesButton = JButton("Reset to Defaults").apply {
            addActionListener {
                for (editor in styleEditors.values) {
                    editor.resetFrom(ComponentStyle())
                }
                val s = TurtleCommanderSettings.getInstance().state
                s.preThemeStyles.reset()
                // Re-seed initial themes if all were deleted
                if (s.themes.isEmpty()) {
                    for (theme in Theme.INITIAL_THEMES) {
                        s.themes.add(SavedTheme.fromTheme(theme))
                    }
                }
                suppressThemeAction = true
                themeCombo?.selectedItem = Theme.DEFAULT
                suppressThemeAction = false
            }
        }

        val themeRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JBLabel("Theme:"))
            add(combo)
            add(saveButton)
            add(renameButton)
            add(deleteButton)
            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(2, 24) })
            add(exportButton)
            add(importButton)
            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(2, 24) })
            add(resetStylesButton)
        }

        val styleGrid = JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.NONE
                insets = JBUI.insets(2, 4)
            }

            // Header row
            gbc.gridy = 0
            gbc.gridx = 0; gbc.insets = JBUI.insets(2, 8, 2, 4)
            add(JBLabel(""), gbc)
            gbc.insets = JBUI.insets(2, 4)
            gbc.gridx = 1; add(JBLabel("Font"), gbc)
            gbc.gridx = 2; add(JBLabel("Size"), gbc)
            gbc.gridx = 3; add(JBLabel("Style"), gbc)
            gbc.gridx = 4; add(JBLabel("Color"), gbc)
            gbc.gridx = 5; add(JBLabel("Bg"), gbc)
            gbc.gridx = 6; add(JBLabel("Sel"), gbc)
            gbc.gridx = 7; add(JBLabel("Active"), gbc)
            // Filler to push everything left
            gbc.gridx = 8; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            add(JPanel().apply { isOpaque = false }, gbc)
            gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE

            val panelEditorLabel = styleEditors["panel"]?.label
            val commandBarEditorLabel = styleEditors["commandBar"]?.label
            for ((i, editor) in editors.withIndex()) {
                gbc.gridy = i + 1
                gbc.gridx = 0; gbc.insets = JBUI.insets(2, 8, 2, 4)
                val lbl = JBLabel("${editor.label}:")
                add(lbl, gbc)
                gbc.insets = JBUI.insets(2, 4)
                val bgOnly = editor.label == commandBarEditorLabel
                if (!bgOnly) {
                    gbc.gridx = 1; add(editor.fontCombo, gbc)
                    gbc.gridx = 2; add(editor.sizeSpinner, gbc)
                    gbc.gridx = 3; add(editor.styleCombo, gbc)
                    gbc.gridx = 4; add(editor.colorButton, gbc)
                }
                gbc.gridx = 5; add(editor.bgColorButton, gbc)
                if (editor.label == panelEditorLabel) {
                    gbc.gridx = 6; add(editor.selectedColorButton, gbc)
                    gbc.gridx = 7; add(editor.activeSelectedColorButton, gbc)
                }
            }
        }

        return JPanel(BorderLayout(8, 4)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createTitledBorder("Appearance")
            val topPanel = JPanel(BorderLayout()).apply {
                add(themeRow, BorderLayout.NORTH)
                add(styleGrid, BorderLayout.CENTER)
            }
            add(topPanel, BorderLayout.CENTER)
        }
    }

    // --- Theme actions ---

    private fun saveCurrentTheme() {
        val name = Messages.showInputDialog(
            "Enter theme name:", "Save Theme", null
        ) ?: return
        if (name.isBlank()) {
            Messages.showWarningDialog("Theme name cannot be empty.", "Save Theme")
            return
        }
        val state = TurtleCommanderSettings.getInstance().state
        val theme = buildThemeFromEditors(name)
        ThemeManager.saveTheme(state, theme)
        rebuildThemeCombo()
        selectThemeByName(name)
    }

    private fun renameSelectedTheme() {
        val current = themeCombo?.selectedItem as? Theme ?: return
        if (current.name == Theme.DEFAULT.name) {
            Messages.showWarningDialog("The Default theme cannot be renamed.", "Rename Theme")
            return
        }
        val newName = Messages.showInputDialog(
            "Enter new name for \"${current.name}\":", "Rename Theme", null, current.name, null
        ) ?: return
        if (newName.isBlank()) {
            Messages.showWarningDialog("Theme name cannot be empty.", "Rename Theme")
            return
        }
        val state = TurtleCommanderSettings.getInstance().state
        when (ThemeManager.renameTheme(state, current.name, newName)) {
            ThemeManager.RenameResult.Success -> {
                rebuildThemeCombo()
                selectThemeByName(newName)
            }
            ThemeManager.RenameResult.Conflict -> {
                Messages.showWarningDialog("A theme named \"$newName\" already exists.", "Rename Theme")
            }
            ThemeManager.RenameResult.NotFound -> {
                Messages.showWarningDialog("Theme not found.", "Rename Theme")
            }
            ThemeManager.RenameResult.InvalidName -> {
                Messages.showWarningDialog("Theme name cannot be empty.", "Rename Theme")
            }
        }
    }

    private fun deleteSelectedTheme() {
        val current = themeCombo?.selectedItem as? Theme ?: return
        if (current.name == Theme.DEFAULT.name) {
            Messages.showWarningDialog("The Default theme cannot be deleted.", "Delete Theme")
            return
        }
        val confirm = Messages.showYesNoDialog(
            "Delete theme \"${current.name}\"?", "Delete Theme", null
        )
        if (confirm != Messages.YES) return
        val state = TurtleCommanderSettings.getInstance().state
        ThemeManager.deleteTheme(state, current.name)
        rebuildThemeCombo()
    }

    private fun exportSelectedTheme() {
        val current = themeCombo?.selectedItem as? Theme ?: return
        val descriptor = FileSaverDescriptor("Export Theme", "Save theme to a file", "tctheme")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, null as com.intellij.openapi.project.Project?)
        val fileName = "${current.name.replace(" ", "_")}.tctheme"
        val wrapper = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, fileName) ?: return
        val file = wrapper.file
        file.writeText(ThemeManager.exportTheme(buildThemeFromEditors(current.name)))
    }

    private fun importThemeFromFile() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withExtensionFilter("Turtle Commander Theme", "tctheme")
            .withTitle("Import Theme")
            .withDescription("Select a Turtle Commander theme file")
        val chosen = FileChooser.chooseFile(descriptor, null, null) ?: return
        val text = File(chosen.path).readText()
        val theme = ThemeManager.importTheme(text)
        if (theme == null) {
            Messages.showWarningDialog("Invalid theme file.", "Import Theme")
            return
        }
        val state = TurtleCommanderSettings.getInstance().state
        val allNames = ThemeManager.getAllThemes(state).map { it.name }.toSet()
        val overwrite = if (theme.name in allNames) {
            val choice = Messages.showYesNoCancelDialog(
                "A theme named \"${theme.name}\" already exists. Overwrite it?",
                "Import Theme", "Overwrite", "Rename", "Cancel", null
            )
            when (choice) {
                Messages.YES -> true
                Messages.NO -> false
                else -> return
            }
        } else {
            false
        }
        val (result, effectiveName) = ThemeManager.importThemeToState(state, theme, overwrite)
        val importedTheme = if (result == ThemeManager.ImportResult.Renamed) theme.copy(name = effectiveName) else theme
        rebuildThemeCombo()
        selectThemeByName(effectiveName)
        applyThemeToEditors(importedTheme)
        if (result == ThemeManager.ImportResult.Renamed) {
            Messages.showInfoMessage("Theme imported as \"$effectiveName\" to avoid name conflict.", "Import Theme")
        }
    }

    private fun selectThemeByName(name: String) {
        val combo = themeCombo ?: return
        for (i in 0 until combo.itemCount) {
            if (combo.getItemAt(i).name == name) {
                suppressThemeAction = true
                combo.selectedIndex = i
                suppressThemeAction = false
                return
            }
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
            || styleEditors["panel"]?.isModified(settings.styles.panelStyle, effectivePanelFamily(settings), effectivePanelSize(settings)) == true
            || styleEditors["tab"]?.isModified(settings.styles.tabStyle, effectiveTabFamily(settings), effectiveTabSize(settings)) == true
            || styleEditors["pathBar"]?.isModified(settings.styles.pathBarStyle) == true
            || styleEditors["statusBar"]?.isModified(settings.styles.statusBarStyle) == true
            || styleEditors["commandBar"]?.isModified(settings.styles.commandBarStyle) == true
            || styleEditors["commandButton"]?.isModified(settings.styles.commandButtonStyle) == true
            || styleEditors["driveSelector"]?.isModified(settings.styles.driveSelectorStyle) == true
            || styleEditors["columnHeader"]?.isModified(settings.styles.columnHeaderStyle) == true
            || getSelectedThemeName() != settings.themeName.ifEmpty { Theme.DEFAULT.name }
            || columnsEditor?.isModified(TurtleCommanderSettings.getInstance().getEffectiveColumns()) == true
            || favoritesEditor?.isModified() == true
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

        styleEditors["panel"]?.applyTo(settings.styles.panelStyle)
        styleEditors["tab"]?.applyTo(settings.styles.tabStyle)
        styleEditors["pathBar"]?.applyTo(settings.styles.pathBarStyle)
        styleEditors["statusBar"]?.applyTo(settings.styles.statusBarStyle)
        styleEditors["commandBar"]?.applyTo(settings.styles.commandBarStyle)
        styleEditors["commandButton"]?.applyTo(settings.styles.commandButtonStyle)
        styleEditors["driveSelector"]?.applyTo(settings.styles.driveSelectorStyle)
        styleEditors["columnHeader"]?.applyTo(settings.styles.columnHeaderStyle)

        settings.themeName = getSelectedThemeName()

        columnsEditor?.applyTo(settings)

        // Sync legacy fields from panelStyle/tabStyle
        settings.panelFontFamily = settings.styles.panelStyle.fontFamily
        settings.panelFontSize = settings.styles.panelStyle.fontSize
        settings.tabFontFamily = settings.styles.tabStyle.fontFamily
        settings.tabFontSize = settings.styles.tabStyle.fontSize

        service.fireSettingsChanged()

        favoritesEditor?.apply()
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

        styleEditors["panel"]?.resetFrom(settings.styles.panelStyle, effectivePanelFamily(settings), effectivePanelSize(settings))
        styleEditors["tab"]?.resetFrom(settings.styles.tabStyle, effectiveTabFamily(settings), effectiveTabSize(settings))
        styleEditors["pathBar"]?.resetFrom(settings.styles.pathBarStyle)
        styleEditors["statusBar"]?.resetFrom(settings.styles.statusBarStyle)
        styleEditors["commandBar"]?.resetFrom(settings.styles.commandBarStyle)
        styleEditors["commandButton"]?.resetFrom(settings.styles.commandButtonStyle)
        styleEditors["driveSelector"]?.resetFrom(settings.styles.driveSelectorStyle)
        styleEditors["columnHeader"]?.resetFrom(settings.styles.columnHeaderStyle)

        rebuildThemeCombo()
        val allThemes = ThemeManager.getAllThemes(settings)
        val themeIdx = allThemes.indexOfFirst { it.name == settings.themeName.ifEmpty { Theme.DEFAULT.name } }.coerceAtLeast(0)
        suppressThemeAction = true
        themeCombo?.selectedIndex = themeIdx
        suppressThemeAction = false

        columnsEditor?.resetFrom(TurtleCommanderSettings.getInstance().getEffectiveColumns())
        favoritesEditor?.reset()
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
        columnsEditor = null
        favoritesEditor = null
        themeCombo = null
        suppressThemeAction = false
        styleEditors.clear()
    }

    private fun effectivePanelFamily(settings: TurtleCommanderSettings.State): String =
        settings.styles.panelStyle.fontFamily.ifEmpty { settings.panelFontFamily }

    private fun effectivePanelSize(settings: TurtleCommanderSettings.State): Int {
        val s = settings.styles.panelStyle.fontSize
        return if (s > 0) s else if (settings.panelFontSize > 0) settings.panelFontSize else 13
    }

    private fun effectiveTabFamily(settings: TurtleCommanderSettings.State): String =
        settings.styles.tabStyle.fontFamily.ifEmpty { settings.tabFontFamily }

    private fun effectiveTabSize(settings: TurtleCommanderSettings.State): Int {
        val s = settings.styles.tabStyle.fontSize
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

    private fun getSelectedThemeName(): String {
        val theme = themeCombo?.selectedItem as? Theme ?: return ""
        return theme.name
    }
}
