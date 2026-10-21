package io.github.jhspetersson.turtlecommander.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke

class BreadcrumbEditResetTest : BasePlatformTestCase() {

    private fun BreadcrumbPathField.editor(): JTextField = components.filterIsInstance<JTextField>().single()

    private fun BreadcrumbPathField.clickBreadcrumbs() {
        val panel = components.filterIsInstance<JPanel>().single()
        val event = MouseEvent(panel, MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1)
        panel.mouseListeners.forEach { it.mousePressed(event) }
    }

    private fun BreadcrumbPathField.pressEscape() {
        val editor = editor()
        val action = editor.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))
        action.actionPerformed(ActionEvent(editor, ActionEvent.ACTION_PERFORMED, null))
    }

    fun testEnteringEditModeShowsCurrentPathAfterCancelledEdit() {
        val field = BreadcrumbPathField()
        field.text = "C:\\projects\\app"

        field.clickBreadcrumbs()
        field.editor().text = "C:\\typed\\junk"
        field.pressEscape()
        field.clickBreadcrumbs()

        assertEquals("C:\\projects\\app", field.editor().text)
    }

    fun testTextReportsCurrentPathAfterCancelledEdit() {
        val field = BreadcrumbPathField()
        field.text = "/home/user"

        field.clickBreadcrumbs()
        field.editor().text = "/typed/junk"
        field.pressEscape()

        assertEquals("/home/user", field.text)
    }

    fun testEnteringEditModeAfterNavigationShowsNewPath() {
        val field = BreadcrumbPathField()
        field.text = "/home/user"

        field.clickBreadcrumbs()
        field.editor().text = "/typed/junk"
        field.pressEscape()
        field.text = "/var/log"
        field.clickBreadcrumbs()

        assertEquals("/var/log", field.editor().text)
    }
}
