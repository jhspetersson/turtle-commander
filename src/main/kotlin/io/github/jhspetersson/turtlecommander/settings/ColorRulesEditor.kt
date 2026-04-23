package io.github.jhspetersson.turtlecommander.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.dialog.InputDialog
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import java.util.*
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Rule table + toolbar UI embedded inside [TurtleCommanderConfigurable].
 * Owns a mutable snapshot of the rules so Apply/Cancel semantics work the
 * same way they do for themes and columns: edits are local until `applyTo`
 * writes them back to [TurtleCommanderSettings.State].
 */
internal class ColorRulesEditor(private val project: Project?) {

    private val rules = mutableListOf<ColorRule>()
    private var savedSnapshot: List<ColorRule> = emptyList()
    private var savedMode: ColorizationMode = ColorizationMode.WINNER

    private val tableModel = RulesTableModel()
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = JBUI.scale(24)
        tableHeader.reorderingAllowed = false
        autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        configureColumnWidths()
    }

    private val modeCombo = ComboBox(DefaultComboBoxModel(ColorizationMode.entries.toTypedArray())).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = when (value) {
                    ColorizationMode.WINNER -> "Winner takes all (highest priority)"
                    ColorizationMode.LAYERED -> "Layered (overlay by priority)"
                    else -> value?.toString() ?: ""
                }
                return c
            }
        }
    }

    val panel: JPanel = JPanel(BorderLayout(4, 4)).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = BorderFactory.createTitledBorder("File colorization rules")
    }

    init {
        // Toggle active with a mouse click on the checkbox column
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val col = table.columnAtPoint(e.point)
                if (col != COL_ACTIVE) return
                val row = table.rowAtPoint(e.point)
                if (row < 0) return
                val cur = rules[row]
                rules[row] = cur.copy(active = !cur.active)
                tableModel.fireTableRowsUpdated(row, row)
            }

            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && table.rowAtPoint(e.point) >= 0) {
                    editSelected()
                }
            }
        })

        panel.add(buildToolbar(), BorderLayout.NORTH)
        panel.add(JScrollPane(table).apply {
            preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(240))
        }, BorderLayout.CENTER)
        panel.add(buildRightButtons(), BorderLayout.EAST)
    }

    fun resetFrom(state: TurtleCommanderSettings.State) {
        val all = ColorRuleManager.getAllRules(state)
        rules.clear()
        rules.addAll(all)
        savedSnapshot = all.map { it.copy() }
        savedMode = ColorRuleManager.getMode(state)
        modeCombo.selectedItem = savedMode
        tableModel.fireTableDataChanged()
    }

    fun applyTo(state: TurtleCommanderSettings.State) {
        state.colorRulesInitialized = true
        state.colorRules.clear()
        for (rule in rules) {
            state.colorRules.add(SavedColorRule.fromRule(rule))
        }
        ColorRuleManager.setMode(state, modeCombo.selectedItem as ColorizationMode)
        savedSnapshot = rules.map { it.copy() }
        savedMode = modeCombo.selectedItem as ColorizationMode
    }

    fun isModified(): Boolean {
        if (modeCombo.selectedItem != savedMode) return true
        if (rules.size != savedSnapshot.size) return true
        for (i in rules.indices) {
            if (rules[i] != savedSnapshot[i]) return true
        }
        return false
    }

    // --- toolbar ---------------------------------------------------------

    private fun buildToolbar(): JComponent {
        val exportButton = JButton(AllIcons.ToolbarDecorator.Export).apply {
            toolTipText = "Export current rules to a .tcrules file"
            addActionListener { exportCurrentRules() }
        }
        val importButton = JButton(AllIcons.ToolbarDecorator.Import).apply {
            toolTipText = "Import rules from a .tcrules file"
            addActionListener { importRules() }
        }
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        row.add(JBLabel("Mode:"))
        row.add(modeCombo)
        row.add(Box.createHorizontalStrut(12))
        row.add(exportButton)
        row.add(importButton)
        return row
    }

    private fun buildRightButtons(): JComponent {
        val add = JButton("Add...").apply { addActionListener { addRule() } }
        val edit = JButton("Edit...").apply { addActionListener { editSelected() } }
        val dup = JButton("Duplicate").apply { addActionListener { duplicateSelected() } }
        val delete = JButton("Delete").apply { addActionListener { deleteSelected() } }
        val moveUp = JButton("Up").apply { addActionListener { moveSelected(-1) } }
        val moveDown = JButton("Down").apply { addActionListener { moveSelected(1) } }
        val reset = JButton("Reset to defaults").apply { addActionListener { resetToDefaults() } }
        val col = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            for (btn in listOf(add, edit, dup, delete, moveUp, moveDown, reset)) {
                btn.alignmentX = Component.LEFT_ALIGNMENT
                btn.maximumSize = Dimension(Int.MAX_VALUE, btn.preferredSize.height)
                add(btn)
                add(Box.createVerticalStrut(4))
            }
        }
        return col
    }

    // --- actions ---------------------------------------------------------

    private fun addRule() {
        val dialog = ColorRuleEditDialog(project, null)
        if (dialog.showAndGet()) {
            dialog.result?.let {
                rules.add(it)
                tableModel.fireTableDataChanged()
                table.setRowSelectionInterval(rules.size - 1, rules.size - 1)
            }
        }
    }

    private fun editSelected() {
        val idx = table.selectedRow
        if (idx < 0) return
        val dialog = ColorRuleEditDialog(project, rules[idx])
        if (dialog.showAndGet()) {
            dialog.result?.let {
                rules[idx] = it
                tableModel.fireTableRowsUpdated(idx, idx)
            }
        }
    }

    private fun duplicateSelected() {
        val idx = table.selectedRow
        if (idx < 0) return
        val src = rules[idx]
        val copy = src.copy(id = UUID.randomUUID().toString(), name = "${src.name} (copy)")
        rules.add(idx + 1, copy)
        tableModel.fireTableDataChanged()
        table.setRowSelectionInterval(idx + 1, idx + 1)
    }

    private fun deleteSelected() {
        val idx = table.selectedRow
        if (idx < 0) return
        val rule = rules[idx]
        val confirm = Messages.showYesNoDialog(
            "Delete rule \"${rule.name}\"?", "Delete Rule", null,
        )
        if (confirm != Messages.YES) return
        rules.removeAt(idx)
        tableModel.fireTableDataChanged()
    }

    private fun moveSelected(delta: Int) {
        val idx = table.selectedRow
        if (idx < 0) return
        val target = idx + delta
        if (target < 0 || target >= rules.size) return
        val item = rules.removeAt(idx)
        rules.add(target, item)
        tableModel.fireTableDataChanged()
        table.setRowSelectionInterval(target, target)
    }

    private fun resetToDefaults() {
        val confirm = Messages.showYesNoDialog(
            "Replace all rules with the built-in defaults?", "Reset Rules", null,
        )
        if (confirm != Messages.YES) return
        rules.clear()
        rules.addAll(ColorRuleDefaults.builtinRules())
        tableModel.fireTableDataChanged()
    }

    private fun exportCurrentRules() {
        val dialog = InputDialog(project, title = "Export Rules", message = "Config name:")
        if (!dialog.showAndGet()) return
        val name = dialog.inputValue
        if (name.isBlank()) {
            Messages.showWarningDialog("Config name cannot be empty.", "Export Rules")
            return
        }
        val config = ColorRulesConfig(name = name, rules = rules.toList())
        val text = ColorRulesIO.export(config)
        val descriptor = FileSaverDescriptor("Export Rules", "Save rules to a .tcrules file", "tcrules")
        val chooser = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val fileName = "${name.replace(' ', '_')}.tcrules"
        val wrapper = chooser.save(null as com.intellij.openapi.vfs.VirtualFile?, fileName) ?: return
        wrapper.file.writeText(text)
    }

    private fun importRules() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withExtensionFilter("Turtle Commander Rules", "tcrules")
            .withTitle("Import Rules")
            .withDescription("Select a Turtle Commander rules file")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val text = File(chosen.path).readText()
        val config = ColorRulesIO.parse(text)
        if (config == null) {
            Messages.showWarningDialog("Invalid rules file.", "Import Rules")
            return
        }
        val choice = Messages.showYesNoCancelDialog(
            "Import \"${config.name}\" (${config.rules.size} rules)?\n\nYes = replace current rules\nNo = append to current rules",
            "Import Rules", "Replace", "Append", "Cancel", null,
        )
        when (choice) {
            Messages.YES -> {
                rules.clear()
                rules.addAll(config.rules.map { it.copy(id = UUID.randomUUID().toString()) })
            }
            Messages.NO -> {
                rules.addAll(config.rules.map { it.copy(id = UUID.randomUUID().toString()) })
            }
            else -> return
        }
        tableModel.fireTableDataChanged()
    }

    // --- table plumbing --------------------------------------------------

    private fun JTable.configureColumnWidths() {
        val cm = columnModel
        cm.getColumn(COL_ACTIVE).apply {
            preferredWidth = JBUI.scale(50)
            maxWidth = JBUI.scale(60)
            cellRenderer = ActiveCellRenderer()
        }
        cm.getColumn(COL_PRIORITY).apply {
            preferredWidth = JBUI.scale(60)
            maxWidth = JBUI.scale(80)
            cellRenderer = CenterAlignedRenderer()
        }
        cm.getColumn(COL_NAME).preferredWidth = JBUI.scale(220)
        cm.getColumn(COL_COLOR).apply {
            preferredWidth = JBUI.scale(50)
            maxWidth = JBUI.scale(60)
            cellRenderer = ColorSwatchCellRenderer()
        }
        cm.getColumn(COL_DOT).apply {
            preferredWidth = JBUI.scale(50)
            maxWidth = JBUI.scale(60)
            cellRenderer = ColorSwatchCellRenderer()
        }
        cm.getColumn(COL_BG).apply {
            preferredWidth = JBUI.scale(50)
            maxWidth = JBUI.scale(60)
            cellRenderer = ColorSwatchCellRenderer()
        }
        cm.getColumn(COL_MATCHERS).preferredWidth = JBUI.scale(300)
    }

    private inner class RulesTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = rules.size
        override fun getColumnCount(): Int = 7
        override fun getColumnName(column: Int): String = when (column) {
            COL_ACTIVE -> "On"
            COL_PRIORITY -> "Prio"
            COL_NAME -> "Name"
            COL_COLOR -> "Fg"
            COL_BG -> "Bg"
            COL_DOT -> "Dot"
            COL_MATCHERS -> "Matchers"
            else -> ""
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            COL_ACTIVE -> Boolean::class.javaObjectType
            COL_PRIORITY -> Int::class.javaObjectType
            else -> String::class.java
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val r = rules[rowIndex]
            return when (columnIndex) {
                COL_ACTIVE -> r.active
                COL_PRIORITY -> r.priority
                COL_NAME -> r.name
                COL_COLOR -> r.style.fontColor
                COL_BG -> r.style.backgroundColor
                COL_DOT -> r.style.iconDotColor
                COL_MATCHERS -> summarizeMatchers(r)
                else -> ""
            }
        }
    }

    private class ActiveCellRenderer : DefaultTableCellRenderer() {
        private val check = JCheckBox().apply {
            horizontalAlignment = CENTER
        }
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            check.isSelected = value == true
            if (isSelected && table != null) {
                check.background = table.selectionBackground
                check.foreground = table.selectionForeground
            } else {
                check.background = table?.background
                check.foreground = table?.foreground
            }
            check.isOpaque = true
            return check
        }
    }

    private class CenterAlignedRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = CENTER
        }
    }

    companion object {
        private const val COL_ACTIVE = 0
        private const val COL_PRIORITY = 1
        private const val COL_NAME = 2
        private const val COL_COLOR = 3
        private const val COL_BG = 4
        private const val COL_DOT = 5
        private const val COL_MATCHERS = 6

        private fun summarizeMatchers(rule: ColorRule): String {
            if (rule.matchers.isEmpty()) return "(none)"
            val joiner = if (rule.combinator == Combinator.AND) " AND " else " OR "
            return rule.matchers.joinToString(joiner) { it.describe() }
        }
    }
}
