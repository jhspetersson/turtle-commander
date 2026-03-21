package io.github.jhspetersson.turtlecommander

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import java.awt.GraphicsEnvironment
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import com.intellij.util.ui.JBUI
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class TurtleCommanderConfigurable : Configurable {

    private var highlightingCheckBox: JCheckBox? = null
    private var commandBarCheckBox: JCheckBox? = null
    private var overwriteCheckBox: JCheckBox? = null
    private var panelFontCombo: ComboBox<String>? = null
    private var panelFontSizeSpinner: JSpinner? = null
    private var tabFontCombo: ComboBox<String>? = null
    private var tabFontSizeSpinner: JSpinner? = null

    override fun getDisplayName(): String = "Turtle Commander"

    override fun createComponent(): JComponent {
        val settings = TurtleCommanderSettings.getInstance().state
        val fontFamilies = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames

        highlightingCheckBox = JCheckBox("Enable file name highlighting for project directories", settings.enableFileNameHighlighting)
        commandBarCheckBox = JCheckBox("Show command bar (F5 Copy, F6 Move, etc.)", settings.showCommandBar)
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

        val fontGrid = JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(2, 4, 2, 4)
            }

            gbc.gridx = 0; gbc.gridy = 0
            gbc.insets = JBUI.insets(2, 0, 2, 4)
            add(JBLabel("File panel font:"), gbc)
            gbc.insets = JBUI.insets(2, 4, 2, 4)
            gbc.gridx = 1
            add(panelFontCombo!!, gbc)
            gbc.gridx = 2
            add(JBLabel("Size:"), gbc)
            gbc.gridx = 3
            add(panelFontSizeSpinner!!, gbc)

            gbc.gridx = 0; gbc.gridy = 1
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
        overwriteCheckBox!!.alignmentX = JComponent.LEFT_ALIGNMENT

        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(highlightingCheckBox)
            add(commandBarCheckBox)
            add(overwriteCheckBox)
            add(javax.swing.Box.createVerticalStrut(8))
            add(fontGrid)
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
            || overwriteCheckBox?.isSelected != settings.alwaysOverwriteFiles
            || getSelectedFontFamily(panelFontCombo, defaultLabel) != settings.panelFontFamily
            || ((panelFontSizeSpinner?.value as? Number)?.toInt() ?: 0) != (if (settings.panelFontSize > 0) settings.panelFontSize else 13)
            || getSelectedFontFamily(tabFontCombo, defaultLabel) != settings.tabFontFamily
            || ((tabFontSizeSpinner?.value as? Number)?.toInt() ?: 0) != (if (settings.tabFontSize > 0) settings.tabFontSize else 12)
    }

    override fun apply() {
        val service = TurtleCommanderSettings.getInstance()
        val settings = service.state
        val defaultLabel = "(Default)"
        settings.enableFileNameHighlighting = highlightingCheckBox?.isSelected ?: settings.enableFileNameHighlighting
        settings.showCommandBar = commandBarCheckBox?.isSelected ?: settings.showCommandBar
        settings.alwaysOverwriteFiles = overwriteCheckBox?.isSelected ?: settings.alwaysOverwriteFiles
        settings.panelFontFamily = getSelectedFontFamily(panelFontCombo, defaultLabel)
        settings.panelFontSize = (panelFontSizeSpinner?.value as? Number)?.toInt() ?: 0
        settings.tabFontFamily = getSelectedFontFamily(tabFontCombo, defaultLabel)
        settings.tabFontSize = (tabFontSizeSpinner?.value as? Number)?.toInt() ?: 0
        service.fireSettingsChanged()
    }

    override fun reset() {
        val settings = TurtleCommanderSettings.getInstance().state
        val defaultLabel = "(Default)"
        highlightingCheckBox?.isSelected = settings.enableFileNameHighlighting
        commandBarCheckBox?.isSelected = settings.showCommandBar
        overwriteCheckBox?.isSelected = settings.alwaysOverwriteFiles
        panelFontCombo?.selectedItem = settings.panelFontFamily.ifEmpty { defaultLabel }
        panelFontSizeSpinner?.value = if (settings.panelFontSize > 0) settings.panelFontSize else 13
        tabFontCombo?.selectedItem = settings.tabFontFamily.ifEmpty { defaultLabel }
        tabFontSizeSpinner?.value = if (settings.tabFontSize > 0) settings.tabFontSize else 12
    }

    override fun disposeUIResources() {
        highlightingCheckBox = null
        commandBarCheckBox = null
        overwriteCheckBox = null
        panelFontCombo = null
        panelFontSizeSpinner = null
        tabFontCombo = null
        tabFontSizeSpinner = null
    }

    private fun getSelectedFontFamily(combo: ComboBox<String>?, defaultLabel: String): String {
        val selected = combo?.selectedItem as? String ?: return ""
        return if (selected == defaultLabel) "" else selected
    }
}
