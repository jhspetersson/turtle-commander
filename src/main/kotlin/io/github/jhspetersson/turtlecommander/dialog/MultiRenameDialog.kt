package io.github.jhspetersson.turtlecommander.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.operation.MultiRenameTemplate
import io.github.jhspetersson.turtlecommander.ui.installStandardContextMenu
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Files
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class MultiRenameDialog(
    project: Project,
    private val entries: List<FileEntry>,
) : DialogWrapper(project) {

    data class Result(val pairs: List<Pair<FileEntry, String>>)

    private val nameField = JBTextField("[N]")
    private val extField = JBTextField("[E]")
    private val searchField = JBTextField("")
    private val replaceField = JBTextField("")
    private val regexCheck = JCheckBox("Regex", false)
    private val caseSensitiveCheck = JCheckBox("Case sensitive", true)
    private val useCurrentDateCheck = JCheckBox("Use current date for [Y][M][D][h][n][s]", false)
    private val caseCombo = ComboBox(CaseOption.entries.toTypedArray()).apply {
        selectedItem = CaseOption.UNCHANGED
    }
    private val counterStartSpinner = JSpinner(SpinnerNumberModel(1, Int.MIN_VALUE, Int.MAX_VALUE, 1))
    private val counterStepSpinner = JSpinner(SpinnerNumberModel(1, Int.MIN_VALUE, Int.MAX_VALUE, 1))
    private val counterWidthSpinner = JSpinner(SpinnerNumberModel(1, 1, 20, 1))

    private val tableModel = PreviewModel()
    private val table = JBTable(tableModel)

    private var conflictRows: Set<Int> = emptySet()
    private var currentTargets: List<String> = emptyList()

    var result: Result? = null
        private set

    init {
        title = "Multi-Rename"
        setOKButtonText("Rename")
        listOf(nameField, extField, searchField, replaceField).forEach { it.installStandardContextMenu() }
        wireLiveUpdate()
        init()
        recomputePreview()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(780, 520)
        }
        root.add(buildControls(), BorderLayout.NORTH)
        root.add(buildPreview(), BorderLayout.CENTER)
        return root
    }

    private fun buildControls(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 4)
            gridy = 0
        }

        fun addRow(label: String, field: JComponent, second: Pair<String, JComponent>? = null) {
            gbc.gridx = 0; gbc.weightx = 0.0
            panel.add(JBLabel(label), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            panel.add(field, gbc)
            if (second != null) {
                gbc.gridx = 2; gbc.weightx = 0.0
                panel.add(JBLabel(second.first), gbc)
                gbc.gridx = 3; gbc.weightx = 1.0
                panel.add(second.second, gbc)
            }
            gbc.gridy++
        }

        addRow("Name template:", nameField, "Extension template:" to extField)
        addRow("Search for:", searchField, "Replace with:" to replaceField)

        val flagsPanel = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)
            add(regexCheck)
            add(caseSensitiveCheck)
            add(JBLabel("   Case:"))
            add(caseCombo)
        }
        gbc.gridx = 0; gbc.gridwidth = 4; gbc.weightx = 1.0
        panel.add(flagsPanel, gbc)
        gbc.gridwidth = 1; gbc.gridy++

        val counterPanel = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)
            add(JBLabel("Counter — start:"))
            add(counterStartSpinner)
            add(JBLabel("step:"))
            add(counterStepSpinner)
            add(JBLabel("width:"))
            add(counterWidthSpinner)
            add(useCurrentDateCheck)
        }
        gbc.gridx = 0; gbc.gridwidth = 4
        panel.add(counterPanel, gbc)
        gbc.gridwidth = 1; gbc.gridy++

        val hint = JBLabel("<html><small>" +
            "[N]=name &nbsp; [N1-5]=chars 1..5 &nbsp; [N3-]=from 3 to end &nbsp; [N2,3]=3 chars from pos 2 &nbsp; [N-3]=3rd from end &nbsp; " +
            "[E]=extension &nbsp; [C]=counter &nbsp; [Y][M][D][h][n][s]=date/time &nbsp; " +
            "[P]=parent dir &nbsp; [G]=grandparent dir &nbsp; " +
            "[R5]=5 random digits &nbsp; [Ra5]/[RA5]/[RM5]=lower/upper/mixed letters &nbsp; " +
                $$"Regex replace supports $1, $2, …" +
            "</small></html>")
        gbc.gridx = 0; gbc.gridwidth = 4
        panel.add(hint, gbc)

        return panel
    }

    private fun buildPreview(): JComponent {
        table.setShowGrid(true)
        table.rowHeight += 2
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        val renderer = ConflictRenderer { conflictRows }
        for (col in 0 until table.columnCount) {
            table.columnModel.getColumn(col).cellRenderer = renderer
        }
        val scroll = JBScrollPane(table)
        val panel = JPanel(BorderLayout(0, 4))
        panel.add(JBLabel("Preview — conflicting rows are highlighted; rename is disabled while any conflict exists."), BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }

    private fun wireLiveUpdate() {
        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = recomputePreview()
            override fun removeUpdate(e: DocumentEvent?) = recomputePreview()
            override fun changedUpdate(e: DocumentEvent?) = recomputePreview()
        }
        listOf(nameField, extField, searchField, replaceField).forEach { it.document.addDocumentListener(docListener) }
        listOf(regexCheck, caseSensitiveCheck, useCurrentDateCheck).forEach { it.addActionListener { recomputePreview() } }
        caseCombo.addActionListener { recomputePreview() }
        listOf(counterStartSpinner, counterStepSpinner, counterWidthSpinner).forEach {
            it.addChangeListener { recomputePreview() }
        }
    }

    private fun recomputePreview() {
        val options = MultiRenameTemplate.Options(
            nameTemplate = nameField.text,
            extensionTemplate = extField.text,
            search = searchField.text,
            replace = replaceField.text,
            regex = regexCheck.isSelected,
            caseSensitive = caseSensitiveCheck.isSelected,
            caseMode = (caseCombo.selectedItem as CaseOption).mode,
            counter = MultiRenameTemplate.CounterConfig(
                start = (counterStartSpinner.value as Number).toInt(),
                step = (counterStepSpinner.value as Number).toInt(),
                width = (counterWidthSpinner.value as Number).toInt(),
            ),
            useCurrentDate = useCurrentDateCheck.isSelected,
        )
        val inputs = entries.map { entry ->
            MultiRenameTemplate.Input.from(entry.path, entry.lastModified)
        }
        val targets = MultiRenameTemplate.render(inputs, options)
        val caseInsensitiveFs = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        conflictRows = MultiRenameTemplate.detectConflicts(
            sources = entries.map { it.path },
            targetNames = targets,
            caseInsensitiveDuplicates = caseInsensitiveFs,
            existsOnDisk = { Files.exists(it) },
        )
        currentTargets = targets
        tableModel.refresh(entries, targets)
        SwingUtilities.invokeLater { updateOkState() }
    }

    private fun updateOkState() {
        val anyChange = entries.indices.any { i -> currentTargets[i] != entries[i].name }
        isOKActionEnabled = conflictRows.isEmpty() && anyChange
    }

    override fun doOKAction() {
        val pairs = entries.zip(currentTargets).filter { (e, name) -> name != e.name }
        result = Result(pairs)
        super.doOKAction()
    }

    override fun getPreferredFocusedComponent() = nameField

    private class PreviewModel : AbstractTableModel() {
        private var sources: List<FileEntry> = emptyList()
        private var targets: List<String> = emptyList()

        fun refresh(sources: List<FileEntry>, targets: List<String>) {
            this.sources = sources
            this.targets = targets
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = sources.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = if (column == 0) "Old name" else "New name"
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            return if (columnIndex == 0) sources[rowIndex].name else targets[rowIndex]
        }
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
    }

    private class ConflictRenderer(private val conflicts: () -> Set<Int>) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val isConflict = row in conflicts()
            if (isConflict && !isSelected) {
                c.background = CONFLICT_BG
                c.foreground = CONFLICT_FG
            } else if (!isSelected) {
                c.background = table.background
                c.foreground = table.foreground
            }
            return c
        }

        companion object {
            private val CONFLICT_BG = JBColor(java.awt.Color(0xFF, 0xD0, 0xD0), java.awt.Color(0x5A, 0x2C, 0x2C))
            private val CONFLICT_FG = JBColor(java.awt.Color(0x80, 0x00, 0x00), java.awt.Color(0xFF, 0xC0, 0xC0))
        }
    }

    enum class CaseOption(val mode: MultiRenameTemplate.CaseMode, val label: String) {
        UNCHANGED(MultiRenameTemplate.CaseMode.UNCHANGED, "Unchanged"),
        UPPER(MultiRenameTemplate.CaseMode.UPPER, "UPPER"),
        LOWER(MultiRenameTemplate.CaseMode.LOWER, "lower"),
        FIRST_UPPER(MultiRenameTemplate.CaseMode.FIRST_UPPER, "First capital"),
        EVERY_WORD_UPPER(MultiRenameTemplate.CaseMode.EVERY_WORD_UPPER, "Every Word Cap");

        override fun toString(): String = label
    }
}
