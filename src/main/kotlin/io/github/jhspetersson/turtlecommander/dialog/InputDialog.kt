package io.github.jhspetersson.turtlecommander.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import io.github.jhspetersson.turtlecommander.ui.installStandardContextMenu
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class InputDialog(
    project: Project? = null,
    title: String,
    private val message: String,
    initialValue: String = "",
) : DialogWrapper(project) {

    private val textField = JBTextField(initialValue)

    val inputValue: String get() = textField.text.trim()

    init {
        this.title = title
        textField.installStandardContextMenu()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(350, 60)
        }
        panel.add(JBLabel(message), BorderLayout.NORTH)
        panel.add(textField, BorderLayout.CENTER)
        return panel
    }

    override fun getPreferredFocusedComponent() = textField
}
