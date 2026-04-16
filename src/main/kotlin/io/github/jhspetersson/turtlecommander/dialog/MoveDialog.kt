package io.github.jhspetersson.turtlecommander.dialog
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.model.FileEntry

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.Box
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

    var targetDirectory: Path = destination
        private set

    private val overwriteCheckBox = JCheckBox(
        "Overwrite existing files",
        TurtleCommanderSettings.getInstance().state.alwaysOverwriteFiles,
    )

    private val destinationField = JBTextField(destinationDisplayPath)

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

        panel.add(Box.createVerticalStrut(8))
        panel.add(destinationField.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        })
        panel.add(Box.createVerticalStrut(8))

        panel.add(overwriteCheckBox.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = destinationField

    override fun doOKAction() {
        val text = destinationField.text.trim()
        if (text.isEmpty()) {
            Messages.showErrorDialog(contentPanel, "Destination cannot be empty.", "Move")
            return
        }

        val resolved = if (text == destinationDisplayPath) {
            destination
        } else {
            val parsed = try {
                Path.of(text)
            } catch (e: InvalidPathException) {
                Messages.showErrorDialog(contentPanel, "Invalid destination path: ${e.message}", "Move")
                return
            }
            if (parsed.isAbsolute) parsed.normalize() else destination.resolve(parsed).normalize()
        }

        if (Files.exists(resolved)) {
            if (!Files.isDirectory(resolved)) {
                Messages.showErrorDialog(contentPanel, "Destination is not a directory:\n$resolved", "Move")
                return
            }
        } else {
            try {
                Files.createDirectories(resolved)
            } catch (e: Exception) {
                Messages.showErrorDialog(contentPanel, "Failed to create destination directory:\n${e.message}", "Move")
                return
            }
        }

        targetDirectory = resolved
        super.doOKAction()
    }
}
