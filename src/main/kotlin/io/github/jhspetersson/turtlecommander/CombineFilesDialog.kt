package io.github.jhspetersson.turtlecommander

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class CombineFilesDialog(
    project: Project,
    private val chunkCount: Int,
    private val totalSize: Long,
    private val hasCrc: Boolean,
    targetDirectory: String,
    targetFileName: String,
) : DialogWrapper(project) {

    private val targetDirField = JBTextField(targetDirectory)
    private val targetFileField = JBTextField(targetFileName)

    val targetDirectory: String get() = targetDirField.text.trim()
    val targetFile: String get() = targetFileField.text.trim()

    init {
        title = "Combine Files"
        setOKButtonText("Combine")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            minimumSize = Dimension(500, 160)
            preferredSize = Dimension(500, 160)
        }

        val info = "$chunkCount parts, ${formatSize(totalSize)} total" +
            if (hasCrc) " (CRC will be verified)" else ""
        panel.add(JBLabel(info).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(8)
        })

        panel.add(JBLabel("Target directory:").apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        panel.add(targetDirField.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        })

        panel.add(JBLabel("Target file name:").apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(8)
        })
        panel.add(targetFileField.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        })

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (targetDirectory.isEmpty()) {
            return ValidationInfo("Target directory cannot be empty", targetDirField)
        }
        if (targetFile.isEmpty()) {
            return ValidationInfo("Target file name cannot be empty", targetFileField)
        }
        return null
    }
}
