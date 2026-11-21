package io.github.jhspetersson.turtlecommander.dialog
import io.github.jhspetersson.turtlecommander.model.FileEntry

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import io.github.jhspetersson.turtlecommander.ui.installStandardContextMenu
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Path
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
import javax.swing.event.DocumentEvent

enum class ArchiveFormat(val extension: String, val label: String) {
    ZIP(".zip", ".zip"),
    TAR_GZ(".tar.gz", ".tar.gz"),
}

internal fun archiveFormatOf(path: String): ArchiveFormat? =
    ArchiveFormat.entries.firstOrNull { path.endsWith(it.extension, ignoreCase = true) }

internal fun withArchiveExtension(path: String, format: ArchiveFormat): String? {
    val current = archiveFormatOf(path) ?: return null
    if (current == format) return path
    return path.dropLast(current.extension.length) + format.extension
}

class PackDialog(
    project: Project,
    private val sources: List<FileEntry>,
    defaultArchivePath: String,
    private val basePath: Path,
) : DialogWrapper(project) {

    val archivePath: String get() {
        val path = archiveField.text.trim()
        return withArchiveExtension(path, archiveFormat) ?: path
    }
    val archiveFormat: ArchiveFormat get() = formatCombo.selectedItem as? ArchiveFormat ?: ArchiveFormat.ZIP
    val deleteAfterPacking: Boolean get() = deleteCheckBox.isSelected

    internal val archiveField = JBTextField(defaultArchivePath)
    internal val formatCombo = ComboBox(DefaultComboBoxModel(ArchiveFormat.entries.toTypedArray())).apply {
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

        archiveFormatOf(defaultArchivePath.trim())?.let { formatCombo.selectedItem = it }
        formatCombo.addActionListener {
            val newFormat = formatCombo.selectedItem as? ArchiveFormat ?: return@addActionListener
            val current = archiveField.text.trim()
            withArchiveExtension(current, newFormat)?.takeIf { it != current }?.let { archiveField.text = it }
        }
        archiveField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val typed = archiveFormatOf(archiveField.text.trim()) ?: return
                if (typed != formatCombo.selectedItem) formatCombo.selectedItem = typed
            }
        })
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

    public override fun doValidate(): ValidationInfo? =
        validatePackArchivePath(archivePath, basePath, sources.map { it.path })?.let { ValidationInfo(it, archiveField) }
}
