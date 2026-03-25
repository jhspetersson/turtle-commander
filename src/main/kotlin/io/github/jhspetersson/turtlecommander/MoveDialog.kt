package io.github.jhspetersson.turtlecommander

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

class MoveDialog(
    project: Project,
    private val sources: List<FileEntry>,
    private val destination: Path,
    private val destinationDisplayPath: String = destination.toString(),
) : DialogWrapper(project) {

    val overwriteExisting: Boolean get() = overwriteCheckBox.isSelected

    private val overwriteCheckBox = JCheckBox(
        "Overwrite existing files",
        TurtleCommanderSettings.getInstance().state.alwaysOverwriteFiles,
    )

    init {
        title = "Move"
        setOKButtonText("Move")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            minimumSize = Dimension(500, 120)
            preferredSize = Dimension(500, 120)
        }

        val label = if (sources.size == 1) {
            "Move \"${sources[0].name}\" to:"
        } else {
            "Move ${sources.size} items to:"
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

        panel.add(JBLabel(destinationDisplayPath).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(8, 0, 8, 0)
        })

        panel.add(overwriteCheckBox.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })

        return panel
    }
}
