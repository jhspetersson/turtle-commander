package io.github.jhspetersson.turtlecommander

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingUtilities

class CopyProgressDialog(
    project: Project,
    private val totalFiles: Int,
) : DialogWrapper(project, false) {

    private val progressBar = JProgressBar(0, totalFiles)
    private val currentFileLabel = JBLabel(" ")
    private val statusLabel = JBLabel("Copying 0 / $totalFiles")
    @Volatile
    var cancelled = false
        private set

    init {
        title = "Copying Files"
        setOKButtonText("Cancel")
        setCancelButtonText("Cancel")
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(400, 80)
        }

        panel.add(statusLabel.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        panel.add(currentFileLabel.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = javax.swing.BorderFactory.createEmptyBorder(4, 0, 4, 0)
        })
        panel.add(progressBar.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            isStringPainted = true
        })

        return panel
    }

    fun updateProgress(copiedCount: Int, currentFileName: String) {
        SwingUtilities.invokeLater {
            progressBar.value = copiedCount
            statusLabel.text = "Copying $copiedCount / $totalFiles"
            currentFileLabel.text = currentFileName
        }
    }

    fun onComplete() {
        SwingUtilities.invokeLater {
            close(OK_EXIT_CODE)
        }
    }

    override fun doCancelAction() {
        cancelled = true
        super.doCancelAction()
    }

    override fun doOKAction() {
        cancelled = true
        super.doOKAction()
    }

    override fun createActions() = arrayOf(cancelAction)
}
