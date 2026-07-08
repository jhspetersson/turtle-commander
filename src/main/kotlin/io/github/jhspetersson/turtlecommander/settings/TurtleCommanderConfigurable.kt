package io.github.jhspetersson.turtlecommander.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.dialog.InputDialog
import io.github.jhspetersson.turtlecommander.dialog.OverwritePolicyRenderer
import io.github.jhspetersson.turtlecommander.service.OverwritePolicy
import io.github.jhspetersson.turtlecommander.service.ThumbnailCache
import io.github.jhspetersson.turtlecommander.util.formatSize
import java.awt.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class TurtleCommanderConfigurable : Configurable {

    private var highlightingCheckBox: JCheckBox? = null
    private var commandBarCheckBox: JCheckBox? = null
    private var saveCommandHistoryCheckBox: JCheckBox? = null
    private var hideDriveSelectorCheckBox: JCheckBox? = null
    private var hideStatusBarCheckBox: JCheckBox? = null
    private var overwritePolicyCombo: ComboBox<OverwritePolicy>? = null
    private var deleteToRecycleBinCheckBox: JCheckBox? = null
    private var sortWithDirectoriesCheckBox: JCheckBox? = null
    private var naturalNameSortCheckBox: JCheckBox? = null
    private var calculateDirectorySizeCheckBox: JCheckBox? = null
    private var openDirectoriesWithSingleClickCheckBox: JCheckBox? = null
    private var defaultViewModeCombo: ComboBox<String>? = null
    private var panelLayoutCombo: ComboBox<String>? = null
    private var thumbnailSizeCombo: ComboBox<ThumbnailSize>? = null
    private var fileSizeFormatCombo: ComboBox<FileSizeFormat>? = null
    private var dateTimeFormatField: JBTextField? = null
    private var exportDateTimeFormatField: JBTextField? = null
    private var dateTimePreviewLabel: JBLabel? = null
    private var columnsEditor: ColumnsEditor? = null
    private var favoritesEditor: FavoritesEditor? = null
    private var colorRulesEditor: ColorRulesEditor? = null

    private val styleEditors = mutableMapOf<String, ComponentStyleEditor>()
    private var themeCombo: ComboBox<Theme>? = null
    private var suppressThemeAction = false

    override fun getDisplayName(): String = "Turtle Commander"

    override fun createComponent(): JComponent {
        val settings = TurtleCommanderSettings.getInstance().state

        highlightingCheckBox = JCheckBox("Enable file colors and icons", settings.enableFileNameHighlighting)
        commandBarCheckBox = JCheckBox("Show command bar (F5 Copy, F6 Move, etc.)", settings.showCommandBar)
        saveCommandHistoryCheckBox = JCheckBox("Save command execution history", settings.saveCommandHistory)
        hideDriveSelectorCheckBox = JCheckBox("Hide drive selector", settings.hideDriveSelector)
        hideStatusBarCheckBox = JCheckBox("Hide status bar", settings.hideStatusBar)
        overwritePolicyCombo = ComboBox(OverwritePolicy.entries.toTypedArray()).apply {
            renderer = OverwritePolicyRenderer
            selectedItem = TurtleCommanderSettings.getInstance().getDefaultOverwritePolicy()
        }
        deleteToRecycleBinCheckBox = JCheckBox("Delete to Recycle Bin (Shift+Delete bypasses)", settings.deleteToRecycleBin)
        sortWithDirectoriesCheckBox = JCheckBox("Sort directories together with files", settings.sortWithDirectories)
        naturalNameSortCheckBox = JCheckBox("Natural sort order for file names (file2 before file10)", settings.naturalNameSort)
        calculateDirectorySizeCheckBox = JCheckBox("Calculate directory size on selection", settings.calculateDirectorySize)
        openDirectoriesWithSingleClickCheckBox = JCheckBox("Open directories with a single click", settings.openDirectoriesWithSingleClick)

        val viewModeItems = arrayOf("Table", "List", "Thumbnail", "Tree")
        defaultViewModeCombo = ComboBox(DefaultComboBoxModel(viewModeItems)).apply {
            selectedItem = when (settings.defaultViewMode) {
                "LIST" -> "List"
                "THUMBNAIL" -> "Thumbnail"
                "TREE" -> "Tree"
                else -> "Table"
            }
        }

        val layoutItems = arrayOf("Horizontal Dual-panel", "Vertical Dual-panel", "Single-panel")
        panelLayoutCombo = ComboBox(DefaultComboBoxModel(layoutItems)).apply {
            selectedItem = layoutLabelFor(settings.panelLayout)
        }

        thumbnailSizeCombo = ComboBox(DefaultComboBoxModel(ThumbnailSize.entries.toTypedArray())).apply {
            selectedItem = ThumbnailSize.fromName(settings.thumbnailSize)
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean,
                ): Component {
                    val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is ThumbnailSize) text = value.label
                    return c
                }
            }
        }

        fileSizeFormatCombo = ComboBox(DefaultComboBoxModel(FileSizeFormat.entries.toTypedArray())).apply {
            selectedItem = FileSizeFormat.fromName(settings.fileSizeFormat)
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean,
                ): Component {
                    val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is FileSizeFormat) text = value.label
                    return c
                }
            }
        }

        dateTimePreviewLabel = JBLabel("")
        dateTimeFormatField = JBTextField(settings.dateTimeFormat.ifBlank { DEFAULT_DATE_TIME_FORMAT }, 20).apply {
            toolTipText = "Java DateTimeFormatter pattern, e.g. yyyy-MM-dd HH:mm, dd/MM/yyyy, MMM d yyyy h:mm a"
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = updateDateTimePreview()
                override fun removeUpdate(e: DocumentEvent?) = updateDateTimePreview()
                override fun changedUpdate(e: DocumentEvent?) = updateDateTimePreview()
            })
        }
        updateDateTimePreview()

        exportDateTimeFormatField = JBTextField(settings.exportDateTimeFormat, 20).apply {
            toolTipText = "Date/time format for CSV/JSON export. Leave blank for ISO-8601 UTC " +
                "(e.g. 2026-06-16T12:34:56Z); otherwise a Java DateTimeFormatter pattern in local time."
        }

        // Style editors for each component
        val panelEditor = ComponentStyleEditor("File panel", settings.styles.panelStyle, effectivePanelFamily(settings), effectivePanelSize(settings))
        val tabEditor = ComponentStyleEditor("Tab bar", settings.styles.tabStyle, effectiveTabFamily(settings), effectiveTabSize(settings))
        val pathBarEditor = ComponentStyleEditor("Path bar", settings.styles.pathBarStyle)
        val statusBarEditor = ComponentStyleEditor("Status bar", settings.styles.statusBarStyle)
        val commandBarEditor = ComponentStyleEditor("Command bar", settings.styles.commandBarStyle)
        val commandButtonEditor = ComponentStyleEditor("Cmd buttons", settings.styles.commandButtonStyle)
        val driveSelectorEditor = ComponentStyleEditor("Drive selector", settings.styles.driveSelectorStyle)
        val columnHeaderEditor = ComponentStyleEditor("Column headers", settings.styles.columnHeaderStyle)
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

        val layoutRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("Layout:  "))
            add(panelLayoutCombo!!)
        }

        val thumbnailCache = ThumbnailCache.getInstance()
        val thumbnailCacheSizeLabel = JBLabel(formatSize(thumbnailCache.getCacheSize()))
        val clearCacheButton = JButton("Clear").apply {
            addActionListener {
                thumbnailCache.clearCache()
                thumbnailCacheSizeLabel.text = formatSize(thumbnailCache.getCacheSize())
            }
        }
        val thumbnailRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("Thumbnail size:  "))
            add(thumbnailSizeCombo!!)
            add(Box.createHorizontalStrut(16))
            add(JBLabel("Cache: "))
            add(thumbnailCacheSizeLabel)
            add(Box.createHorizontalStrut(8))
            add(clearCacheButton)
        }

        val fileSizeFormatRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("File size format:  "))
            add(fileSizeFormatCombo!!)
        }

        val dateTimeFormatRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("Date/time format:  "))
            add(dateTimeFormatField!!)
            add(Box.createHorizontalStrut(8))
            add(dateTimePreviewLabel!!)
        }

        val exportDateTimeFormatRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("Export date/time format:  "))
            add(exportDateTimeFormatField!!)
            add(Box.createHorizontalStrut(8))
            add(JBLabel("(blank = ISO-8601 UTC)"))
        }

        val appearancePanel = createAppearancePanel(
            listOf(tabEditor, driveSelectorEditor, pathBarEditor, columnHeaderEditor, panelEditor, statusBarEditor, commandBarEditor, commandButtonEditor)
        )

        val clearCommandHistoryButton = JButton("Clear").apply {
            toolTipText = "Remove all saved command-line history"
            addActionListener {
                for (project in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
                    project.service<io.github.jhspetersson.turtlecommander.service.FileManagerStateService>()
                        .clearCommandHistory()
                }
            }
        }
        val commandHistoryRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(saveCommandHistoryCheckBox!!)
            add(Box.createHorizontalStrut(8))
            add(clearCommandHistoryButton)
        }

        highlightingCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        commandBarCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        hideDriveSelectorCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        hideStatusBarCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        deleteToRecycleBinCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        sortWithDirectoriesCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        naturalNameSortCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        calculateDirectorySizeCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT
        openDirectoriesWithSingleClickCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT

        // Columns editor
        val colEditor = ColumnsEditor(TurtleCommanderSettings.getInstance().getEffectiveColumns())
        columnsEditor = colEditor

        // Favorites editor
        val favEditor = FavoritesEditor()
        favoritesEditor = favEditor

        // Color rules editor
        val rulesEditor = ColorRulesEditor(null)
        rulesEditor.resetFrom(settings)
        colorRulesEditor = rulesEditor

        val overwritePolicyRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("Default copy/move policy:  "))
            add(overwritePolicyCombo!!)
        }

        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(highlightingCheckBox)
            add(commandBarCheckBox)
            add(commandHistoryRow)
            add(hideDriveSelectorCheckBox)
            add(hideStatusBarCheckBox)
            add(deleteToRecycleBinCheckBox)
            add(sortWithDirectoriesCheckBox)
            add(naturalNameSortCheckBox)
            add(calculateDirectorySizeCheckBox)
            add(openDirectoriesWithSingleClickCheckBox)
            add(Box.createVerticalStrut(8))
            add(overwritePolicyRow)
            add(viewModeRow)
            add(layoutRow)
            add(thumbnailRow)
            add(fileSizeFormatRow)
            add(dateTimeFormatRow)
            add(exportDateTimeFormatRow)
            add(Box.createVerticalStrut(8))
            add(appearancePanel)
            add(Box.createVerticalStrut(8))
            add(colEditor.panel)
            add(Box.createVerticalStrut(8))
            add(rulesEditor.panel)
            add(Box.createVerticalStrut(8))
            add(favEditor.panel)
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

        val iconSize = AllIcons.ToolbarDecorator.Export.iconWidth + JBUI.scale(14)
        val saveButton = JButton(AllIcons.Actions.MenuSaveall).apply {
            toolTipText = "Save current styles as a new theme"
            preferredSize = Dimension(iconSize, preferredSize.height)
            addActionListener { saveCurrentTheme() }
        }
        val renameButton = JButton(AllIcons.Actions.Edit).apply {
            toolTipText = "Rename the selected custom theme"
            preferredSize = Dimension(iconSize, preferredSize.height)
            addActionListener { renameSelectedTheme() }
        }
        val deleteButton = JButton(AllIcons.Actions.GC).apply {
            toolTipText = "Delete the selected custom theme"
            preferredSize = Dimension(iconSize, preferredSize.height)
            addActionListener { deleteSelectedTheme() }
        }
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

        val panelEditorLabel = styleEditors["panel"]?.label
        val commandBarEditorLabel = styleEditors["commandBar"]?.label

        val componentListModel = DefaultListModel<ComponentStyleEditor>()
        editors.forEach { componentListModel.addElement(it) }
        val componentList = JBList(componentListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : ListCellRenderer<ComponentStyleEditor> {
                private val label = JBLabel().apply { border = JBUI.Borders.empty(2, 6) }
                override fun getListCellRendererComponent(
                    list: JList<out ComponentStyleEditor>,
                    value: ComponentStyleEditor?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component {
                    label.text = value?.label ?: ""
                    label.background = if (isSelected) list.selectionBackground else list.background
                    label.foreground = if (isSelected) list.selectionForeground else list.foreground
                    label.isOpaque = true
                    label.font = list.font
                    return label
                }
            }
        }

        val detailPanel = JPanel(BorderLayout())

        fun renderDetail() {
            detailPanel.removeAll()
            val editor = componentList.selectedValue
            if (editor != null) {
                val grid = JPanel(GridBagLayout())
                val gbc = GridBagConstraints().apply {
                    anchor = GridBagConstraints.WEST
                    fill = GridBagConstraints.NONE
                    insets = JBUI.insets(2, 4)
                }
                // Spacer row to keep column widths consistent across selections
                gbc.insets = JBUI.insets(0, 4)
                gbc.gridy = 0
                gbc.gridx = 0; grid.add(Box.createHorizontalStrut(JBUI.scale(110)), gbc)
                gbc.gridx = 1; grid.add(Box.createHorizontalStrut(JBUI.scale(170)), gbc)
                gbc.insets = JBUI.insets(2, 4)

                var row = 1
                val bgOnly = editor.label == commandBarEditorLabel
                if (!bgOnly) {
                    gbc.gridy = row++
                    gbc.gridx = 0; grid.add(JBLabel("Font:"), gbc)
                    gbc.gridx = 1; grid.add(editor.fontCombo, gbc)
                    gbc.gridy = row++
                    gbc.gridx = 0; grid.add(JBLabel("Size:"), gbc)
                    gbc.gridx = 1; grid.add(editor.sizeSpinner, gbc)
                    gbc.gridy = row++
                    gbc.gridx = 0; grid.add(JBLabel("Style:"), gbc)
                    gbc.gridx = 1; grid.add(editor.styleCombo, gbc)
                    gbc.gridy = row++
                    gbc.gridx = 0; grid.add(JBLabel("Color:"), gbc)
                    gbc.gridx = 1; grid.add(editor.colorButton, gbc)
                }
                gbc.gridy = row++
                gbc.gridx = 0; grid.add(JBLabel("Background:"), gbc)
                gbc.gridx = 1; grid.add(editor.bgColorButton, gbc)
                if (editor.label == panelEditorLabel) {
                    gbc.gridy = row++
                    gbc.gridx = 0; grid.add(JBLabel("Selected:"), gbc)
                    gbc.gridx = 1; grid.add(editor.selectedColorButton, gbc)
                    gbc.gridy = row
                    gbc.gridx = 0; grid.add(JBLabel("Active selected:"), gbc)
                    gbc.gridx = 1; grid.add(editor.activeSelectedColorButton, gbc)
                }
                detailPanel.add(grid, BorderLayout.NORTH)
            }
            detailPanel.revalidate()
            detailPanel.repaint()
        }

        componentList.addListSelectionListener { renderDetail() }
        if (componentListModel.size() > 0) componentList.selectedIndex = 0

        val listPanel = JPanel(BorderLayout()).apply {
            add(JScrollPane(componentList).apply {
                preferredSize = Dimension(160, 0)
            }, BorderLayout.CENTER)
        }

        val styleArea = JPanel(BorderLayout(8, 0)).apply {
            add(listPanel, BorderLayout.WEST)
            add(detailPanel, BorderLayout.CENTER)
            preferredSize = Dimension(Int.MAX_VALUE, 280)
            maximumSize = Dimension(Int.MAX_VALUE, 280)
        }

        return JPanel(BorderLayout(8, 4)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createTitledBorder("Appearance")
            add(themeRow, BorderLayout.NORTH)
            add(styleArea, BorderLayout.CENTER)
        }
    }

    // --- Theme actions ---

    private fun saveCurrentTheme() {
        val dialog = InputDialog(title = "Save Theme", message = "Enter theme name:")
        if (!dialog.showAndGet()) return
        val name = dialog.inputValue
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
        val renameDialog = InputDialog(title = "Rename Theme", message = "Enter new name for \"${current.name}\":", initialValue = current.name)
        if (!renameDialog.showAndGet()) return
        val newName = renameDialog.inputValue
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
            || saveCommandHistoryCheckBox?.isSelected != settings.saveCommandHistory
            || hideDriveSelectorCheckBox?.isSelected != settings.hideDriveSelector
            || hideStatusBarCheckBox?.isSelected != settings.hideStatusBar
            || (overwritePolicyCombo?.item ?: OverwritePolicy.ASK).name != settings.defaultOverwritePolicy
            || deleteToRecycleBinCheckBox?.isSelected != settings.deleteToRecycleBin
            || sortWithDirectoriesCheckBox?.isSelected != settings.sortWithDirectories
            || naturalNameSortCheckBox?.isSelected != settings.naturalNameSort
            || calculateDirectorySizeCheckBox?.isSelected != settings.calculateDirectorySize
            || openDirectoriesWithSingleClickCheckBox?.isSelected != settings.openDirectoriesWithSingleClick
            || getSelectedViewMode() != settings.defaultViewMode
            || getSelectedPanelLayout() != settings.panelLayout
            || getSelectedThumbnailSize() != settings.thumbnailSize
            || getSelectedFileSizeFormat() != settings.fileSizeFormat
            || getEnteredDateTimeFormat() != settings.dateTimeFormat
            || getEnteredExportDateTimeFormat() != settings.exportDateTimeFormat
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
            || colorRulesEditor?.isModified() == true
    }

    override fun apply() {
        val service = TurtleCommanderSettings.getInstance()
        val settings = service.state
        settings.enableFileNameHighlighting = highlightingCheckBox?.isSelected ?: settings.enableFileNameHighlighting
        settings.showCommandBar = commandBarCheckBox?.isSelected ?: settings.showCommandBar
        settings.saveCommandHistory = saveCommandHistoryCheckBox?.isSelected ?: settings.saveCommandHistory
        settings.hideDriveSelector = hideDriveSelectorCheckBox?.isSelected ?: settings.hideDriveSelector
        settings.hideStatusBar = hideStatusBarCheckBox?.isSelected ?: settings.hideStatusBar
        settings.defaultOverwritePolicy =
            (overwritePolicyCombo?.item ?: OverwritePolicy.valueOf(settings.defaultOverwritePolicy)).name
        settings.deleteToRecycleBin = deleteToRecycleBinCheckBox?.isSelected ?: settings.deleteToRecycleBin
        settings.sortWithDirectories = sortWithDirectoriesCheckBox?.isSelected ?: settings.sortWithDirectories
        settings.naturalNameSort = naturalNameSortCheckBox?.isSelected ?: settings.naturalNameSort
        settings.calculateDirectorySize = calculateDirectorySizeCheckBox?.isSelected ?: settings.calculateDirectorySize
        settings.openDirectoriesWithSingleClick = openDirectoriesWithSingleClickCheckBox?.isSelected ?: settings.openDirectoriesWithSingleClick
        settings.defaultViewMode = getSelectedViewMode()
        settings.panelLayout = getSelectedPanelLayout()
        settings.thumbnailSize = getSelectedThumbnailSize()
        settings.fileSizeFormat = getSelectedFileSizeFormat()
        settings.dateTimeFormat = getEnteredDateTimeFormat()
        settings.exportDateTimeFormat = getEnteredExportDateTimeFormat()

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
        colorRulesEditor?.applyTo(settings)

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
        saveCommandHistoryCheckBox?.isSelected = settings.saveCommandHistory
        hideDriveSelectorCheckBox?.isSelected = settings.hideDriveSelector
        hideStatusBarCheckBox?.isSelected = settings.hideStatusBar
        overwritePolicyCombo?.item = TurtleCommanderSettings.getInstance().getDefaultOverwritePolicy()
        deleteToRecycleBinCheckBox?.isSelected = settings.deleteToRecycleBin
        sortWithDirectoriesCheckBox?.isSelected = settings.sortWithDirectories
        naturalNameSortCheckBox?.isSelected = settings.naturalNameSort
        calculateDirectorySizeCheckBox?.isSelected = settings.calculateDirectorySize
        openDirectoriesWithSingleClickCheckBox?.isSelected = settings.openDirectoriesWithSingleClick
        defaultViewModeCombo?.selectedItem = when (settings.defaultViewMode) {
            "LIST" -> "List"
            "THUMBNAIL" -> "Thumbnail"
            "TREE" -> "Tree"
            else -> "Table"
        }
        panelLayoutCombo?.selectedItem = layoutLabelFor(settings.panelLayout)
        thumbnailSizeCombo?.selectedItem = ThumbnailSize.fromName(settings.thumbnailSize)
        fileSizeFormatCombo?.selectedItem = FileSizeFormat.fromName(settings.fileSizeFormat)
        dateTimeFormatField?.text = settings.dateTimeFormat.ifBlank { DEFAULT_DATE_TIME_FORMAT }
        exportDateTimeFormatField?.text = settings.exportDateTimeFormat
        updateDateTimePreview()

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
        colorRulesEditor?.resetFrom(settings)
    }

    override fun disposeUIResources() {
        highlightingCheckBox = null
        commandBarCheckBox = null
        hideDriveSelectorCheckBox = null
        hideStatusBarCheckBox = null
        overwritePolicyCombo = null
        deleteToRecycleBinCheckBox = null
        sortWithDirectoriesCheckBox = null
        naturalNameSortCheckBox = null
        calculateDirectorySizeCheckBox = null
        openDirectoriesWithSingleClickCheckBox = null
        defaultViewModeCombo = null
        panelLayoutCombo = null
        thumbnailSizeCombo = null
        fileSizeFormatCombo = null
        dateTimeFormatField = null
        exportDateTimeFormatField = null
        dateTimePreviewLabel = null
        columnsEditor = null
        favoritesEditor = null
        colorRulesEditor = null
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

    private fun getSelectedPanelLayout(): String {
        return when (panelLayoutCombo?.selectedItem as? String) {
            "Vertical Dual-panel" -> PanelLayout.VERTICAL.name
            "Single-panel" -> PanelLayout.SINGLE.name
            else -> PanelLayout.HORIZONTAL.name
        }
    }

    private fun getSelectedThumbnailSize(): String {
        val selected = thumbnailSizeCombo?.selectedItem as? ThumbnailSize ?: ThumbnailSize.SMALL
        return selected.name
    }

    private fun getSelectedFileSizeFormat(): String {
        val selected = fileSizeFormatCombo?.selectedItem as? FileSizeFormat ?: FileSizeFormat.AUTO_BINARY
        return selected.name
    }

    private fun getEnteredDateTimeFormat(): String {
        val text = dateTimeFormatField?.text?.trim().orEmpty()
        return text.ifEmpty { DEFAULT_DATE_TIME_FORMAT }
    }

    /** Export format keeps a blank value verbatim — blank selects the ISO-8601 UTC default. */
    private fun getEnteredExportDateTimeFormat(): String =
        exportDateTimeFormatField?.text?.trim().orEmpty()

    /**
     * Render a sample timestamp next to the pattern field so the user gets immediate
     * feedback on what the rendered date will look like — and an inline error when
     * the pattern doesn't parse rather than waiting for apply().
     */
    private fun updateDateTimePreview() {
        val label = dateTimePreviewLabel ?: return
        val pattern = dateTimeFormatField?.text?.trim().orEmpty().ifEmpty { DEFAULT_DATE_TIME_FORMAT }
        val sample = Instant.parse("2023-11-14T22:13:20Z").toEpochMilli()
        val text = runCatching {
            DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(sample))
        }.getOrElse { "invalid pattern" }
        label.text = "→ $text"
    }

    private fun layoutLabelFor(name: String): String = when (name) {
        PanelLayout.VERTICAL.name -> "Vertical Dual-panel"
        PanelLayout.SINGLE.name -> "Single-panel"
        else -> "Horizontal Dual-panel"
    }

    private fun getSelectedThemeName(): String {
        val theme = themeCombo?.selectedItem as? Theme ?: return ""
        return theme.name
    }
}
