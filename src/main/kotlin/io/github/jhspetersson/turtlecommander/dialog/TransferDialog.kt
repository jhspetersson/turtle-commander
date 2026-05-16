package io.github.jhspetersson.turtlecommander.dialog
import io.github.jhspetersson.turtlecommander.service.OverwritePolicy
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.model.FileEntry

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
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
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Shared destination / overwrite-policy dialog used for both Copy and Move. The two flows are
 * structurally identical — only the window title, OK-button label, and a few wording bits
 * change — so they're parameterized via [verb] (e.g. "Copy" / "Move") instead of being two
 * near-identical [DialogWrapper] subclasses.
 */
class TransferDialog(
    project: Project,
    private val verb: String,
    private val sources: List<FileEntry>,
    private val destination: Path,
    private val destinationDisplayPath: String = destination.toString(),
) : DialogWrapper(project) {

    val policy: OverwritePolicy get() = policyCombo.item

    var targetDirectory: Path = destination
        private set

    private val policyCombo = ComboBox(OverwritePolicy.entries.toTypedArray()).apply {
        renderer = OverwritePolicyRenderer
        // Preselect the user's saved default policy. ASK on a fresh install means the
        // dialog still prompts per file — overwrite-without-asking has to be opted into.
        item = TurtleCommanderSettings.getInstance().getDefaultOverwritePolicy()
    }

    private val destinationField = JBTextField(destinationDisplayPath)

    init {
        title = verb
        setOKButtonText(verb)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            minimumSize = Dimension(500, 140)
            preferredSize = Dimension(500, 140)
        }

        val label = if (sources.size == 1) {
            "$verb \"${sources[0].name}\" to:"
        } else {
            "$verb ${sources.size} items to:"
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

    override fun getPreferredFocusedComponent(): JComponent = destinationField

    override fun doOKAction() {
        val text = destinationField.text.trim()
        if (text.isEmpty()) {
            Messages.showErrorDialog(contentPanel, "Destination cannot be empty.", verb)
            return
        }

        val resolved = if (text == destinationDisplayPath) {
            destination
        } else {
            val parsed = try {
                Path.of(text)
            } catch (e: InvalidPathException) {
                Messages.showErrorDialog(contentPanel, "Invalid destination path: ${e.message}", verb)
                return
            }
            if (parsed.isAbsolute) parsed.normalize() else destination.resolve(parsed).normalize()
        }

        if (Files.exists(resolved)) {
            if (!Files.isDirectory(resolved)) {
                Messages.showErrorDialog(contentPanel, "Destination is not a directory:\n$resolved", verb)
                return
            }
        } else {
            try {
                Files.createDirectories(resolved)
            } catch (e: Exception) {
                Messages.showErrorDialog(contentPanel, "Failed to create destination directory:\n${e.message}", verb)
                return
            }
        }

        targetDirectory = resolved
        super.doOKAction()
    }
}
