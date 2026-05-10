package io.github.jhspetersson.turtlecommander.dialog
import io.github.jhspetersson.turtlecommander.service.OverwritePolicy
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.model.FileEntry

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import io.github.jhspetersson.turtlecommander.ui.installStandardContextMenu
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

class ExtractDialog(
    project: Project,
    private val sources: List<FileEntry>,
    defaultDestination: String,
) : DialogWrapper(project) {

    val destinationPath: String get() = destinationField.text.trim()
    val policy: OverwritePolicy get() = policyCombo.item

    private val destinationField = JBTextField(defaultDestination)
    private val policyCombo = ComboBox(OverwritePolicy.entries.toTypedArray()).apply {
        renderer = OverwritePolicyRenderer
        item = if (TurtleCommanderSettings.getInstance().state.alwaysOverwriteFiles) {
            OverwritePolicy.OVERWRITE_ALL
        } else {
            OverwritePolicy.ASK
        }
    }

    init {
        title = "Extract Files"
        setOKButtonText("Extract")
        destinationField.installStandardContextMenu()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            minimumSize = Dimension(500, 170)
            preferredSize = Dimension(500, 170)
        }

        val label = if (sources.size == 1) {
            "Extract \"${sources[0].name}\" to:"
        } else {
            "Extract ${sources.size} archives to:"
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

        val fieldWrapper = JPanel(BorderLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(8, 0, 8, 0)
            add(destinationField, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, destinationField.preferredSize.height + 16)
        }
        panel.add(fieldWrapper)

        val policyRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("If a target file already exists:"))
            add(Box.createHorizontalStrut(8))
            add(policyCombo.apply {
                maximumSize = Dimension(260, preferredSize.height)
            })
        }
        panel.add(policyRow)

        return panel
    }

    override fun getPreferredFocusedComponent() = destinationField
}
