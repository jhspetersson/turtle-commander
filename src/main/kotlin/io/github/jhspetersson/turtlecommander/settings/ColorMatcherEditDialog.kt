package io.github.jhspetersson.turtlecommander.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.ui.installStandardContextMenu
import java.awt.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import javax.swing.*
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Edit a single [RuleMatcher]. The kind switcher flips between card-layout forms
 * (Size / Name / Created / Modified / Owner / Group / Permissions / Directory contains);
 * on OK the active card's fields are read out into the appropriate matcher subclass.
 * Created and Modified share one date card; Owner, Group and Permissions share one text card.
 */
internal class ColorMatcherEditDialog(
    project: Project?,
    initial: RuleMatcher?,
) : DialogWrapper(project) {

    enum class Kind {
        SIZE,
        NAME,
        CREATED,
        MODIFIED,
        OWNER,
        GROUP,
        PERMISSIONS,
        CONTAINS,
        SYMLINK;

        /** The card-layout key this kind displays — several kinds share a card. */
        fun cardName(): String = when (this) {
            SIZE -> "SIZE"
            NAME -> "NAME"
            CREATED, MODIFIED -> "DATE"
            OWNER, GROUP, PERMISSIONS -> "TEXT"
            CONTAINS -> "CONTAINS"
            SYMLINK -> "SYMLINK"
        }

        companion object {
            fun from(m: RuleMatcher): Kind = when (m) {
                is RuleMatcher.Size -> SIZE
                is RuleMatcher.Name -> NAME
                is RuleMatcher.Contains -> CONTAINS
                is RuleMatcher.Symlink -> SYMLINK
                is RuleMatcher.Date -> when (m.field) {
                    DateField.CREATED -> CREATED
                    DateField.MODIFIED -> MODIFIED
                }
                is RuleMatcher.Text -> when (m.field) {
                    TextProperty.OWNER -> OWNER
                    TextProperty.GROUP -> GROUP
                    TextProperty.PERMISSIONS -> PERMISSIONS
                }
            }
        }
    }

    private val kindCombo = ComboBox(DefaultComboBoxModel(Kind.entries.toTypedArray())).apply {
        renderer = labelRenderer { (it as? Kind)?.label() ?: it?.toString().orEmpty() }
    }

    // Size fields
    private val sizeOpCombo = ComboBox(DefaultComboBoxModel(SizeOp.entries.toTypedArray())).apply {
        renderer = labelRenderer { (it as? SizeOp)?.label() ?: it?.toString().orEmpty() }
    }
    private val sizeValueSpinner = JSpinner(SpinnerNumberModel(1L, 0L, Long.MAX_VALUE, 1L))
    private val sizeMaxSpinner = JSpinner(SpinnerNumberModel(1L, 0L, Long.MAX_VALUE, 1L))
    private val sizeUnitCombo = ComboBox(DefaultComboBoxModel(arrayOf("B", "KB", "MB", "GB")))
    private val sizeMaxUnitCombo = ComboBox(DefaultComboBoxModel(arrayOf("B", "KB", "MB", "GB")))
    private val sizeMaxLabel = JBLabel("And ≤:")

    // Name fields
    private val nameKindCombo = ComboBox(DefaultComboBoxModel(PatternKind.entries.toTypedArray())).apply {
        renderer = labelRenderer { (it as? PatternKind)?.label() ?: it?.toString().orEmpty() }
    }
    private val namePatternField = JBTextField(24)
    private val nameCaseCheck = JCheckBox("Case sensitive")
    private val nameAppliesCombo = ComboBox(DefaultComboBoxModel(AppliesTo.entries.toTypedArray())).apply {
        renderer = labelRenderer { (it as? AppliesTo)?.label() ?: it?.toString().orEmpty() }
    }

    // Contains fields
    private val containsKindCombo = ComboBox(DefaultComboBoxModel(PatternKind.entries.toTypedArray())).apply {
        renderer = labelRenderer { (it as? PatternKind)?.label() ?: it?.toString().orEmpty() }
    }
    private val containsPatternField = JBTextField(24)
    private val containsCaseCheck = JCheckBox("Case sensitive")

    // Date fields (shared by Created / Modified)
    private val dateOpCombo = ComboBox(DefaultComboBoxModel(DateOp.entries.toTypedArray())).apply {
        renderer = labelRenderer { (it as? DateOp)?.label() ?: it?.toString().orEmpty() }
    }
    private val dateValueLabel = JBLabel("Date:")
    private val dateValueField = JBTextField(12)
    private val dateMaxLabel = JBLabel("And through:")
    private val dateMaxField = JBTextField(12)
    private val dateAmountLabel = JBLabel("Amount:")
    private val dateAmountSpinner = JSpinner(SpinnerNumberModel(7L, 1L, Long.MAX_VALUE, 1L))
    private val dateUnitCombo = ComboBox(DefaultComboBoxModel(DateUnit.entries.toTypedArray())).apply {
        renderer = labelRenderer { (it as? DateUnit)?.label() ?: it?.toString().orEmpty() }
    }

    // Text fields (shared by Owner / Group / Permissions)
    private val textKindCombo = ComboBox(DefaultComboBoxModel(PatternKind.entries.toTypedArray())).apply {
        renderer = labelRenderer { (it as? PatternKind)?.label() ?: it?.toString().orEmpty() }
    }
    private val textPatternField = JBTextField(24)
    private val textCaseCheck = JCheckBox("Case sensitive")

    // Symlink field
    private val symlinkStateCombo = ComboBox(DefaultComboBoxModel(SymlinkState.entries.toTypedArray())).apply {
        renderer = labelRenderer { (it as? SymlinkState)?.label() ?: it?.toString().orEmpty() }
    }

    private val cards = CardLayout()
    private val cardPanel = JPanel(cards)

    var result: RuleMatcher? = null
        private set

    init {
        title = if (initial == null) "Add matcher" else "Edit matcher"
        namePatternField.installStandardContextMenu()
        containsPatternField.installStandardContextMenu()
        textPatternField.installStandardContextMenu()
        dateValueField.installStandardContextMenu()
        dateMaxField.installStandardContextMenu()
        loadFrom(initial)
        kindCombo.addActionListener { showSelectedCard() }
        sizeOpCombo.addActionListener { updateSizeVisibility() }
        dateOpCombo.addActionListener { updateDateVisibility() }

        // After the user picks a matching operator from a dropdown, jump straight to its value field.
        sizeOpCombo.focusNextOnSelect { sizeValueSpinner.editorField() }
        dateOpCombo.focusNextOnSelect {
            when {
                dateValueField.isVisible -> dateValueField
                dateAmountSpinner.isVisible -> dateAmountSpinner.editorField()
                else -> null
            }
        }
        nameKindCombo.focusNextOnSelect { namePatternField }
        containsKindCombo.focusNextOnSelect { containsPatternField }
        textKindCombo.focusNextOnSelect { textPatternField }

        init()
    }

    override fun getPreferredFocusedComponent(): JComponent = primaryInput()

    /** The editable value field of the currently selected card, so focus lands on input, not the kind combo. */
    private fun primaryInput(): JComponent = when (kindCombo.selectedItem as Kind) {
        Kind.SIZE -> sizeValueSpinner.editorField()
        Kind.NAME -> namePatternField
        Kind.CONTAINS -> containsPatternField
        Kind.OWNER, Kind.GROUP, Kind.PERMISSIONS -> textPatternField
        Kind.CREATED, Kind.MODIFIED -> when {
            dateValueField.isVisible -> dateValueField
            dateAmountSpinner.isVisible -> dateAmountSpinner.editorField()
            else -> dateOpCombo
        }
        Kind.SYMLINK -> symlinkStateCombo
    }

    override fun createCenterPanel(): JComponent {
        cardPanel.add(buildSizePanel(), "SIZE")
        cardPanel.add(buildNamePanel(), "NAME")
        cardPanel.add(buildDatePanel(), "DATE")
        cardPanel.add(buildTextPanel(), "TEXT")
        cardPanel.add(buildContainsPanel(), "CONTAINS")
        cardPanel.add(buildSymlinkPanel(), "SYMLINK")

        val root = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(440, 210)
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

    private fun buildDatePanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("Date")
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 4)
        }
        gbc.gridy = 0
        gbc.gridx = 0; panel.add(JBLabel("Operator:"), gbc)
        gbc.gridx = 1; panel.add(dateOpCombo, gbc)

        gbc.gridy = 1
        gbc.gridx = 0; panel.add(dateValueLabel, gbc)
        gbc.gridx = 1; panel.add(dateValueField, gbc)

        gbc.gridy = 2
        gbc.gridx = 0; panel.add(dateMaxLabel, gbc)
        gbc.gridx = 1; panel.add(dateMaxField, gbc)

        gbc.gridy = 3
        gbc.gridx = 0; panel.add(dateAmountLabel, gbc)
        val amountRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(dateAmountSpinner.apply { preferredSize = Dimension(90, preferredSize.height) })
            add(Box.createHorizontalStrut(6))
            add(dateUnitCombo)
        }
        gbc.gridx = 1; panel.add(amountRow, gbc)

        gbc.gridy = 4
        gbc.gridx = 1; panel.add(JBLabel("Dates are YYYY-MM-DD").apply { foreground = Color.GRAY }, gbc)
        return panel
    }

    private fun buildTextPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("Value")
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 4)
        }
        gbc.gridy = 0
        gbc.gridx = 0; panel.add(JBLabel("Pattern kind:"), gbc)
        gbc.gridx = 1; panel.add(textKindCombo, gbc)

        gbc.gridy = 1
        gbc.gridx = 0; panel.add(JBLabel("Pattern:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; panel.add(textPatternField, gbc)
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0

        gbc.gridy = 2
        gbc.gridx = 1; panel.add(textCaseCheck, gbc)

        gbc.gridy = 3
        gbc.gridx = 1
        panel.add(JBLabel("e.g. perms glob *w* or owner = root").apply { foreground = Color.GRAY }, gbc)
        return panel
    }

    private fun buildSymlinkPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("Symbolic link")
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 4)
        }
        gbc.gridy = 0
        gbc.gridx = 0; panel.add(JBLabel("State:"), gbc)
        gbc.gridx = 1; panel.add(symlinkStateCombo, gbc)

        gbc.gridy = 1
        gbc.gridx = 1
        panel.add(JBLabel("Matches symlinks; \"broken\" = target missing").apply { foreground = Color.GRAY }, gbc)
        return panel
    }

    private fun showSelectedCard() {
        val kind = kindCombo.selectedItem as Kind
        cards.show(cardPanel, kind.cardName())
        updateSizeVisibility()
        updateDateVisibility()
    }

    private fun updateSizeVisibility() {
        val between = (sizeOpCombo.selectedItem as? SizeOp) == SizeOp.BETWEEN
        sizeMaxLabel.isVisible = between
        sizeMaxSpinner.isVisible = between
        sizeMaxUnitCombo.isVisible = between
    }

    private fun updateDateVisibility() {
        val op = dateOpCombo.selectedItem as? DateOp ?: return
        val absolute = op == DateOp.BEFORE || op == DateOp.AFTER || op == DateOp.BETWEEN
        val between = op == DateOp.BETWEEN
        val relative = op == DateOp.WITHIN_LAST || op == DateOp.OLDER_THAN
        dateValueLabel.isVisible = absolute
        dateValueField.isVisible = absolute
        dateMaxLabel.isVisible = between
        dateMaxField.isVisible = between
        dateAmountLabel.isVisible = relative
        dateAmountSpinner.isVisible = relative
        dateUnitCombo.isVisible = relative
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
            is RuleMatcher.Date -> {
                kindCombo.selectedItem = when (matcher.field) {
                    DateField.CREATED -> Kind.CREATED
                    DateField.MODIFIED -> Kind.MODIFIED
                }
                dateOpCombo.selectedItem = matcher.op
                dateValueField.text = if (matcher.epochMillis != 0L) millisToIsoDate(matcher.epochMillis) else ""
                dateMaxField.text = if (matcher.epochMillisMax != 0L) millisToIsoDate(matcher.epochMillisMax) else ""
                dateAmountSpinner.value = if (matcher.amount > 0L) matcher.amount else 7L
                dateUnitCombo.selectedItem = matcher.unit
            }
            is RuleMatcher.Text -> {
                kindCombo.selectedItem = when (matcher.field) {
                    TextProperty.OWNER -> Kind.OWNER
                    TextProperty.GROUP -> Kind.GROUP
                    TextProperty.PERMISSIONS -> Kind.PERMISSIONS
                }
                textKindCombo.selectedItem = matcher.kind
                textPatternField.text = matcher.pattern
                textCaseCheck.isSelected = matcher.caseSensitive
            }
            is RuleMatcher.Symlink -> {
                kindCombo.selectedItem = Kind.SYMLINK
                symlinkStateCombo.selectedItem = matcher.state
            }
            null -> {
                kindCombo.selectedItem = Kind.CONTAINS
                containsKindCombo.selectedItem = PatternKind.EXACT
                sizeUnitCombo.selectedItem = "MB"
                sizeMaxUnitCombo.selectedItem = "MB"
                nameAppliesCombo.selectedItem = AppliesTo.BOTH
                dateOpCombo.selectedItem = DateOp.WITHIN_LAST
                dateUnitCombo.selectedItem = DateUnit.DAYS
                textKindCombo.selectedItem = PatternKind.EXACT
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
            Kind.CREATED -> buildDateMatcher(DateField.CREATED) ?: return
            Kind.MODIFIED -> buildDateMatcher(DateField.MODIFIED) ?: return
            Kind.OWNER -> buildTextMatcher(TextProperty.OWNER) ?: return
            Kind.GROUP -> buildTextMatcher(TextProperty.GROUP) ?: return
            Kind.PERMISSIONS -> buildTextMatcher(TextProperty.PERMISSIONS) ?: return
            Kind.SYMLINK -> RuleMatcher.Symlink(state = symlinkStateCombo.selectedItem as SymlinkState)
        }
        super.doOKAction()
    }

    /** Reads the date card into a matcher, or shows an error and returns null. */
    private fun buildDateMatcher(field: DateField): RuleMatcher.Date? {
        return when (val op = dateOpCombo.selectedItem as DateOp) {
            DateOp.BEFORE, DateOp.AFTER -> {
                val millis = parseIsoDate(dateValueField)?.let { startOfDayMillis(it) } ?: return null
                RuleMatcher.Date(field = field, op = op, epochMillis = millis)
            }
            DateOp.BETWEEN -> {
                val lo = parseIsoDate(dateValueField)?.let { startOfDayMillis(it) } ?: return null
                val hi = parseIsoDate(dateMaxField)?.let { endOfDayMillis(it) } ?: return null
                if (hi < lo) {
                    setErrorText("End date is before start date", dateMaxField)
                    return null
                }
                RuleMatcher.Date(field = field, op = op, epochMillis = lo, epochMillisMax = hi)
            }
            DateOp.WITHIN_LAST, DateOp.OLDER_THAN -> {
                val amount = (dateAmountSpinner.value as Number).toLong()
                if (amount <= 0L) {
                    setErrorText("Amount must be positive", dateAmountSpinner)
                    return null
                }
                RuleMatcher.Date(field = field, op = op, amount = amount, unit = dateUnitCombo.selectedItem as DateUnit)
            }
        }
    }

    /** Reads the text card into a matcher, or shows an error and returns null. */
    private fun buildTextMatcher(field: TextProperty): RuleMatcher.Text? {
        val pattern = textPatternField.text.trim()
        if (pattern.isEmpty()) {
            setErrorText("Pattern cannot be empty", textPatternField)
            return null
        }
        return RuleMatcher.Text(
            field = field,
            kind = textKindCombo.selectedItem as PatternKind,
            pattern = pattern,
            caseSensitive = textCaseCheck.isSelected,
        )
    }

    /**
     * Moves keyboard focus to [next] after the user selects an item from this combo's popup.
     * Guarded by a popup-open flag so plain arrow-key navigation (popup closed) doesn't keep
     * stealing focus on every selection change.
     */
    private fun ComboBox<*>.focusNextOnSelect(next: () -> JComponent?) {
        var fromPopup = false
        addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) { fromPopup = true }
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
            override fun popupMenuCanceled(e: PopupMenuEvent?) { fromPopup = false }
        })
        addActionListener {
            if (fromPopup) {
                fromPopup = false
                SwingUtilities.invokeLater { next()?.requestFocusInWindow() }
            }
        }
    }

    /** The inner text field of a spinner, so focus lands on the editable value rather than the spinner shell. */
    private fun JSpinner.editorField(): JComponent = (editor as JSpinner.DefaultEditor).textField

    /** Parses `YYYY-MM-DD` from [field], or shows an error on it and returns null. */
    private fun parseIsoDate(field: JBTextField): LocalDate? {
        val text = field.text.trim()
        if (text.isEmpty()) {
            setErrorText("Enter a date (YYYY-MM-DD)", field)
            return null
        }
        return try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            setErrorText("Invalid date, use YYYY-MM-DD", field)
            null
        }
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
    is RuleMatcher.Date -> {
        val f = when (field) { DateField.CREATED -> "created"; DateField.MODIFIED -> "modified" }
        when (op) {
            DateOp.BEFORE -> "$f before ${millisToIsoDate(epochMillis)}"
            DateOp.AFTER -> "$f on/after ${millisToIsoDate(epochMillis)}"
            DateOp.BETWEEN -> "$f ${millisToIsoDate(epochMillis)}..${millisToIsoDate(epochMillisMax)}"
            DateOp.WITHIN_LAST -> "$f within last $amount ${unit.label()}"
            DateOp.OLDER_THAN -> "$f older than $amount ${unit.label()}"
        }
    }
    is RuleMatcher.Text -> {
        val f = when (field) {
            TextProperty.OWNER -> "owner"
            TextProperty.GROUP -> "group"
            TextProperty.PERMISSIONS -> "permissions"
        }
        val kindStr = when (kind) { PatternKind.EXACT -> "="; PatternKind.GLOB -> "glob"; PatternKind.REGEX -> "regex" }
        val case = if (caseSensitive) ", Aa" else ""
        "$f $kindStr \"$pattern\"$case"
    }
    is RuleMatcher.Symlink -> when (state) {
        SymlinkState.ANY -> "symlink"
        SymlinkState.VALID -> "symlink (valid)"
        SymlinkState.BROKEN -> "symlink (broken)"
    }
}

