package io.github.jhspetersson.turtlecommander.dialog
import io.github.jhspetersson.turtlecommander.model.FileEntry

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import io.github.jhspetersson.turtlecommander.ui.installStandardContextMenu
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ListCellRenderer

enum class ArchiveFormat(val extension: String, val label: String) {
    ZIP(".zip", ".zip"),
    TAR_GZ(".tar.gz", ".tar.gz"),
}

class PackDialog(
    project: Project,
    private val sources: List<FileEntry>,
    defaultArchivePath: String,
) : DialogWrapper(project) {

    val archivePath: String get() {
        val path = archiveField.text.trim()
        val selectedFormat = formatCombo.selectedItem as? ArchiveFormat ?: ArchiveFormat.ZIP
        // Replace extension if user hasn't manually changed it
        for (fmt in ArchiveFormat.entries) {
            if (path.endsWith(fmt.extension)) {
                return path.removeSuffix(fmt.extension) + selectedFormat.extension
            }
        }
        return path
    }
    val archiveFormat: ArchiveFormat get() = formatCombo.selectedItem as? ArchiveFormat ?: ArchiveFormat.ZIP
    val deleteAfterPacking: Boolean get() = deleteCheckBox.isSelected

    private val archiveField = JBTextField(defaultArchivePath)
    private val formatCombo = ComboBox(DefaultComboBoxModel(ArchiveFormat.entries.toTypedArray())).apply {
        selectedItem = ArchiveFormat.ZIP
        renderer = DefaultListCellRenderer().let { renderer ->
            ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
                renderer.getListCellRendererComponent(list, value?.label, index, isSelected, cellHasFocus)
            }
        }
    }
    private val deleteCheckBox = JCheckBox("Delete files after packing", false)

    init {
        title = "Pack Files"
        setOKButtonText("Pack")
        archiveField.installStandardContextMenu()
        init()

        formatCombo.addActionListener {
            val path = archiveField.text.trim()
            val newFormat = formatCombo.selectedItem as? ArchiveFormat ?: return@addActionListener
            for (fmt in ArchiveFormat.entries) {
                if (path.endsWith(fmt.extension)) {
                    archiveField.text = path.removeSuffix(fmt.extension) + newFormat.extension
                    return@addActionListener
                }
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            minimumSize = Dimension(500, 150)
            preferredSize = Dimension(500, 150)
        }

        val label = if (sources.size == 1) {
            "Pack \"${sources[0].name}\" to archive:"
        } else {
            "Pack ${sources.size} items to archive:"
        }

        panel.add(JBLabel(label).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })

        if (sources.size > 1) {
            val textArea = JTextArea(sources.joinToString("\n") { it.name }).apply {
                isEditable = false
                rows = minOf(sources.size, 10)
            }
            panel.add(JScrollPane(textArea).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
        }

        val fieldPanel = JPanel(BorderLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(8, 0, 8, 0)
            add(archiveField, BorderLayout.CENTER)
            add(formatCombo, BorderLayout.EAST)
            maximumSize = Dimension(Int.MAX_VALUE, archiveField.preferredSize.height + 16)
        }
        panel.add(fieldPanel)

        panel.add(deleteCheckBox.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })

        return panel
    }

    override fun getPreferredFocusedComponent() = archiveField
}
