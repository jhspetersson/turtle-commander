package io.github.jhspetersson.turtlecommander.settings

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

internal class ColumnsEditor(
    private val fontItems: Array<String>,
    initialColumns: List<ColumnConfig>,
) {
    private data class ColumnRow(
        val id: String,
        var visible: Boolean,
        val styleEditor: ComponentStyleEditor,
    )

    private val rows = mutableListOf<ColumnRow>()
    private val listModel = DefaultListModel<ColumnRow>()
    private val list = JBList(listModel)
    private val detailPanel = JPanel(BorderLayout())
    val panel: JPanel

    init {
        resetFrom(initialColumns)

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ListCellRenderer<ColumnRow> {
            private val checkBox = JCheckBox()
            override fun getListCellRendererComponent(
                list: JList<out ColumnRow>,
                value: ColumnRow?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                checkBox.text = value?.id ?: ""
                checkBox.isSelected = value?.visible == true
                checkBox.background = if (isSelected) list.selectionBackground else list.background
                checkBox.foreground = if (isSelected) list.selectionForeground else list.foreground
                checkBox.isOpaque = true
                checkBox.font = list.font
                return checkBox
            }
        }
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val idx = list.locationToIndex(e.point)
                if (idx < 0) return
                val cellBounds = list.getCellBounds(idx, idx) ?: return
                // Toggle checkbox when clicking in the checkbox area (left ~24px)
                if (e.x - cellBounds.x < 24) {
                    rows[idx].visible = !rows[idx].visible
                    listModel.set(idx, rows[idx])
                    updateDetail()
                }
            }
        })
        list.addListSelectionListener { updateDetail() }

        val moveUpButton = JButton("Up").apply {
            addActionListener {
                val idx = list.selectedIndex
                if (idx > 0) {
                    val item = rows.removeAt(idx)
                    rows.add(idx - 1, item)
                    rebuildListModel()
                    list.selectedIndex = idx - 1
                }
            }
        }
        val moveDownButton = JButton("Down").apply {
            addActionListener {
                val idx = list.selectedIndex
                if (idx >= 0 && idx < rows.size - 1) {
                    val item = rows.removeAt(idx)
                    rows.add(idx + 1, item)
                    rebuildListModel()
                    list.selectedIndex = idx + 1
                }
            }
        }

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(moveUpButton)
            add(moveDownButton)
            for (btn in listOf(moveUpButton, moveDownButton)) {
                btn.maximumSize = Dimension(Int.MAX_VALUE, btn.preferredSize.height)
            }
        }

        val listPanel = JPanel(BorderLayout(4, 0)).apply {
            add(JScrollPane(list).apply {
                preferredSize = Dimension(160, 0)
            }, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.EAST)
        }

        panel = JPanel(BorderLayout(8, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createTitledBorder("Columns")
            add(listPanel, BorderLayout.WEST)
            add(detailPanel, BorderLayout.CENTER)
            preferredSize = Dimension(Int.MAX_VALUE, 260)
            maximumSize = Dimension(Int.MAX_VALUE, 260)
        }

        if (rows.isNotEmpty()) list.selectedIndex = 0
    }

    private fun updateDetail() {
        detailPanel.removeAll()

        val idx = list.selectedIndex
        if (idx < 0 || idx >= rows.size) {
            detailPanel.revalidate()
            detailPanel.repaint()
            return
        }

        val editor = rows[idx].styleEditor
        val grid = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
            insets = JBUI.insets(2, 4, 2, 4)
        }
        gbc.gridy = 0
        gbc.gridx = 0; grid.add(JBLabel("Font:"), gbc)
        gbc.gridx = 1; grid.add(editor.fontCombo, gbc)
        gbc.gridy = 1
        gbc.gridx = 0; grid.add(JBLabel("Size:"), gbc)
        gbc.gridx = 1; grid.add(editor.sizeSpinner, gbc)
        gbc.gridy = 2
        gbc.gridx = 0; grid.add(JBLabel("Style:"), gbc)
        gbc.gridx = 1; grid.add(editor.styleCombo, gbc)
        gbc.gridy = 3
        gbc.gridx = 0; grid.add(JBLabel("Color:"), gbc)
        gbc.gridx = 1; grid.add(editor.colorButton, gbc)

        detailPanel.add(grid, BorderLayout.CENTER)
        detailPanel.revalidate()
        detailPanel.repaint()
    }

    private fun rebuildListModel() {
        listModel.clear()
        for (row in rows) {
            listModel.addElement(row)
        }
    }

    fun resetFrom(columns: List<ColumnConfig>) {
        rows.clear()
        for (col in columns) {
            val editor = ComponentStyleEditor(col.id, fontItems, col.style)
            rows.add(ColumnRow(col.id, col.visible, editor))
        }
        rebuildListModel()
        if (rows.isNotEmpty() && list.selectedIndex < 0) list.selectedIndex = 0
        updateDetail()
    }

    fun applyTo(settings: TurtleCommanderSettings.State) {
        settings.columns.clear()
        for (row in rows) {
            val config = ColumnConfig().apply {
                id = row.id
                visible = row.visible
            }
            row.styleEditor.applyTo(config.style)
            settings.columns.add(config)
        }
    }

    fun isModified(savedColumns: List<ColumnConfig>): Boolean {
        if (rows.size != savedColumns.size) return true
        for ((i, row) in rows.withIndex()) {
            val saved = savedColumns[i]
            if (row.id != saved.id) return true
            if (row.visible != saved.visible) return true
            if (row.styleEditor.isModified(saved.style)) return true
        }
        return false
    }
}
