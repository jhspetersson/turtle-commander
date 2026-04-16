package io.github.jhspetersson.turtlecommander.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.installStandardContextMenu
import io.github.jhspetersson.turtlecommander.util.PermissionFlag
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.FileSystems
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*

data class FileSearchCriteria(
    val rootPath: Path,
    val namePattern: String?,
    val namePatternMode: NamePatternMode,
    val caseSensitive: Boolean = false,
    val resultKind: ResultKind = ResultKind.ALL,
    val contentPattern: String? = null,
    val contentCaseSensitive: Boolean = false,
    val sizeFilter: SizeFilter?,
    val creationDateFilter: DateFilter?,
    val modificationDateFilter: DateFilter?,
    val ownerPattern: String? = null,
    val groupPattern: String? = null,
    val permissionsFilter: PermissionsFilter? = null,
)

enum class NamePatternMode { GLOB, REGEXP }

enum class ResultKind { ALL, FILES, DIRS }

data class PermissionsFilter(
    val mode: PermissionsFilterMode,
    val flags: Set<PermissionFlag>,
)

enum class PermissionsFilterMode { EXACT_MATCH, ALL_OF, ANY_OF, NONE_OF }

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
    private val nameCombo = ComboBox<String>().apply {
        isEditable = true
        val regexp = initialCriteria?.namePatternMode == NamePatternMode.REGEXP
        val history = TurtleCommanderSettings.getInstance().getNameSearchHistory(regexp)
        val initialPattern = initialCriteria?.namePattern ?: if (regexp) ".*" else "*"
        val items = linkedSetOf(initialPattern)
        items.addAll(history)
        model = DefaultComboBoxModel(items.toTypedArray())
        selectedItem = initialPattern
    }
    private val nameEditor: JTextField get() = nameCombo.editor.editorComponent as JTextField
    private val nameText: String get() = (nameCombo.editor.item as? String ?: "").trim()
    private val globRadio = JRadioButton("Glob", initialCriteria?.namePatternMode != NamePatternMode.REGEXP)
    private val regexpRadio = JRadioButton("Regexp", initialCriteria?.namePatternMode == NamePatternMode.REGEXP)
    private val caseSensitiveCheckBox = JCheckBox("Case sensitive", initialCriteria?.caseSensitive == true)
    private val resultKindAllRadio = JRadioButton("All", initialCriteria?.resultKind != ResultKind.FILES && initialCriteria?.resultKind != ResultKind.DIRS)
    private val resultKindFilesRadio = JRadioButton("Files", initialCriteria?.resultKind == ResultKind.FILES)
    private val resultKindDirsRadio = JRadioButton("Dirs", initialCriteria?.resultKind == ResultKind.DIRS)

    private val contentCheckBox = JCheckBox("Search by content", initialCriteria?.contentPattern != null)
    private val contentCombo = ComboBox<String>().apply {
        isEditable = true
        val history = TurtleCommanderSettings.getInstance().contentSearchHistory
        val initialPattern = initialCriteria?.contentPattern ?: ""
        val items = linkedSetOf<String>()
        if (initialPattern.isNotEmpty()) items.add(initialPattern)
        items.addAll(history)
        model = DefaultComboBoxModel(items.toTypedArray())
        selectedItem = initialPattern
    }
    private val contentEditor: JTextField get() = contentCombo.editor.editorComponent as JTextField
    private val contentText: String get() = (contentCombo.editor.item as? String ?: "").trim()
    private val contentCaseSensitiveCheckBox = JCheckBox("Case sensitive", initialCriteria?.contentCaseSensitive == true)

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

    private val isHostWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")
    private val permissionFlagsForOs: List<PermissionFlag> =
        if (isHostWindows) PermissionFlag.DOS_FLAGS else PermissionFlag.POSIX_FLAGS
    private val permissionsCheckBox = JCheckBox("Search by permissions", initialCriteria?.permissionsFilter != null)
    private val permissionsModeCombo = ComboBox(DefaultComboBoxModel(arrayOf("Exact match", "All of", "Any of", "None of"))).apply {
        initialCriteria?.permissionsFilter?.let { selectedIndex = it.mode.ordinal }
    }
    private val permissionFlagCheckBoxes: Map<PermissionFlag, JCheckBox> = permissionFlagsForOs.associateWith { flag ->
        JCheckBox(permissionLabel(flag), initialCriteria?.permissionsFilter?.flags?.contains(flag) == true)
    }

    init {
        title = "Search Files"
        setOKButtonText("Search")

        listOf(
            rootField, sizeField1, sizeField2,
            creationDateField1, creationDateField2,
            modificationDateField1, modificationDateField2,
            ownerField, groupField,
        ).forEach { it.installStandardContextMenu() }
        nameEditor.installStandardContextMenu()
        contentEditor.installStandardContextMenu()

        init()

        setupEnabling()
        setupSizeInBetween()
        setupDateInBetween()
        setupNameHistorySwap()
    }

    /**
     * Glob and regex patterns live in separate MRU histories, so when the user toggles the
     * Glob/Regexp radio we rebuild [nameCombo]'s dropdown from the matching history. The
     * currently typed text is preserved so the user doesn't lose in-progress edits when they
     * realise they picked the wrong mode.
     */
    private fun setupNameHistorySwap() {
        fun swap() {
            val current = nameText
            val regexp = regexpRadio.isSelected
            val history = TurtleCommanderSettings.getInstance().getNameSearchHistory(regexp)
            val items = linkedSetOf<String>()
            if (current.isNotEmpty()) items.add(current)
            items.addAll(history)
            if (items.isEmpty()) items.add(if (regexp) ".*" else "*")
            nameCombo.model = DefaultComboBoxModel(items.toTypedArray())
            // Restore the editor text rather than letting the new model's first item win.
            nameCombo.selectedItem = current.ifEmpty { items.first() }
        }
        globRadio.addActionListener { swap(); nameEditor.requestFocusInWindow() }
        regexpRadio.addActionListener { swap(); nameEditor.requestFocusInWindow() }
        caseSensitiveCheckBox.addActionListener { nameEditor.requestFocusInWindow() }
        resultKindAllRadio.addActionListener { nameEditor.requestFocusInWindow() }
        resultKindFilesRadio.addActionListener { nameEditor.requestFocusInWindow() }
        resultKindDirsRadio.addActionListener { nameEditor.requestFocusInWindow() }
    }

    private fun setupEnabling() {
        fun updateNameEnabled() {
            val enabled = nameCheckBox.isSelected
            nameCombo.isEnabled = enabled
            globRadio.isEnabled = enabled
            regexpRadio.isEnabled = enabled
            caseSensitiveCheckBox.isEnabled = enabled
            resultKindAllRadio.isEnabled = enabled
            resultKindFilesRadio.isEnabled = enabled
            resultKindDirsRadio.isEnabled = enabled
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

        fun updateContentEnabled() {
            val enabled = contentCheckBox.isSelected
            contentCombo.isEnabled = enabled
            contentCaseSensitiveCheckBox.isEnabled = enabled
        }

        fun updateOwnerEnabled() { ownerField.isEnabled = ownerCheckBox.isSelected }
        fun updateGroupEnabled() { groupField.isEnabled = groupCheckBox.isSelected }
        fun updatePermissionsEnabled() {
            val enabled = permissionsCheckBox.isSelected
            permissionsModeCombo.isEnabled = enabled
            permissionFlagCheckBoxes.values.forEach { it.isEnabled = enabled }
        }

        nameCheckBox.addActionListener { updateNameEnabled(); if (nameCheckBox.isSelected) nameEditor.requestFocusInWindow() }
        contentCheckBox.addActionListener { updateContentEnabled(); if (contentCheckBox.isSelected) contentEditor.requestFocusInWindow() }
        contentCaseSensitiveCheckBox.addActionListener { contentEditor.requestFocusInWindow() }
        sizeCheckBox.addActionListener { updateSizeEnabled(); if (sizeCheckBox.isSelected) sizeField1.requestFocusInWindow() }
        creationDateCheckBox.addActionListener { updateCreationDateEnabled(); if (creationDateCheckBox.isSelected) creationDateField1.requestFocusInWindow() }
        modificationDateCheckBox.addActionListener { updateModificationDateEnabled(); if (modificationDateCheckBox.isSelected) modificationDateField1.requestFocusInWindow() }
        ownerCheckBox.addActionListener { updateOwnerEnabled(); if (ownerCheckBox.isSelected) ownerField.requestFocusInWindow() }
        groupCheckBox.addActionListener { updateGroupEnabled(); if (groupCheckBox.isSelected) groupField.requestFocusInWindow() }
        permissionsCheckBox.addActionListener { updatePermissionsEnabled() }

        updateNameEnabled()
        updateContentEnabled()
        updateSizeEnabled()
        updateCreationDateEnabled()
        updateModificationDateEnabled()
        updateOwnerEnabled()
        updateGroupEnabled()
        updatePermissionsEnabled()
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
        // POSIX permissions add an Owner/Group/Others legend row under the checkboxes, so the
        // dialog needs a bit more vertical room than on Windows.
        val extraHeight = if (isHostWindows) 0 else 20
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            minimumSize = Dimension(550, 420 + extraHeight)
            preferredSize = Dimension(550, 570 + extraHeight)
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

        // Content filter
        panel.add(contentCheckBox.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        panel.add(createContentPanel())

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

        // Owner & Group filters — keep the section checkbox flush with the other section
        // checkboxes (name, size, dates, permissions) by zeroing the horizontal inset on the
        // first column; the field/group checkbox/field still get padded.
        panel.add(JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(2, 4)
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.insets = JBUI.insets(2, 0, 2, 4)
            add(ownerCheckBox, gbc)
            gbc.insets = JBUI.insets(2, 4)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            add(ownerField, gbc)
            gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            add(groupCheckBox, gbc)
            gbc.gridx = 3; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            add(groupField, gbc)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        })

        panel.add(Box.createVerticalStrut(8))

        // Permissions filter
        panel.add(permissionsCheckBox.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        panel.add(createPermissionsPanel())

        return panel
    }

    private fun createPermissionsPanel(): JPanel {
        return JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(2, 20, 0, 0)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(2, 4)
            }
            gbc.gridx = 0; gbc.gridy = 0
            add(permissionsModeCombo, gbc)

            // DOS: 4 flags in one row. POSIX: 9 flags in one row with short r/w/x labels and
            // an Owner/Group/Others legend underneath each triplet.
            if (isHostWindows) {
                var col = 1
                for (flag in permissionFlagsForOs) {
                    gbc.gridx = col++
                    add(permissionFlagCheckBoxes.getValue(flag), gbc)
                }
            } else {
                val flagsPanel = JPanel(GridBagLayout())
                val fg = GridBagConstraints().apply {
                    anchor = GridBagConstraints.CENTER
                    insets = JBUI.insets(2, 4)
                }
                val triplets = listOf(
                    "Owner" to Triple(PermissionFlag.OWNER_READ, PermissionFlag.OWNER_WRITE, PermissionFlag.OWNER_EXECUTE),
                    "Group" to Triple(PermissionFlag.GROUP_READ, PermissionFlag.GROUP_WRITE, PermissionFlag.GROUP_EXECUTE),
                    "Others" to Triple(PermissionFlag.OTHERS_READ, PermissionFlag.OTHERS_WRITE, PermissionFlag.OTHERS_EXECUTE),
                )
                for ((groupIdx, entry) in triplets.withIndex()) {
                    val baseCol = groupIdx * 3
                    fg.gridy = 0
                    fg.gridwidth = 1
                    fg.fill = GridBagConstraints.NONE
                    fg.gridx = baseCol
                    flagsPanel.add(permissionFlagCheckBoxes.getValue(entry.second.first), fg)
                    fg.gridx = baseCol + 1
                    flagsPanel.add(permissionFlagCheckBoxes.getValue(entry.second.second), fg)
                    fg.gridx = baseCol + 2
                    flagsPanel.add(permissionFlagCheckBoxes.getValue(entry.second.third), fg)

                    fg.gridy = 1
                    fg.gridx = baseCol
                    fg.gridwidth = 3
                    fg.fill = GridBagConstraints.HORIZONTAL
                    flagsPanel.add(JBLabel(entry.first, SwingConstants.CENTER), fg)
                }
                gbc.gridx = 1
                add(flagsPanel, gbc)
            }
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun permissionLabel(flag: PermissionFlag): String = when (flag) {
        PermissionFlag.OWNER_READ, PermissionFlag.GROUP_READ, PermissionFlag.OTHERS_READ -> "r"
        PermissionFlag.OWNER_WRITE, PermissionFlag.GROUP_WRITE, PermissionFlag.OTHERS_WRITE -> "w"
        PermissionFlag.OWNER_EXECUTE, PermissionFlag.GROUP_EXECUTE, PermissionFlag.OTHERS_EXECUTE -> "x"
        PermissionFlag.DOS_READ_ONLY -> "Read-only"
        PermissionFlag.DOS_HIDDEN -> "Hidden"
        PermissionFlag.DOS_SYSTEM -> "System"
        PermissionFlag.DOS_ARCHIVE -> "Archive"
    }

    private fun createNamePanel(): JPanel {
        val group = ButtonGroup()
        group.add(globRadio)
        group.add(regexpRadio)

        val resultKindGroup = ButtonGroup()
        resultKindGroup.add(resultKindAllRadio)
        resultKindGroup.add(resultKindFilesRadio)
        resultKindGroup.add(resultKindDirsRadio)
        val resultKindPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(resultKindAllRadio)
            add(resultKindFilesRadio)
            add(resultKindDirsRadio)
        }

        return JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(2, 20, 0, 0)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(2, 4)
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            gbc.gridwidth = 4
            add(nameCombo, gbc)
            gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            gbc.gridx = 0; gbc.gridy = 1
            add(globRadio, gbc)
            gbc.gridx = 1
            add(regexpRadio, gbc)
            gbc.gridx = 2
            add(caseSensitiveCheckBox, gbc)
            // Push the All/Files/Dirs radio group to the right edge of the row. fill=NONE keeps
            // the panel at its preferred width so anchor=EAST has room to push against.
            gbc.gridx = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 1.0
            gbc.anchor = GridBagConstraints.EAST
            add(resultKindPanel, gbc)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun createContentPanel(): JPanel {
        return JPanel(GridBagLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(2, 20, 0, 0)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(2, 4)
            }
            gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            add(contentCombo, gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            add(contentCaseSensitiveCheckBox, gbc)
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

    /**
     * Validates the name pattern before the dialog closes. Called by [DialogWrapper] when the
     * user clicks Search (and continuously by [startTrackingValidation]). A non-null return keeps
     * the dialog open, highlights the returned component with a tooltip, and focuses it — so an
     * invalid glob/regex is caught immediately instead of surfacing later in the search results
     * tab, by which time the input form is already gone.
     */
    override fun doValidate(): ValidationInfo? {
        if (!nameCheckBox.isSelected) return null
        val pattern = nameText
        if (pattern.isBlank()) return null
        return if (regexpRadio.isSelected) {
            try {
                pattern.toRegex()
                null
            } catch (e: Exception) {
                ValidationInfo("Invalid regex: ${e.message ?: pattern}", nameEditor)
            }
        } else {
            try {
                FileSystems.getDefault().getPathMatcher("glob:$pattern")
                null
            } catch (e: Exception) {
                ValidationInfo("Invalid glob: ${e.message ?: pattern}", nameEditor)
            }
        }
    }

    override fun doOKAction() {
        // Only persist to the MRU history once validation has passed, so invalid patterns the
        // user fat-fingered never leak into the history dropdown. Glob and regex have separate
        // histories so the dropdown always matches the selected mode.
        val namePattern = if (nameCheckBox.isSelected && nameText.isNotBlank()) nameText else null
        if (namePattern != null) {
            TurtleCommanderSettings.getInstance()
                .addNameSearchHistory(namePattern, regexp = regexpRadio.isSelected)
        }
        val contentPattern = if (contentCheckBox.isSelected && contentText.isNotBlank()) contentText else null
        if (contentPattern != null) {
            TurtleCommanderSettings.getInstance().addContentSearchHistory(contentPattern)
        }
        super.doOKAction()
    }

    fun getCriteria(): FileSearchCriteria {
        val namePattern = if (nameCheckBox.isSelected && nameText.isNotBlank()) nameText else null
        val nameMode = if (regexpRadio.isSelected) NamePatternMode.REGEXP else NamePatternMode.GLOB
        val contentPattern = if (contentCheckBox.isSelected && contentText.isNotBlank()) contentText else null

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

        val permissions = if (permissionsCheckBox.isSelected) {
            val mode = PermissionsFilterMode.entries[permissionsModeCombo.selectedIndex]
            val selectedFlags = permissionFlagCheckBoxes.entries
                .filter { it.value.isSelected }
                .mapTo(mutableSetOf()) { it.key }
            PermissionsFilter(mode, selectedFlags)
        } else null

        return FileSearchCriteria(
            rootPath = Path.of(rootField.text.trim()),
            namePattern = namePattern,
            namePatternMode = nameMode,
            caseSensitive = caseSensitiveCheckBox.isSelected,
            resultKind = when {
                resultKindFilesRadio.isSelected -> ResultKind.FILES
                resultKindDirsRadio.isSelected -> ResultKind.DIRS
                else -> ResultKind.ALL
            },
            contentPattern = contentPattern,
            contentCaseSensitive = contentCaseSensitiveCheckBox.isSelected,
            sizeFilter = sizeFilter,
            creationDateFilter = creationDate,
            modificationDateFilter = modificationDate,
            ownerPattern = owner,
            groupPattern = group,
            permissionsFilter = permissions,
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

    override fun getPreferredFocusedComponent() = nameEditor
}