private val SYSTEM_ZONE: ZoneId get() = ZoneId.systemDefault()

private fun startOfDayMillis(date: LocalDate): Long =
    date.atStartOfDay(SYSTEM_ZONE).toInstant().toEpochMilli()

/** End of [date] (one ms before the next midnight) so BETWEEN ranges are inclusive of the whole day. */
private fun endOfDayMillis(date: LocalDate): Long =
    date.plusDays(1).atStartOfDay(SYSTEM_ZONE).toInstant().toEpochMilli() - 1

/** Formats an instant back to `YYYY-MM-DD`; any time-of-day collapses to its calendar day. */
private fun millisToIsoDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(SYSTEM_ZONE).toLocalDate().toString()

private fun labelRenderer(label: (Any?) -> String): DefaultListCellRenderer = object : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
    ): Component {
        val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        text = label(value)
        return c
    }
}

private fun ColorMatcherEditDialog.Kind.label(): String = when (this) {
    ColorMatcherEditDialog.Kind.SIZE -> "Size"
    ColorMatcherEditDialog.Kind.NAME -> "Name"
    ColorMatcherEditDialog.Kind.CREATED -> "Created date"
    ColorMatcherEditDialog.Kind.MODIFIED -> "Modified date"
    ColorMatcherEditDialog.Kind.OWNER -> "Owner / user"
    ColorMatcherEditDialog.Kind.GROUP -> "Group"
    ColorMatcherEditDialog.Kind.PERMISSIONS -> "Permissions"
    ColorMatcherEditDialog.Kind.CONTAINS -> "Directory contains"
    ColorMatcherEditDialog.Kind.SYMLINK -> "Symbolic link"
}

