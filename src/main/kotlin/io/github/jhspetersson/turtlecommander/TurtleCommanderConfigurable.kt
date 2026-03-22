package io.github.jhspetersson.turtlecommander

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import java.awt.GraphicsEnvironment
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import com.intellij.util.ui.JBUI
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class TurtleCommanderConfigurable : Configurable {

    private var highlightingCheckBox: JCheckBox? = null
    private var commandBarCheckBox: JCheckBox? = null
    private var hideDriveSelectorCheckBox: JCheckBox? = null
    private var hideStatusBarCheckBox: JCheckBox? = null
    private var overwriteCheckBox: JCheckBox? = null
    private var panelFontCombo: ComboBox<String>? = null
    private var panelFontSizeSpinner: JSpinner? = null
    private var tabFontCombo: ComboBox<String>? = null
    private var tabFontSizeSpinner: JSpinner? = null
    private var defaultViewModeCombo: ComboBox<String>? = null
    private var favoritesListModel: DefaultListModel<String>? = null
    private var favoritesList: JBList<String>? = null

    override fun getDisplayName(): String = "Turtle Commander"

    override fun createComponent(): JComponent {
        val settings = TurtleCommanderSettings.getInstance().state
        val fontFamilies = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames

        highlightingCheckBox = JCheckBox("Enable file name highlighting for project directories", settings.enableFileNameHighlighting)
        commandBarCheckBox = JCheckBox("Show command bar (F5 Copy, F6 Move, etc.)", settings.showCommandBar)
        hideDriveSelectorCheckBox = JCheckBox("Hide drive selector", settings.hideDriveSelector)
        hideStatusBarCheckBox = JCheckBox("Hide status bar", settings.hideStatusBar)
        overwriteCheckBox = JCheckBox("Always overwrite existing files during copy/move", settings.alwaysOverwriteFiles)

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

        val viewModeItems = arrayOf("Table", "List", "Tree")
        defaultViewModeCombo = ComboBox(DefaultComboBoxModel(viewModeItems)).apply {
            selectedItem = when (settings.defaultViewMode) {
                "LIST" -> "List"
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

        // Favorites editor
        val favListModel = DefaultListModel<String>()
        favoritesListModel = favListModel
        val currentProject = ProjectManager.getInstance().openProjects.firstOrNull()
        if (currentProject != null) {
            val stateService = currentProject.service<FileManagerStateService>()
            stateService.getFavorites().forEach { favListModel.addElement(it) }
        }

        val favList = JBList(favListModel)
        favoritesList = favList
        favList.visibleRowCount = 6

        val addButton = JButton("Add").apply {
            addActionListener {
                val path = com.intellij.openapi.ui.Messages.showInputDialog(
                    "Enter favorite path:", "Add Favorite", null
                )
                if (!path.isNullOrBlank()) {
                    favListModel.addElement(path.trim())
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

        val favButtonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(addButton)
            add(removeButton)
            add(javax.swing.Box.createVerticalStrut(8))
            add(moveUpButton)
            add(moveDownButton)
        }

        val favoritesPanel = JPanel(BorderLayout(8, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = javax.swing.BorderFactory.createTitledBorder("Favorites")
            add(javax.swing.JScrollPane(favList), BorderLayout.CENTER)
            add(favButtonPanel, BorderLayout.EAST)
            preferredSize = Dimension(Int.MAX_VALUE, 200)
            maximumSize = Dimension(Int.MAX_VALUE, 200)
        }

        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(highlightingCheckBox)
            add(commandBarCheckBox)
            add(hideDriveSelectorCheckBox)
            add(hideStatusBarCheckBox)
            add(overwriteCheckBox)
            add(javax.swing.Box.createVerticalStrut(8))
            add(fontGrid)
            add(javax.swing.Box.createVerticalStrut(8))
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
        val saved = stateService.getFavorites()
        if (model.size() != saved.size) return true
        for (i in 0 until model.size()) {
            if (model.getElementAt(i) != saved[i]) return true
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
                val newFavorites = (0 until model.size()).map { model.getElementAt(it) }
                stateService.setFavorites(newFavorites)
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
        panelFontCombo?.selectedItem = settings.panelFontFamily.ifEmpty { defaultLabel }
        panelFontSizeSpinner?.value = if (settings.panelFontSize > 0) settings.panelFontSize else 13
        tabFontCombo?.selectedItem = settings.tabFontFamily.ifEmpty { defaultLabel }
        tabFontSizeSpinner?.value = if (settings.tabFontSize > 0) settings.tabFontSize else 12
        defaultViewModeCombo?.selectedItem = when (settings.defaultViewMode) {
            "LIST" -> "List"
            "TREE" -> "Tree"
            else -> "Table"
        }

        val model = favoritesListModel
        if (model != null) {
            model.clear()
            val currentProject = ProjectManager.getInstance().openProjects.firstOrNull()
            if (currentProject != null) {
                val stateService = currentProject.service<FileManagerStateService>()
                stateService.getFavorites().forEach { model.addElement(it) }
            }
        }
    }

    override fun disposeUIResources() {
        highlightingCheckBox = null
        commandBarCheckBox = null
        hideDriveSelectorCheckBox = null
        hideStatusBarCheckBox = null
        overwriteCheckBox = null
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
            "Tree" -> "TREE"
            else -> "TABLE"
        }
    }

    private fun getSelectedFontFamily(combo: ComboBox<String>?, defaultLabel: String): String {
        val selected = combo?.selectedItem as? String ?: return ""
        return if (selected == defaultLabel) "" else selected
    }
}
