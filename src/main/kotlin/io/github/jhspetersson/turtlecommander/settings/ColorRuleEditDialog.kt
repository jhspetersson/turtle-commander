package io.github.jhspetersson.turtlecommander.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.ui.installStandardContextMenu
import java.awt.*
import java.awt.font.TextAttribute
import java.util.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Full-rule editor. The matcher table is edited via [ColorMatcherEditDialog];
 * styling uses the same [ColorPickerButton] as the theme editor so visuals
 * stay consistent across the settings surface.
 */
internal class ColorRuleEditDialog(
    private val project: Project?,
    initial: ColorRule?,
) : DialogWrapper(project) {

    private val isNew = initial == null
    private val id: String = initial?.id ?: UUID.randomUUID().toString()

    private val nameField = JBTextField(24)
    private val prioritySpinner = JSpinner(SpinnerNumberModel(0, -1_000_000, 1_000_000, 1))
    private val activeCheck = JCheckBox("Active")
    private val combinatorCombo = ComboBox(DefaultComboBoxModel(Combinator.entries.toTypedArray())).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = when (value) {
                    Combinator.AND -> "All matchers must match (AND)"
                    Combinator.OR -> "Any matcher matches (OR)"
                    else -> value?.toString() ?: ""
                }
                return c
            }
        }
    }

    private val matchers = mutableListOf<RuleMatcher>()
    private val matcherModel = MatcherTableModel()
    private val matcherTable = JBTable(matcherModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        // Select whole rows, not individual cells, so the highlight spans the row.
        rowSelectionAllowed = true
        columnSelectionAllowed = false
        rowHeight = JBUI.scale(22)
        tableHeader.reorderingAllowed = false
        // Drop the per-cell focus rectangle; otherwise the focused cell reads as a
        // single selected cell rather than the whole selected row.
        val noFocusRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): Component = super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
        }
        columnModel.getColumn(0).apply { preferredWidth = JBUI.scale(120); cellRenderer = noFocusRenderer }
        columnModel.getColumn(1).apply { preferredWidth = JBUI.scale(420); cellRenderer = noFocusRenderer }
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && rowAtPoint(e.point) >= 0) editSelectedMatcher()
            }
        })
    }

    // Styling
    private val fontColorButton = ColorPickerButton("...")
    private val bgColorButton = ColorPickerButton("...")
    private val dotColorButton = ColorPickerButton("...")
    private val iconPickerButton = IconPickerButton()
    private val fontStyleCombo = ComboBox(DefaultComboBoxModel(arrayOf("Default", "Plain", "Bold", "Italic", "Bold Italic")))
    private val fontSizeSpinner = JSpinner(SpinnerNumberModel(0, 0, 72, 1))
    private val strikethroughCheck = JCheckBox("Strikethrough")
    private val previewLabel = JBLabel("Preview").apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground") ?: JBColor.GRAY),
            BorderFactory.createEmptyBorder(6, 10, 6, 10),
        )
        isOpaque = true
    }

    var result: ColorRule? = null
        private set

    init {
        title = if (isNew) "Add color rule" else "Edit color rule"
        nameField.installStandardContextMenu()
        loadFrom(initial)
        wireLivePreview()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val header = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 4)
        }
        gbc.gridy = 0
        gbc.gridx = 0; header.add(JBLabel("Name:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; header.add(nameField, gbc)
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0

        gbc.gridy = 1
        gbc.gridx = 0; header.add(JBLabel("Priority:"), gbc)
        val prioRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(prioritySpinner.apply { preferredSize = Dimension(80, preferredSize.height) })
            add(Box.createHorizontalStrut(16))
            add(activeCheck)
        }
        gbc.gridx = 1; header.add(prioRow, gbc)

        gbc.gridy = 2
        gbc.gridx = 0; header.add(JBLabel("Combine with:"), gbc)
        gbc.gridx = 1; header.add(combinatorCombo, gbc)

        val matchersPanel = JPanel(BorderLayout(4, 4)).apply {
            border = BorderFactory.createTitledBorder("Matchers")
            add(JScrollPane(matcherTable).apply { preferredSize = Dimension(550, 140) }, BorderLayout.CENTER)
            add(buildMatcherButtons(), BorderLayout.EAST)
        }

        val stylePanel = buildStylePanel()

        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(header)
            add(Box.createVerticalStrut(6))
            add(matchersPanel)
            add(Box.createVerticalStrut(6))
            add(stylePanel)
            add(Box.createVerticalStrut(6))
            add(buildPreviewPanel())
        }
        root.preferredSize = Dimension(640, 560)
        return root
    }

    private fun buildMatcherButtons(): JComponent {
        val addButton = JButton("Add...").apply { addActionListener { addMatcher() } }
        val editButton = JButton("Edit...").apply { addActionListener { editSelectedMatcher() } }
        val deleteButton = JButton("Delete").apply { addActionListener { deleteSelectedMatcher() } }
        val col = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(addButton)
            add(Box.createVerticalStrut(4))
            add(editButton)
            add(Box.createVerticalStrut(4))
            add(deleteButton)
            for (btn in listOf(addButton, editButton, deleteButton)) {
                btn.maximumSize = Dimension(Int.MAX_VALUE, btn.preferredSize.height)
                btn.alignmentX = Component.LEFT_ALIGNMENT
            }
        }
        return col
    }

    private fun buildStylePanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("Style")
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 4)
        }
        gbc.gridy = 0
        gbc.gridx = 0; panel.add(JBLabel("Font color:"), gbc)
        gbc.gridx = 1; panel.add(fontColorButton, gbc)

        gbc.gridy = 1
        gbc.gridx = 0; panel.add(JBLabel("Background:"), gbc)
        gbc.gridx = 1; panel.add(bgColorButton, gbc)

        gbc.gridy = 2
        gbc.gridx = 0; panel.add(JBLabel("Icon dot:"), gbc)
        gbc.gridx = 1; panel.add(dotColorButton, gbc)

        gbc.gridy = 3
        gbc.gridx = 0; panel.add(JBLabel("Icon:"), gbc)
        gbc.gridx = 1
        val iconRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(iconPickerButton)
            add(Box.createHorizontalStrut(6))
            add(JBLabel("(replaces the file-type icon)"))
        }
        panel.add(iconRow, gbc)

        gbc.gridy = 4
        gbc.gridx = 0; panel.add(JBLabel("Font style:"), gbc)
        gbc.gridx = 1; panel.add(fontStyleCombo, gbc)

        gbc.gridy = 5
        gbc.gridx = 0; panel.add(JBLabel("Font size override:"), gbc)
        gbc.gridx = 1
        val sizeRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(fontSizeSpinner.apply { preferredSize = Dimension(60, preferredSize.height) })
            add(Box.createHorizontalStrut(6))
            add(JBLabel("(0 = no override)"))
        }
        panel.add(sizeRow, gbc)

        gbc.gridy = 6
        gbc.gridx = 0; panel.add(JBLabel("Effects:"), gbc)
        gbc.gridx = 1; panel.add(strikethroughCheck, gbc)

        return panel
    }

    private fun buildPreviewPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Preview")
            add(previewLabel, BorderLayout.CENTER)
        }
    }

    private fun wireLivePreview() {
        fontColorButton.onChange = { refreshPreview() }
        bgColorButton.onChange = { refreshPreview() }
        dotColorButton.onChange = { refreshPreview() }
        iconPickerButton.onChange = { refreshPreview() }
        fontStyleCombo.addActionListener { refreshPreview() }
        fontSizeSpinner.addChangeListener { refreshPreview() }
        strikethroughCheck.addActionListener { refreshPreview() }
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { refreshPreview() }
        })
        refreshPreview()
    }

    private fun refreshPreview() {
        val font = previewLabel.font
        val baseSize = font?.size ?: 13
        val styleIdx = fontStyleCombo.selectedIndex
        val swingStyle = when (styleIdx) {
            2 -> Font.BOLD
            3 -> Font.ITALIC
            4 -> Font.BOLD or Font.ITALIC
            1 -> Font.PLAIN
            else -> font?.style ?: Font.PLAIN
        }
        val sizeOverride = (fontSizeSpinner.value as? Number)?.toInt() ?: 0
        val size = if (sizeOverride > 0) sizeOverride else baseSize
        val baseFont = Font(font?.family ?: Font.DIALOG, swingStyle, size)
        previewLabel.font = if (strikethroughCheck.isSelected) {
            baseFont.deriveFont(mapOf(TextAttribute.STRIKETHROUGH to TextAttribute.STRIKETHROUGH_ON))
        } else {
            baseFont
        }
        val fg = fontColorButton.getColorHex()
        val bg = bgColorButton.getColorHex()
        previewLabel.foreground = if (fg.isNotEmpty()) Color.decode(fg) else UIManager.getColor("Label.foreground")
        previewLabel.background = if (bg.isNotEmpty()) Color.decode(bg) else UIManager.getColor("Panel.background")
        previewLabel.icon = RuleIcons.resolve(iconPickerButton.getIconId())
        previewLabel.text = nameField.text.ifBlank { "Preview" }
    }

    private fun loadFrom(rule: ColorRule?) {
        if (rule == null) {
            nameField.text = ""
            prioritySpinner.value = 0
            activeCheck.isSelected = true
            combinatorCombo.selectedItem = Combinator.AND
            matchers.clear()
            iconPickerButton.setIconId("")
            fontStyleCombo.selectedIndex = 0
            strikethroughCheck.isSelected = false
            return
        }
        nameField.text = rule.name
        prioritySpinner.value = rule.priority
        activeCheck.isSelected = rule.active
        combinatorCombo.selectedItem = rule.combinator
        matchers.clear()
        matchers.addAll(rule.matchers)
        matcherModel.fireTableDataChanged()
        fontColorButton.setColor(decodeOrNull(rule.style.fontColor))
        bgColorButton.setColor(decodeOrNull(rule.style.backgroundColor))
        dotColorButton.setColor(decodeOrNull(rule.style.iconDotColor))
        iconPickerButton.setIconId(rule.style.iconId)
        fontStyleCombo.selectedIndex = when (rule.style.fontStyle) {
            null -> 0
            Font.PLAIN -> 1
            Font.BOLD -> 2
            Font.ITALIC -> 3
            Font.BOLD or Font.ITALIC -> 4
            else -> 0
        }
        fontSizeSpinner.value = rule.style.fontSize ?: 0
        strikethroughCheck.isSelected = rule.style.strikethrough == true
    }

    private fun addMatcher() {
        val dialog = ColorMatcherEditDialog(project, null)
        if (dialog.showAndGet()) {
            dialog.result?.let {
                matchers.add(it)
                matcherModel.fireTableDataChanged()
                matcherTable.setRowSelectionInterval(matchers.size - 1, matchers.size - 1)
            }
        }
    }

    private fun editSelectedMatcher() {
        val idx = matcherTable.selectedRow
        if (idx < 0) return
        val dialog = ColorMatcherEditDialog(project, matchers[idx])
        if (dialog.showAndGet()) {
            dialog.result?.let {
                matchers[idx] = it
                matcherModel.fireTableRowsUpdated(idx, idx)
            }
        }
    }

    private fun deleteSelectedMatcher() {
        val idx = matcherTable.selectedRow
        if (idx < 0) return
        matchers.removeAt(idx)
        matcherModel.fireTableDataChanged()
    }

    override fun doOKAction() {
        val name = nameField.text.trim()
        if (name.isEmpty()) {
            setErrorText("Name cannot be empty", nameField)
            return
        }
        if (matchers.isEmpty()) {
            setErrorText("Add at least one matcher", matcherTable)
            return
        }
        result = ColorRule(
            id = id,
            name = name,
            priority = (prioritySpinner.value as Number).toInt(),
            active = activeCheck.isSelected,
            combinator = combinatorCombo.selectedItem as Combinator,
            matchers = matchers.toList(),
            style = RuleStyle(
                fontColor = fontColorButton.getColorHex(),
                backgroundColor = bgColorButton.getColorHex(),
                iconDotColor = dotColorButton.getColorHex(),
                fontStyle = when (fontStyleCombo.selectedIndex) {
                    1 -> Font.PLAIN
                    2 -> Font.BOLD
                    3 -> Font.ITALIC
                    4 -> Font.BOLD or Font.ITALIC
                    else -> null
                },
                fontSize = ((fontSizeSpinner.value as? Number)?.toInt() ?: 0).takeIf { it > 0 },
                strikethrough = true.takeIf { strikethroughCheck.isSelected },
                iconId = iconPickerButton.getIconId(),
            ),
        )
        super.doOKAction()
    }

    private inner class MatcherTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = matchers.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = when (column) {
            0 -> "Type"
            1 -> "Summary"
            else -> ""
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val m = matchers[rowIndex]
            return when (columnIndex) {
                0 -> when (m) {
                    is RuleMatcher.Size -> "Size"
                    is RuleMatcher.Name -> "Name"
                    is RuleMatcher.Contains -> "Contains"
                    is RuleMatcher.Date -> when (m.field) {
                        DateField.CREATED -> "Created"
                        DateField.MODIFIED -> "Modified"
                    }
                    is RuleMatcher.Text -> when (m.field) {
                        TextProperty.OWNER -> "Owner"
                        TextProperty.GROUP -> "Group"
                        TextProperty.PERMISSIONS -> "Permissions"
                    }
                    is RuleMatcher.Symlink -> "Symlink"
                }
                1 -> m.describe()
                else -> ""
            }
        }
    }
}

private fun decodeOrNull(hex: String): Color? {
    if (hex.isBlank()) return null
    return runCatching { Color.decode(hex) }.getOrNull()
}

/** Light swatch cell renderer for previewing colors in the rules table. */
internal class ColorSwatchCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        // Ignore focus so the whole selected row renders uniformly (no single-cell border).
        val c = super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
        text = ""
        icon = when (value) {
            is Color -> ColorSwatchIcon(value)
            is String -> if (value.isBlank()) null else runCatching { ColorSwatchIcon(Color.decode(value)) }.getOrNull()
            else -> null
        }
        return c
    }
}
