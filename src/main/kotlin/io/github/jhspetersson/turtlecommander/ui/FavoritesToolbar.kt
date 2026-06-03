package io.github.jhspetersson.turtlecommander.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.jhspetersson.turtlecommander.action.EdtAction
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.ide.CopyPasteManager
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

/** Lightweight menu entry that runs [handler] when chosen. */
internal class FavoriteMenuItem(
    text: String,
    icon: Icon? = null,
    private val handler: () -> Unit,
) : EdtAction(text, null, icon) {
    override fun actionPerformed(e: AnActionEvent) = handler()
}

/**
 * A favorite directory rendered as a toolbar button. Left-click opens it in a
 * new tab; right-click opens a context menu (mirroring the directory tab menu)
 * with open / reveal / copy-path / remove actions.
 */
internal class FavoriteAction(
    val favPath: String,
    index: Int,
    colorHex: String,
    private val project: Project,
) : EdtAction(), CustomComponentAction {
    init {
        val path = Path.of(favPath)
        val name = path.fileName?.toString() ?: favPath
        templatePresentation.setText(name, false)
        val shortcut = if (index in 1..9) "<br>Ctrl+$index" else ""
        templatePresentation.description = "<html>$favPath$shortcut</html>"
        templatePresentation.icon = favoriteIcon(colorHex)
        templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val button = ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        button.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                e.consume()
                val px = e.x
                val py = e.y
                // Defer until the button finishes processing this mouse event; showing the
                // popup synchronously leaves the button in a transient non-showing state,
                // which makes the platform abort the chosen menu action.
                SwingUtilities.invokeLater { showContextMenu(button, px, py) }
            }
        })
        return button
    }

    override fun actionPerformed(e: AnActionEvent) {
        openInNewTab()
    }

    private fun openInNewTab() {
        val panel = project.service<FileManagerStateService>().getActivePanel() ?: return
        panel.openDirectoryInNewTab(Path.of(favPath))
    }

    private fun showContextMenu(component: JComponent, x: Int, y: Int) {
        val path = Path.of(favPath)
        val group = DefaultActionGroup()

        group.add(FavoriteMenuItem("Open in New Tab", AllIcons.Actions.OpenNewTab) { openInNewTab() })
        group.add(FavoriteMenuItem(RevealFileAction.getActionName(), AllIcons.Actions.MenuOpen) {
            RevealFileAction.openDirectory(path)
        })

        group.addSeparator()

        val copyAs = DefaultActionGroup("Copy as", true).apply {
            templatePresentation.icon = AllIcons.Actions.Copy
        }
        copyAs.add(FavoriteMenuItem("Directory Name") { CopyPasteManager.copyTextToClipboard(path.fileName?.toString() ?: favPath) })
        copyAs.add(FavoriteMenuItem("Full Path") { CopyPasteManager.copyTextToClipboard(favPath) })
        copyAs.add(FavoriteMenuItem("Parent Path") { CopyPasteManager.copyTextToClipboard(path.parent?.toString() ?: "") })
        group.add(copyAs)

        group.addSeparator()

        group.add(FavoriteMenuItem("Remove from Favorites", AllIcons.Actions.Close) {
            project.service<FileManagerStateService>().removeFavorite(favPath)
        })

        if (!component.isShowing) return
        // Anchor the popup's data-context to the (stable) active panel rather than the
        // toolbar button: the tool-window header recreates its custom-component buttons
        // when focus moves to the popup, which would otherwise leave the chosen menu
        // action's target component "not showing" and make the platform abort it.
        val targetComponent = project.service<FileManagerStateService>().getActivePanel() ?: component
        val popupMenu = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.POPUP, group)
        popupMenu.setTargetComponent(targetComponent)
        popupMenu.component.show(component, x, y)
    }
}

internal class RemoveFavoriteAction(
    private val favPath: String,
    private val project: Project,
) : EdtAction() {
    init {
        val name = Path.of(favPath).fileName?.toString() ?: favPath
        templatePresentation.text = "Remove $name from favorites"
        templatePresentation.icon = AllIcons.Actions.Close
        templatePresentation.hoveredIcon = AllIcons.Actions.CloseHovered
    }

    override fun actionPerformed(e: AnActionEvent) {
        project.service<FileManagerStateService>().removeFavorite(favPath)
    }
}

internal class FavoriteOverflowAction(
    private val overflowEntries: List<FileManagerStateService.FavoriteEntry>,
    private val startIndex: Int,
    private val project: Project,
) : EdtAction("More Favorites...", "Show more favorites", AllIcons.General.ChevronDown) {

    override fun actionPerformed(e: AnActionEvent) {
        val popupMenu = JPopupMenu()
        for ((i, entry) in overflowEntries.withIndex()) {
            val name = Path.of(entry.path).fileName?.toString() ?: entry.path
            val menuItem = JMenuItem(name, favoriteIcon(entry.color))
            val favIndex = startIndex + i + 1
            val shortcut = if (favIndex in 1..9) "<br>Ctrl+$favIndex" else ""
            menuItem.toolTipText = "<html>${entry.path}$shortcut</html>"
            menuItem.addActionListener {
                val stateService = project.service<FileManagerStateService>()
                val panel = stateService.getActivePanel() ?: return@addActionListener
                panel.openDirectoryInNewTab(Path.of(entry.path))
            }
            popupMenu.add(menuItem)
        }
        val component = e.inputEvent?.component ?: return
        popupMenu.show(component, 0, component.height)
    }
}

val FAVORITE_PRESET_COLORS = linkedMapOf(
    "None" to "",
    "Blue" to "#5B9BD5",
    "Green" to "#70AD47",
    "Orange" to "#ED7D31",
    "Red" to "#E06666",
    "Purple" to "#A855F7",
    "Teal" to "#4DB6AC",
    "Brown" to "#A0522D",
    "Pink" to "#F48FB1",
    "Gray" to "#9E9E9E",
)

private fun favoriteIcon(colorHex: String): Icon {
    if (colorHex.isBlank()) return AllIcons.Nodes.Folder
    val color = try { Color.decode(colorHex) } catch (_: Exception) { return AllIcons.Nodes.Folder }
    return ColorFolderIcon(color)
}

private class ColorFolderIcon(private val color: Color) : Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = (g.create() as Graphics2D).apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        }
        try {
            g2.color = color
            // Folder tab
            val tabWidth = 6
            val tabHeight = 3
            g2.fillRoundRect(x + 1, y + 2, tabWidth, tabHeight + 1, 2, 2)
            // Folder body
            g2.fillRoundRect(x + 1, y + 4, iconWidth - 2, iconHeight - 6, 3, 3)
        } finally {
            g2.dispose()
        }
    }

    override fun getIconWidth(): Int = 16
    override fun getIconHeight(): Int = 16
}
