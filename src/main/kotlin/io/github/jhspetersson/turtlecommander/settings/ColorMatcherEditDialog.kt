package io.github.jhspetersson.turtlecommander.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.ui.installStandardContextMenu
import java.awt.*
import javax.swing.*

/**
 * Edit a single [RuleMatcher]. The kind switcher flips between three
 * card-layout forms (Size / Name / Contains); on OK the active card's
 * fields are read out into the appropriate matcher subclass.
 */
internal class ColorMatcherEditDialog(
    project: Project?,
    initial: RuleMatcher?,
) : DialogWrapper(project) {

    enum class Kind() {
        SIZE,
        NAME,
        CONTAINS;
        companion object {
            fun from(m: RuleMatcher): Kind = when (m) {
                is RuleMatcher.Size -> SIZE
                is RuleMatcher.Name -> NAME
                is RuleMatcher.Contains -> CONTAINS
            }
        }
    }

    private val kindCombo = ComboBox(DefaultComboBoxModel(Kind.entries.toTypedArray()))

    // Size fields
    private val sizeOpCombo = ComboBox(DefaultComboBoxModel(SizeOp.entries.toTypedArray()))
    private val sizeValueSpinner = JSpinner(SpinnerNumberModel(1L, 0L, Long.MAX_VALUE, 1L))
    private val sizeMaxSpinner = JSpinner(SpinnerNumberModel(1L, 0L, Long.MAX_VALUE, 1L))
    private val sizeUnitCombo = ComboBox(DefaultComboBoxModel(arrayOf("B", "KB", "MB", "GB")))
    private val sizeMaxUnitCombo = ComboBox(DefaultComboBoxModel(arrayOf("B", "KB", "MB", "GB")))
    private val sizeMaxLabel = JBLabel("And ≤:")

    // Name fields
    private val nameKindCombo = ComboBox(DefaultComboBoxModel(PatternKind.entries.toTypedArray()))
    private val namePatternField = JBTextField(24)
    private val nameCaseCheck = JCheckBox("Case sensitive")
    private val nameAppliesCombo = ComboBox(DefaultComboBoxModel(AppliesTo.entries.toTypedArray()))

    // Contains fields
    private val containsKindCombo = ComboBox(DefaultComboBoxModel(PatternKind.entries.toTypedArray()))
    private val containsPatternField = JBTextField(24)
    private val containsCaseCheck = JCheckBox("Case sensitive")

    private val cards = CardLayout()
    private val cardPanel = JPanel(cards)

    var result: RuleMatcher? = null
        private set

    init {
        title = if (initial == null) "Add matcher" else "Edit matcher"
        namePatternField.installStandardContextMenu()
        containsPatternField.installStandardContextMenu()
        loadFrom(initial)
        kindCombo.addActionListener { showSelectedCard() }
        sizeOpCombo.addActionListener { updateSizeVisibility() }
        init()
    }

    override fun createCenterPanel(): JComponent {
        cardPanel.add(buildSizePanel(), Kind.SIZE.name)
        cardPanel.add(buildNamePanel(), Kind.NAME.name)
        cardPanel.add(buildContainsPanel(), Kind.CONTAINS.name)

        val root = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(420, 170)
        }
        val top = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 4)
            gridy = 0
        }
        gbc.gridx = 0; top.add(JBLabel("Match by:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; top.add(kindCombo, gbc)
        root.add(top, BorderLayout.NORTH)
        root.add(cardPanel, BorderLayout.CENTER)
        showSelectedCard()
        return root
    }

    private fun buildSizePanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("Size")
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 4)
        }
        gbc.gridy = 0
        gbc.gridx = 0; panel.add(JBLabel("Operator:"), gbc)
        gbc.gridx = 1; panel.add(sizeOpCombo, gbc)

        gbc.gridy = 1
        gbc.gridx = 0; panel.add(JBLabel("Value:"), gbc)
        val valueRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(sizeValueSpinner.apply { preferredSize = Dimension(120, preferredSize.height) })
            add(Box.createHorizontalStrut(6))
            add(sizeUnitCombo.apply { preferredSize = Dimension(70, preferredSize.height) })
        }
        gbc.gridx = 1; panel.add(valueRow, gbc)

        gbc.gridy = 2
        gbc.gridx = 0; panel.add(sizeMaxLabel, gbc)
        val maxRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(sizeMaxSpinner.apply { preferredSize = Dimension(120, preferredSize.height) })
            add(Box.createHorizontalStrut(6))
            add(sizeMaxUnitCombo.apply { preferredSize = Dimension(70, preferredSize.height) })
        }
        gbc.gridx = 1; panel.add(maxRow, gbc)
        return panel
    }

    private fun buildNamePanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("Name")
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 4)
        }
        gbc.gridy = 0
        gbc.gridx = 0; panel.add(JBLabel("Pattern kind:"), gbc)
        gbc.gridx = 1; panel.add(nameKindCombo, gbc)

        gbc.gridy = 1
        gbc.gridx = 0; panel.add(JBLabel("Pattern:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; panel.add(namePatternField, gbc)
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0

        gbc.gridy = 2
        gbc.gridx = 0; panel.add(JBLabel("Applies to:"), gbc)
        gbc.gridx = 1; panel.add(nameAppliesCombo, gbc)

        gbc.gridy = 3
        gbc.gridx = 1; panel.add(nameCaseCheck, gbc)
        return panel
    }

    private fun buildContainsPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("Directory contains")
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 4)
        }
        gbc.gridy = 0
        gbc.gridx = 0; panel.add(JBLabel("Pattern kind:"), gbc)
        gbc.gridx = 1; panel.add(containsKindCombo, gbc)

        gbc.gridy = 1
        gbc.gridx = 0; panel.add(JBLabel("Pattern:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; panel.add(containsPatternField, gbc)
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0

        gbc.gridy = 2
        gbc.gridx = 1; panel.add(containsCaseCheck, gbc)
        return panel
    }

    private fun showSelectedCard() {
        val kind = kindCombo.selectedItem as Kind
        cards.show(cardPanel, kind.name)
        updateSizeVisibility()
    }

    private fun updateSizeVisibility() {
        val between = (sizeOpCombo.selectedItem as? SizeOp) == SizeOp.BETWEEN
        sizeMaxLabel.isVisible = between
        sizeMaxSpinner.isVisible = between
        sizeMaxUnitCombo.isVisible = between
    }

    private fun loadFrom(matcher: RuleMatcher?) {
        when (matcher) {
            is RuleMatcher.Size -> {
                kindCombo.selectedItem = Kind.SIZE
                sizeOpCombo.selectedItem = matcher.op
                val (value, unit) = splitUnit(matcher.bytes)
                sizeValueSpinner.value = value
                sizeUnitCombo.selectedItem = unit
                val (valueMax, unitMax) = splitUnit(matcher.bytesMax)
                sizeMaxSpinner.value = valueMax
                sizeMaxUnitCombo.selectedItem = unitMax
            }
            is RuleMatcher.Name -> {
                kindCombo.selectedItem = Kind.NAME
                nameKindCombo.selectedItem = matcher.kind
                namePatternField.text = matcher.pattern
                nameCaseCheck.isSelected = matcher.caseSensitive
                nameAppliesCombo.selectedItem = matcher.appliesTo
            }
            is RuleMatcher.Contains -> {
                kindCombo.selectedItem = Kind.CONTAINS
                containsKindCombo.selectedItem = matcher.kind
                containsPatternField.text = matcher.pattern
                containsCaseCheck.isSelected = matcher.caseSensitive
            }
            null -> {
                kindCombo.selectedItem = Kind.CONTAINS
                containsKindCombo.selectedItem = PatternKind.EXACT
                sizeUnitCombo.selectedItem = "MB"
                sizeMaxUnitCombo.selectedItem = "MB"
                nameAppliesCombo.selectedItem = AppliesTo.BOTH
            }
        }
    }

    private fun splitUnit(bytes: Long): Pair<Long, String> {
        if (bytes == 0L) return 0L to "B"
        val gb = 1024L * 1024 * 1024
        val mb = 1024L * 1024
        val kb = 1024L
        return when {
            bytes % gb == 0L -> (bytes / gb) to "GB"
            bytes % mb == 0L -> (bytes / mb) to "MB"
            bytes % kb == 0L -> (bytes / kb) to "KB"
            else -> bytes to "B"
        }
    }

    private fun toBytes(value: Long, unit: String): Long = when (unit) {
        "KB" -> value * 1024
        "MB" -> value * 1024 * 1024
        "GB" -> value * 1024 * 1024 * 1024
        else -> value
    }

    override fun doOKAction() {
        result = when (kindCombo.selectedItem as Kind) {
            Kind.SIZE -> {
                val op = sizeOpCombo.selectedItem as SizeOp
                val bytes = toBytes((sizeValueSpinner.value as Number).toLong(), sizeUnitCombo.selectedItem as String)
                val bytesMax = if (op == SizeOp.BETWEEN) {
                    toBytes((sizeMaxSpinner.value as Number).toLong(), sizeMaxUnitCombo.selectedItem as String)
                } else 0L
                RuleMatcher.Size(op = op, bytes = bytes, bytesMax = bytesMax)
            }
            Kind.NAME -> {
                val pattern = namePatternField.text.trim()
                if (pattern.isEmpty()) {
                    setErrorText("Pattern cannot be empty", namePatternField)
                    return
                }
                RuleMatcher.Name(
                    kind = nameKindCombo.selectedItem as PatternKind,
                    pattern = pattern,
                    caseSensitive = nameCaseCheck.isSelected,
                    appliesTo = nameAppliesCombo.selectedItem as AppliesTo,
                )
            }
            Kind.CONTAINS -> {
                val pattern = containsPatternField.text.trim()
                if (pattern.isEmpty()) {
                    setErrorText("Pattern cannot be empty", containsPatternField)
                    return
                }
                RuleMatcher.Contains(
                    kind = containsKindCombo.selectedItem as PatternKind,
                    pattern = pattern,
                    caseSensitive = containsCaseCheck.isSelected,
                )
            }
        }
        super.doOKAction()
    }
}

