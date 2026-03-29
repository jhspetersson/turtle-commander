package io.github.jhspetersson.turtlecommander.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.service.ThumbnailCache
import io.github.jhspetersson.turtlecommander.util.formatSize
import java.awt.*
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

        val appearancePanel = createAppearancePanel(
            listOf(tabEditor, driveSelectorEditor, pathBarEditor, columnHeaderEditor, panelEditor, statusBarEditor, commandBarEditor)
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
        styleEditors["panel"]?.applyTo(state.preThemePanelStyle)
        styleEditors["tab"]?.applyTo(state.preThemeTabStyle)
        styleEditors["pathBar"]?.applyTo(state.preThemePathBarStyle)
        styleEditors["statusBar"]?.applyTo(state.preThemeStatusBarStyle)
        styleEditors["commandBar"]?.applyTo(state.preThemeCommandBarStyle)
        styleEditors["driveSelector"]?.applyTo(state.preThemeDriveSelectorStyle)
        styleEditors["columnHeader"]?.applyTo(state.preThemeColumnHeaderStyle)
    }

    private var suppressThemeAction = false

    private fun applyThemeToEditors(theme: Theme, previousTheme: Theme = Theme.DEFAULT) {
        if (suppressThemeAction) return
        val state = TurtleCommanderSettings.getInstance().state
        if (theme.name == Theme.DEFAULT.name) {
            // Restore pre-theme styles
            styleEditors["panel"]?.resetFrom(state.preThemePanelStyle)
            styleEditors["tab"]?.resetFrom(state.preThemeTabStyle)
            styleEditors["pathBar"]?.resetFrom(state.preThemePathBarStyle)
            styleEditors["statusBar"]?.resetFrom(state.preThemeStatusBarStyle)
            styleEditors["commandBar"]?.resetFrom(state.preThemeCommandBarStyle)
            styleEditors["driveSelector"]?.resetFrom(state.preThemeDriveSelectorStyle)
            styleEditors["columnHeader"]?.resetFrom(state.preThemeColumnHeaderStyle)
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
        styleEditors["driveSelector"]?.resetFrom(theme.driveSelectorStyle.toComponentStyle())
        styleEditors["columnHeader"]?.resetFrom(theme.columnHeaderStyle.toComponentStyle())
    }

    private fun createAppearancePanel(editors: List<ComponentStyleEditor>): JPanel {
        val savedThemeName = TurtleCommanderSettings.getInstance().state.themeName.ifEmpty { Theme.DEFAULT.name }
        val initialIndex = Theme.ALL_THEMES.indexOfFirst { it.name == savedThemeName }.coerceAtLeast(0)

        var previousTheme: Theme = Theme.ALL_THEMES[initialIndex]
        val combo = ComboBox(DefaultComboBoxModel(Theme.ALL_THEMES.toTypedArray())).apply {
            selectedIndex = initialIndex
        }
        combo.addActionListener {
            val theme = combo.selectedItem as? Theme ?: return@addActionListener
            val prev = previousTheme
            previousTheme = theme
            applyThemeToEditors(theme, prev)
        }
        themeCombo = combo

        val resetStylesButton = JButton("Reset to Defaults").apply {
            addActionListener {
                for (editor in styleEditors.values) {
                    editor.resetFrom(ComponentStyle())
                }
                // Clear pre-theme snapshot so Default doesn't restore old styles
                val state = TurtleCommanderSettings.getInstance().state
                state.preThemePanelStyle = ComponentStyle()
                state.preThemeTabStyle = ComponentStyle()
                state.preThemePathBarStyle = ComponentStyle()
                state.preThemeStatusBarStyle = ComponentStyle()
                state.preThemeCommandBarStyle = ComponentStyle()
                state.preThemeDriveSelectorStyle = ComponentStyle()
                state.preThemeColumnHeaderStyle = ComponentStyle()
                themeCombo?.selectedItem = Theme.DEFAULT
            }
        }

        val themeRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JBLabel("Theme:"))
            add(combo)
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
            gbc.gridx = 0; gbc.insets = JBUI.insets(2, 0, 2, 4)
            add(JBLabel("").apply { minimumSize = Dimension(90, 0); preferredSize = Dimension(90, preferredSize.height) }, gbc)
            gbc.insets = JBUI.insets(2, 4)
            gbc.gridx = 1; add(JBLabel("Font"), gbc)
            gbc.gridx = 2; add(JBLabel("Size"), gbc)
            gbc.gridx = 3; add(JBLabel("Style"), gbc)
            gbc.gridx = 4; add(JBLabel("Color"), gbc)
            gbc.gridx = 5; add(JBLabel("Bg"), gbc)

            for ((i, editor) in editors.withIndex()) {
                gbc.gridy = i + 1
                gbc.gridx = 0; gbc.insets = JBUI.insets(2, 0, 2, 4)
                val lbl = JBLabel("${editor.label}:").apply { minimumSize = Dimension(90, 0) }
                add(lbl, gbc)
                gbc.insets = JBUI.insets(2, 4)
                gbc.gridx = 1; add(editor.fontCombo, gbc)
                gbc.gridx = 2; add(editor.sizeSpinner, gbc)
                gbc.gridx = 3; add(editor.styleCombo, gbc)
                gbc.gridx = 4; add(editor.colorButton, gbc)
                gbc.gridx = 5; add(editor.bgColorButton, gbc)
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

        styleEditors["panel"]?.applyTo(settings.panelStyle)
        styleEditors["tab"]?.applyTo(settings.tabStyle)
        styleEditors["pathBar"]?.applyTo(settings.pathBarStyle)
        styleEditors["statusBar"]?.applyTo(settings.statusBarStyle)
        styleEditors["commandBar"]?.applyTo(settings.commandBarStyle)
        styleEditors["driveSelector"]?.applyTo(settings.driveSelectorStyle)
        styleEditors["columnHeader"]?.applyTo(settings.columnHeaderStyle)

        settings.themeName = getSelectedThemeName()

        columnsEditor?.applyTo(settings)

        // Sync legacy fields from panelStyle/tabStyle
        settings.panelFontFamily = settings.panelStyle.fontFamily
        settings.panelFontSize = settings.panelStyle.fontSize
        settings.tabFontFamily = settings.tabStyle.fontFamily
        settings.tabFontSize = settings.tabStyle.fontSize

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

        styleEditors["panel"]?.resetFrom(settings.panelStyle, effectivePanelFamily(settings), effectivePanelSize(settings))
        styleEditors["tab"]?.resetFrom(settings.tabStyle, effectiveTabFamily(settings), effectiveTabSize(settings))
        styleEditors["pathBar"]?.resetFrom(settings.pathBarStyle)
        styleEditors["statusBar"]?.resetFrom(settings.statusBarStyle)
        styleEditors["commandBar"]?.resetFrom(settings.commandBarStyle)
        styleEditors["driveSelector"]?.resetFrom(settings.driveSelectorStyle)
        styleEditors["columnHeader"]?.resetFrom(settings.columnHeaderStyle)
        val themeIdx = Theme.ALL_THEMES.indexOfFirst { it.name == settings.themeName.ifEmpty { Theme.DEFAULT.name } }.coerceAtLeast(0)
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

    private fun getSelectedThemeName(): String {
        val theme = themeCombo?.selectedItem as? Theme ?: return ""
        return theme.name
    }
}
