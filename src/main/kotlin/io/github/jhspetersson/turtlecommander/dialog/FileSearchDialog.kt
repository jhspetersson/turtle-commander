package io.github.jhspetersson.turtlecommander.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import io.github.jhspetersson.turtlecommander.ui.installStandardContextMenu
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*

data class FileSearchCriteria(
    val rootPath: Path,
    val namePattern: String?,
    val namePatternMode: NamePatternMode,
    val caseSensitive: Boolean = false,
    val sizeFilter: SizeFilter?,
    val creationDateFilter: DateFilter?,
    val modificationDateFilter: DateFilter?,
    val ownerPattern: String? = null,
    val groupPattern: String? = null,
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
    val startMillis: Long,
    val endMillis: Long,
    val startMillis2: Long?,
    val endMillis2: Long?,
    val text1: String,
    val text2: String?,
)

enum class DateFilterMode { EARLIER, APPROX_EQUAL, LATER, IN_BETWEEN }

class FileSearchDialog(
    project: Project,
    initialRoot: Path,
    initialCriteria: FileSearchCriteria? = null,
) : DialogWrapper(project) {

    companion object {
        fun bytesToUnit(bytes: Long): Pair<String, String> {
            return when {
                bytes >= 1024L * 1024 * 1024 -> Pair("%.1f".format(bytes / (1024.0 * 1024 * 1024)), "GB")
                bytes >= 1024L * 1024 -> Pair("%.1f".format(bytes / (1024.0 * 1024)), "MB")
                bytes >= 1024L -> Pair("%.1f".format(bytes / 1024.0), "KB")
                else -> Pair(bytes.toString(), "B")
            }
        }
    }

    private val rootField = JBTextField(initialRoot.toString())

    private val nameCheckBox = JCheckBox("Search by name", initialCriteria == null || initialCriteria.namePattern != null)
    private val nameField = JBTextField(initialCriteria?.namePattern ?: "*")
    private val globRadio = JRadioButton("Glob", initialCriteria?.namePatternMode != NamePatternMode.REGEXP)
    private val regexpRadio = JRadioButton("Regexp", initialCriteria?.namePatternMode == NamePatternMode.REGEXP)
    private val caseSensitiveCheckBox = JCheckBox("Case sensitive", initialCriteria?.caseSensitive == true)

    private val sizeCheckBox = JCheckBox("Search by size", initialCriteria?.sizeFilter != null)
    private val sizeModeCombo = ComboBox(DefaultComboBoxModel(arrayOf("More than", "Approximately equal", "Less than", "In between"))).apply {
        initialCriteria?.sizeFilter?.let { selectedIndex = it.mode.ordinal }
    }
    private val sizeField1 = JBTextField(10).apply {
        initialCriteria?.sizeFilter?.let { text = bytesToUnit(it.sizeBytes).first }
    }
    private val sizeField2 = JBTextField(10).apply {
        initialCriteria?.sizeFilter?.sizeBytes2?.let { text = bytesToUnit(it).first }
    }
    private val sizeUnitCombo = ComboBox(DefaultComboBoxModel(arrayOf("B", "KB", "MB", "GB"))).apply {
        selectedItem = initialCriteria?.sizeFilter?.let { bytesToUnit(it.sizeBytes).second } ?: "MB"
    }
    private val sizeField2Label = JBLabel("and")

    private val creationDateCheckBox = JCheckBox("Search by creation date", initialCriteria?.creationDateFilter != null)
    private val creationDateModeCombo = ComboBox(DefaultComboBoxModel(arrayOf("Earlier than", "Approximately equal", "Later than", "In between"))).apply {
        initialCriteria?.creationDateFilter?.let { selectedIndex = it.mode.ordinal }
    }
    private val creationDateField1 = JBTextField(16).apply {
        emptyText.text = "yyyy[-MM[-dd[ HH:mm]]]"
        initialCriteria?.creationDateFilter?.let { text = it.text1 }
    }
    private val creationDateField2 = JBTextField(16).apply {
        emptyText.text = "yyyy[-MM[-dd[ HH:mm]]]"
        initialCriteria?.creationDateFilter?.text2?.let { text = it }
    }
    private val creationDateField2Label = JBLabel("and")

    private val modificationDateCheckBox = JCheckBox("Search by modification date", initialCriteria?.modificationDateFilter != null)
    private val modificationDateModeCombo = ComboBox(DefaultComboBoxModel(arrayOf("Earlier than", "Approximately equal", "Later than", "In between"))).apply {
        initialCriteria?.modificationDateFilter?.let { selectedIndex = it.mode.ordinal }
    }
    private val modificationDateField1 = JBTextField(16).apply {
        emptyText.text = "yyyy[-MM[-dd[ HH:mm]]]"
        initialCriteria?.modificationDateFilter?.let { text = it.text1 }
    }
    private val modificationDateField2 = JBTextField(16).apply {
        emptyText.text = "yyyy[-MM[-dd[ HH:mm]]]"
        initialCriteria?.modificationDateFilter?.text2?.let { text = it }
    }
    private val modificationDateField2Label = JBLabel("and")

    private val ownerCheckBox = JCheckBox("Search by owner", initialCriteria?.ownerPattern != null)
    private val ownerField = JBTextField(initialCriteria?.ownerPattern ?: "")

    private val groupCheckBox = JCheckBox("Search by group", initialCriteria?.groupPattern != null)
    private val groupField = JBTextField(initialCriteria?.groupPattern ?: "")

    init {
        title = "Search Files"
        setOKButtonText("Search")

        listOf(
            rootField, nameField, sizeField1, sizeField2,
            creationDateField1, creationDateField2,
            modificationDateField1, modificationDateField2,
            ownerField, groupField,
        ).forEach { it.installStandardContextMenu() }

        init()

        setupEnabling()
        setupSizeInBetween()
        setupDateInBetween()
    }

    private fun setupEnabling() {
        fun updateNameEnabled() {
            val enabled = nameCheckBox.isSelected
            nameField.isEnabled = enabled
            globRadio.isEnabled = enabled
            regexpRadio.isEnabled = enabled
            caseSensitiveCheckBox.isEnabled = enabled
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

        fun updateOwnerEnabled() { ownerField.isEnabled = ownerCheckBox.isSelected }
        fun updateGroupEnabled() { groupField.isEnabled = groupCheckBox.isSelected }

        nameCheckBox.addActionListener { updateNameEnabled(); if (nameCheckBox.isSelected) nameField.requestFocusInWindow() }
        sizeCheckBox.addActionListener { updateSizeEnabled(); if (sizeCheckBox.isSelected) sizeField1.requestFocusInWindow() }
        creationDateCheckBox.addActionListener { updateCreationDateEnabled(); if (creationDateCheckBox.isSelected) creationDateField1.requestFocusInWindow() }
        modificationDateCheckBox.addActionListener { updateModificationDateEnabled(); if (modificationDateCheckBox.isSelected) modificationDateField1.requestFocusInWindow() }
        ownerCheckBox.addActionListener { updateOwnerEnabled(); if (ownerCheckBox.isSelected) ownerField.requestFocusInWindow() }
        groupCheckBox.addActionListener { updateGroupEnabled(); if (groupCheckBox.isSelected) groupField.requestFocusInWindow() }

        updateNameEnabled()
        updateSizeEnabled()
        updateCreationDateEnabled()
        updateModificationDateEnabled()
        updateOwnerEnabled()
        updateGroupEnabled()
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
            preferredSize = Dimension(550, 430)
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

        panel.add(Box.createVerticalStrut(8))

        // Size filter
        panel.add(sizeCheckBox.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        panel.add(createSizePanel())

        panel.add(Box.createVerticalStrut(8))

        // Creation date filter
        panel.add(creationDateCheckBox.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        panel.add(createDatePanel(creationDateModeCombo, creationDateField1, creationDateField2, creationDateField2Label))

        panel.add(Box.createVerticalStrut(8))

        // Modification date filter
        panel.add(modificationDateCheckBox.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        panel.add(createDatePanel(modificationDateModeCombo, modificationDateField1, modificationDateField2, modificationDateField2Label))

        panel.add(Box.createVerticalStrut(8))

        // Owner & Group filters
        panel.add(JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(2, 4)
            }
            gbc.gridx = 0; gbc.gridy = 0
            add(ownerCheckBox, gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            add(ownerField, gbc)
            gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            add(groupCheckBox, gbc)
            gbc.gridx = 3; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            add(groupField, gbc)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        })

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
                insets = JBUI.insets(2, 4)
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            gbc.gridwidth = 2
            add(nameField, gbc)
            gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            gbc.gridx = 0; gbc.gridy = 1
            add(globRadio, gbc)
            gbc.gridx = 1
            add(regexpRadio, gbc)
            gbc.gridx = 2
            add(caseSensitiveCheckBox, gbc)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun createSizePanel(): JPanel {
        return JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(2, 20, 0, 0)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(2, 4)
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
        return JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(2, 20, 0, 0)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(2, 4)
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

        val owner = if (ownerCheckBox.isSelected && ownerField.text.isNotBlank()) ownerField.text.trim() else null
        val group = if (groupCheckBox.isSelected && groupField.text.isNotBlank()) groupField.text.trim() else null

        return FileSearchCriteria(
            rootPath = Path.of(rootField.text.trim()),
            namePattern = namePattern,
            namePatternMode = nameMode,
            caseSensitive = caseSensitiveCheckBox.isSelected,
            sizeFilter = sizeFilter,
            creationDateFilter = creationDate,
            modificationDateFilter = modificationDate,
            ownerPattern = owner,
            groupPattern = group,
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
        val text1 = field1.text.trim()
        val (start1, end1) = parseDateRange(text1) ?: return null
        val text2 = field2.text.trim()
        val range2 = if (mode == DateFilterMode.IN_BETWEEN) {
            parseDateRange(text2) ?: return null
        } else null
        return DateFilter(mode, start1, end1, range2?.first, range2?.second, text1, if (mode == DateFilterMode.IN_BETWEEN) text2 else null)
    }

    private fun parseDateRange(text: String): Pair<Long, Long>? {
        if (text.isBlank()) return null
        val cal = Calendar.getInstance()
        // yyyy-MM-dd HH:mm
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm")
            sdf.isLenient = false
            val d = sdf.parse(text)
            cal.time = d
            val start = cal.timeInMillis
            cal.add(Calendar.MINUTE, 1)
            cal.add(Calendar.MILLISECOND, -1)
            return Pair(start, cal.timeInMillis)
        } catch (_: Exception) {}
        // yyyy-MM-dd
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd")
            sdf.isLenient = false
            val d = sdf.parse(text)
            cal.time = d
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MILLISECOND, -1)
            return Pair(start, cal.timeInMillis)
        } catch (_: Exception) {}
        // yyyy-MM
        try {
            val sdf = SimpleDateFormat("yyyy-MM")
            sdf.isLenient = false
            val d = sdf.parse(text)
            cal.time = d
            val start = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            cal.add(Calendar.MILLISECOND, -1)
            return Pair(start, cal.timeInMillis)
        } catch (_: Exception) {}
        // yyyy
        try {
            val sdf = SimpleDateFormat("yyyy")
            sdf.isLenient = false
            val d = sdf.parse(text)
            cal.time = d
            val start = cal.timeInMillis
            cal.add(Calendar.YEAR, 1)
            cal.add(Calendar.MILLISECOND, -1)
            return Pair(start, cal.timeInMillis)
        } catch (_: Exception) {}
        return null
    }

    override fun getPreferredFocusedComponent() = nameField
}