private fun SymlinkState.label(): String = when (this) {
    SymlinkState.ANY -> "Any symlink"
    SymlinkState.VALID -> "Valid (target exists)"
    SymlinkState.BROKEN -> "Broken (target missing)"
}

private fun DateOp.label(): String = when (this) {
    DateOp.BEFORE -> "Before"
    DateOp.AFTER -> "On or after"
    DateOp.BETWEEN -> "Between"
    DateOp.WITHIN_LAST -> "Within last"
    DateOp.OLDER_THAN -> "Older than"
}

private fun DateUnit.label(): String = when (this) {
    DateUnit.MINUTES -> "minutes"
    DateUnit.HOURS -> "hours"
    DateUnit.DAYS -> "days"
    DateUnit.WEEKS -> "weeks"
}

private fun SizeOp.label(): String = when (this) {
    SizeOp.LT -> "Less than (<)"
    SizeOp.LE -> "At most (≤)"
    SizeOp.EQ -> "Equal to (=)"
    SizeOp.GE -> "At least (≥)"
    SizeOp.GT -> "Greater than (>)"
    SizeOp.BETWEEN -> "Between"
}

private fun PatternKind.label(): String = when (this) {
    PatternKind.EXACT -> "Exact match"
    PatternKind.GLOB -> "Glob pattern"
    PatternKind.REGEX -> "Regular expression"
}

private fun AppliesTo.label(): String = when (this) {
    AppliesTo.FILE -> "Files only"
    AppliesTo.DIR -> "Directories only"
    AppliesTo.BOTH -> "Files and directories"
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