internal fun RuleMatcher.describe(): String = when (this) {
    is RuleMatcher.Size -> when (op) {
        SizeOp.LT -> "size < ${humanBytes(bytes)}"
        SizeOp.LE -> "size ≤ ${humanBytes(bytes)}"
        SizeOp.EQ -> "size = ${humanBytes(bytes)}"
        SizeOp.GE -> "size ≥ ${humanBytes(bytes)}"
        SizeOp.GT -> "size > ${humanBytes(bytes)}"
        SizeOp.BETWEEN -> "${humanBytes(bytes)} ≤ size ≤ ${humanBytes(bytesMax)}"
    }
    is RuleMatcher.Name -> {
        val kindStr = when (kind) { PatternKind.EXACT -> "="; PatternKind.GLOB -> "glob"; PatternKind.REGEX -> "regex" }
        val scope = when (appliesTo) { AppliesTo.FILE -> " (files)"; AppliesTo.DIR -> " (dirs)"; AppliesTo.BOTH -> "" }
        val case = if (caseSensitive) ", Aa" else ""
        "name $kindStr \"$pattern\"$scope$case"
    }
    is RuleMatcher.Contains -> {
        val kindStr = when (kind) { PatternKind.EXACT -> "="; PatternKind.GLOB -> "glob"; PatternKind.REGEX -> "regex" }
        val case = if (caseSensitive) ", Aa" else ""
        "contains $kindStr \"$pattern\"$case"
    }
}

private fun humanBytes(b: Long): String {
    val gb = 1024L * 1024 * 1024
    val mb = 1024L * 1024
    val kb = 1024L
    return when {
        b >= gb && b % gb == 0L -> "${b / gb} GB"
        b >= mb && b % mb == 0L -> "${b / mb} MB"
        b >= kb && b % kb == 0L -> "${b / kb} KB"
        else -> "$b B"
    }
}
