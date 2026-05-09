package io.github.jhspetersson.turtlecommander.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import javax.swing.Icon
import javax.swing.text.JTextComponent

fun JTextComponent.installStandardContextMenu() {
    val field = this
    val am = ActionManager.getInstance()

    val group = DefaultActionGroup().apply {
        // Cut / Paste / Delete are mutating ops — disable on read-only fields,
        // where the underlying JTextComponent.cut/paste/replaceSelection are
        // already no-ops but the menu would otherwise still light up.
        add(textFieldAction("Cut", AllIcons.Actions.MenuCut, { field.cut() }) {
            field.isEditable && field.selectedText != null
        })
        add(textFieldAction("Copy", AllIcons.Actions.Copy, { field.copy() }) { field.selectedText != null })
        add(textFieldAction("Paste", AllIcons.Actions.MenuPaste, { field.paste() }) {
            field.isEditable && try {
                Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
            } catch (_: Exception) {
                false
            }
        })
        add(Separator.create())
        add(textFieldAction("Delete", AllIcons.Actions.GC, { field.replaceSelection("") }) {
            field.isEditable && field.selectedText != null
        })
        add(Separator.create())
        add(textFieldAction("Select All", null, { field.selectAll() }) { field.text.isNotEmpty() })
    }

    val popupMenu = am.createActionPopupMenu(ActionPlaces.EDITOR_POPUP, group)
    componentPopupMenu = popupMenu.component
}

private fun textFieldAction(
    text: String,
    icon: Icon?,
    action: () -> Unit,
    isEnabled: () -> Boolean,
) = object : AnAction(text, null, icon) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) = action()
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isEnabled()
    }
}
