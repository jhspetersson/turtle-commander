package io.github.jhspetersson.turtlecommander

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

class PackDialog(
    project: Project,
    private val sources: List<FileEntry>,
    defaultArchivePath: String,
) : DialogWrapper(project) {

    val archivePath: String get() = archiveField.text.trim()
    val deleteAfterPacking: Boolean get() = deleteCheckBox.isSelected

    private val archiveField = JBTextField(defaultArchivePath)
    private val deleteCheckBox = JCheckBox("Delete files after packing", false)

    init {
        title = "Pack Files"
        setOKButtonText("Pack")
        init()
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

        val fieldWrapper = JPanel(java.awt.BorderLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(8, 0, 8, 0)
            add(archiveField, java.awt.BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, archiveField.preferredSize.height + 16)
        }
        panel.add(fieldWrapper)

        panel.add(deleteCheckBox.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })

        return panel
    }

    override fun getPreferredFocusedComponent() = archiveField
}
