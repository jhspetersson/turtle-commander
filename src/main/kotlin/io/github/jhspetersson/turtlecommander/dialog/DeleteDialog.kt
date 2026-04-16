package io.github.jhspetersson.turtlecommander.dialog
import io.github.jhspetersson.turtlecommander.model.FileEntry

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

class DeleteDialog(
    project: Project,
    private val sources: List<FileEntry>,
    private val useRecycleBin: Boolean = false,
) : DialogWrapper(project) {

    init {
        title = if (useRecycleBin) "Move to Recycle Bin" else "Delete"
        setOKButtonText(if (useRecycleBin) "Move to Recycle Bin" else "Delete")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            minimumSize = Dimension(500, 120)
            preferredSize = Dimension(500, 120)
        }

        val label = buildPrompt(sources, useRecycleBin)

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

        return panel
    }

    companion object {
        /**
         * Package-visible for testing: the exact wording depends on whether the deletion is
         * permanent or reversible via the recycle bin, so tests assert against this directly
         * without spinning up the Swing dialog.
         */
        fun buildPrompt(sources: List<FileEntry>, useRecycleBin: Boolean): String {
            return if (sources.size == 1) {
                if (useRecycleBin) "Move \"${sources[0].name}\" to Recycle Bin?"
                else "Delete \"${sources[0].name}\"?"
            } else {
                if (useRecycleBin) "Move ${sources.size} items to Recycle Bin?"
                else "Delete ${sources.size} items?"
            }
        }
    }
}
