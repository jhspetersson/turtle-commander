package io.github.jhspetersson.turtlecommander

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton

data class FileSearchCriteria(
    val rootPath: Path,
    val namePattern: String?,
    val namePatternMode: NamePatternMode,
    val sizeFilter: SizeFilter?,
    val creationDateFilter: DateFilter?,
    val modificationDateFilter: DateFilter?,
)

enum class NamePatternMode { GLOB, REGEXP }

data class SizeFilter(
    val mode: SizeFilterMode,
    val sizeBytes: Long,
    val sizeBytes2: Long?,
)

enum class SizeFilterMode { MORE_THAN, APPROX_EQUAL, LESS_THAN, IN_BETWEEN }

data class DateFilter(
    val mode: DateFilterMode,
    val dateMillis: Long,
    val dateMillis2: Long?,
)

enum class DateFilterMode { EARLIER, APPROX_EQUAL, LATER, IN_BETWEEN }

class FileSearchDialog(
    project: Project,
    initialRoot: Path,
    initialCriteria: FileSearchCriteria? = null,
) : DialogWrapper(project) {

    private val rootField = JBTextField(initialRoot.toString())

    private val nameCheckBox = JCheckBox("Search by name", initialCriteria?.namePattern != null)
    private val nameField = JBTextField(initialCriteria?.namePattern ?: "*")
    private val globRadio = JRadioButton("Glob", initialCriteria?.namePatternMode != NamePatternMode.REGEXP)
    private val regexpRadio = JRadioButton("Regexp", initialCriteria?.namePatternMode == NamePatternMode.REGEXP)

    private val sizeCheckBox = JCheckBox("Search by size", initialCriteria?.sizeFilter != null)
    private val sizeModeCombo = ComboBox(DefaultComboBoxModel(arrayOf("More than", "Approximately equal", "Less than", "In between")))
    private val sizeField1 = JBTextField(10)
    private val sizeField2 = JBTextField(10)
    private val sizeUnitCombo = ComboBox(DefaultComboBoxModel(arrayOf("B", "KB", "MB", "GB")))
    private val sizeField2Label = JBLabel("and")

    private val creationDateCheckBox = JCheckBox("Search by creation date", initialCriteria?.creationDateFilter != null)
    private val creationDateModeCombo = ComboBox(DefaultComboBoxModel(arrayOf("Earlier than", "Approximately equal", "Later than", "In between")))
    private val creationDateField1 = JBTextField(12)
    private val creationDateField2 = JBTextField(12)
    private val creationDateField2Label = JBLabel("and")

    private val modificationDateCheckBox = JCheckBox("Search by modification date", initialCriteria?.modificationDateFilter != null)
    private val modificationDateModeCombo = ComboBox(DefaultComboBoxModel(arrayOf("Earlier than", "Approximately equal", "Later than", "In between")))
    private val modificationDateField1 = JBTextField(12)
    private val modificationDateField2 = JBTextField(12)
    private val modificationDateField2Label = JBLabel("and")

    private val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")

    init {
        title = "Search Files"
        setOKButtonText("Search")

        if (initialCriteria != null) {
            initFromCriteria(initialCriteria)
        }

        setupEnabling()
        setupSizeInBetween()
        setupDateInBetween()

        init()
    }

    private fun initFromCriteria(c: FileSearchCriteria) {
        c.sizeFilter?.let { sf ->
            sizeModeCombo.selectedIndex = sf.mode.ordinal
            val (value1, unit1) = bytesToUnit(sf.sizeBytes)
            sizeField1.text = value1
            sizeUnitCombo.selectedItem = unit1
            sf.sizeBytes2?.let { val (v2, _) = bytesToUnit(it); sizeField2.text = v2 }
        }
        c.creationDateFilter?.let { df ->
            creationDateModeCombo.selectedIndex = df.mode.ordinal
            creationDateField1.text = dateFormat.format(java.util.Date(df.dateMillis))
            df.dateMillis2?.let { creationDateField2.text = dateFormat.format(java.util.Date(it)) }
        }
        c.modificationDateFilter?.let { df ->
            modificationDateModeCombo.selectedIndex = df.mode.ordinal
            modificationDateField1.text = dateFormat.format(java.util.Date(df.dateMillis))
            df.dateMillis2?.let { modificationDateField2.text = dateFormat.format(java.util.Date(it)) }
        }
    }

    private fun bytesToUnit(bytes: Long): Pair<String, String> {
        return when {
            bytes >= 1024L * 1024 * 1024 -> Pair("%.1f".format(bytes / (1024.0 * 1024 * 1024)), "GB")
            bytes >= 1024L * 1024 -> Pair("%.1f".format(bytes / (1024.0 * 1024)), "MB")
            bytes >= 1024L -> Pair("%.1f".format(bytes / 1024.0), "KB")
            else -> Pair(bytes.toString(), "B")
        }
    }

    private fun setupEnabling() {
        fun updateNameEnabled() {
            val enabled = nameCheckBox.isSelected
            nameField.isEnabled = enabled
            globRadio.isEnabled = enabled
            regexpRadio.isEnabled = enabled
        }

        fun updateSizeEnabled() {
            val enabled = sizeCheckBox.isSelected
            sizeModeCombo.isEnabled = enabled
            sizeField1.isEnabled = enabled
            sizeField2.isEnabled = enabled
            sizeUnitCombo.isEnabled = enabled
        }

        fun updateCreationDateEnabled() {
            val enabled = creationDateCheckBox.isSelected
            creationDateModeCombo.isEnabled = enabled
            creationDateField1.isEnabled = enabled
            creationDateField2.isEnabled = enabled
        }

        fun updateModificationDateEnabled() {
            val enabled = modificationDateCheckBox.isSelected
            modificationDateModeCombo.isEnabled = enabled
            modificationDateField1.isEnabled = enabled
            modificationDateField2.isEnabled = enabled
        }

        nameCheckBox.addActionListener { updateNameEnabled() }
        sizeCheckBox.addActionListener { updateSizeEnabled() }
        creationDateCheckBox.addActionListener { updateCreationDateEnabled() }
        modificationDateCheckBox.addActionListener { updateModificationDateEnabled() }

        updateNameEnabled()
        updateSizeEnabled()
        updateCreationDateEnabled()
        updateModificationDateEnabled()
    }

    private fun setupSizeInBetween() {
        fun updateVisibility() {
            val inBetween = sizeModeCombo.selectedIndex == SizeFilterMode.IN_BETWEEN.ordinal
            sizeField2.isVisible = inBetween
            sizeField2Label.isVisible = inBetween
        }
        sizeModeCombo.addActionListener { updateVisibility() }
        updateVisibility()
    }

    private fun setupDateInBetween() {
        fun updateCreation() {
            val inBetween = creationDateModeCombo.selectedIndex == DateFilterMode.IN_BETWEEN.ordinal
            creationDateField2.isVisible = inBetween
            creationDateField2Label.isVisible = inBetween
        }
        fun updateModification() {
            val inBetween = modificationDateModeCombo.selectedIndex == DateFilterMode.IN_BETWEEN.ordinal
            modificationDateField2.isVisible = inBetween
            modificationDateField2Label.isVisible = inBetween
        }
        creationDateModeCombo.addActionListener { updateCreation() }
        modificationDateModeCombo.addActionListener { updateModification() }
        updateCreation()
        updateModification()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            minimumSize = Dimension(550, 300)
            preferredSize = Dimension(550, 400)
        }

        // Search root
        panel.add(JBLabel("Search in:").apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        panel.add(JPanel(BorderLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(4, 0, 8, 0)
            add(rootField, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, rootField.preferredSize.height + 8)
        })

        // Name filter
        panel.add(nameCheckBox.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        panel.add(createNamePanel())

        panel.add(javax.swing.Box.createVerticalStrut(8))

        // Size filter
        panel.add(sizeCheckBox.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        panel.add(createSizePanel())

        panel.add(javax.swing.Box.createVerticalStrut(8))

        // Creation date filter
        panel.add(creationDateCheckBox.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        panel.add(createDatePanel(creationDateModeCombo, creationDateField1, creationDateField2, creationDateField2Label))

        panel.add(javax.swing.Box.createVerticalStrut(8))

        // Modification date filter
        panel.add(modificationDateCheckBox.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        panel.add(createDatePanel(modificationDateModeCombo, modificationDateField1, modificationDateField2, modificationDateField2Label))

        return panel
    }

    private fun createNamePanel(): JPanel {
        val group = ButtonGroup()
        group.add(globRadio)
        group.add(regexpRadio)

        return JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(2, 20, 0, 0)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = java.awt.Insets(2, 4, 2, 4)
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            gbc.gridwidth = 2
            add(nameField, gbc)
            gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            gbc.gridx = 0; gbc.gridy = 1
            add(globRadio, gbc)
            gbc.gridx = 1
            add(regexpRadio, gbc)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun createSizePanel(): JPanel {
        return JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(2, 20, 0, 0)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = java.awt.Insets(2, 4, 2, 4)
            }
            gbc.gridx = 0; gbc.gridy = 0
            add(sizeModeCombo, gbc)
            gbc.gridx = 1
            add(sizeField1, gbc)
            gbc.gridx = 2
            add(sizeField2Label, gbc)
            gbc.gridx = 3
            add(sizeField2, gbc)
            gbc.gridx = 4
            add(sizeUnitCombo, gbc)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun createDatePanel(
        modeCombo: ComboBox<String>,
        field1: JBTextField,
        field2: JBTextField,
        field2Label: JBLabel,
    ): JPanel {
        field1.emptyText.text = "yyyy-MM-dd HH:mm"
        field2.emptyText.text = "yyyy-MM-dd HH:mm"
        return JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(2, 20, 0, 0)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = java.awt.Insets(2, 4, 2, 4)
            }
            gbc.gridx = 0; gbc.gridy = 0
            add(modeCombo, gbc)
            gbc.gridx = 1
            add(field1, gbc)
            gbc.gridx = 2
            add(field2Label, gbc)
            gbc.gridx = 3
            add(field2, gbc)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    fun getCriteria(): FileSearchCriteria {
        val namePattern = if (nameCheckBox.isSelected && nameField.text.isNotBlank()) nameField.text.trim() else null
        val nameMode = if (regexpRadio.isSelected) NamePatternMode.REGEXP else NamePatternMode.GLOB

        val sizeFilter = if (sizeCheckBox.isSelected) {
            val mode = SizeFilterMode.entries[sizeModeCombo.selectedIndex]
            val multiplier = when (sizeUnitCombo.selectedItem as? String) {
                "KB" -> 1024L
                "MB" -> 1024L * 1024
                "GB" -> 1024L * 1024 * 1024
                else -> 1L
            }
            val size1 = (sizeField1.text.trim().toDoubleOrNull()?.toLong() ?: 0L) * multiplier
            val size2 = if (mode == SizeFilterMode.IN_BETWEEN) {
                (sizeField2.text.trim().toDoubleOrNull()?.toLong() ?: 0L) * multiplier
            } else null
            SizeFilter(mode, size1, size2)
        } else null

        val creationDate = parseDateFilter(creationDateCheckBox, creationDateModeCombo, creationDateField1, creationDateField2)
        val modificationDate = parseDateFilter(modificationDateCheckBox, modificationDateModeCombo, modificationDateField1, modificationDateField2)

        return FileSearchCriteria(
            rootPath = Path.of(rootField.text.trim()),
            namePattern = namePattern,
            namePatternMode = nameMode,
            sizeFilter = sizeFilter,
            creationDateFilter = creationDate,
            modificationDateFilter = modificationDate,
        )
    }

    private fun parseDateFilter(
        checkBox: JCheckBox,
        modeCombo: ComboBox<String>,
        field1: JBTextField,
        field2: JBTextField,
    ): DateFilter? {
        if (!checkBox.isSelected) return null
        val mode = DateFilterMode.entries[modeCombo.selectedIndex]
        val date1 = try { dateFormat.parse(field1.text.trim()).time } catch (_: Exception) { return null }
        val date2 = if (mode == DateFilterMode.IN_BETWEEN) {
            try { dateFormat.parse(field2.text.trim()).time } catch (_: Exception) { return null }
        } else null
        return DateFilter(mode, date1, date2)
    }

    override fun getPreferredFocusedComponent() = nameField
}
