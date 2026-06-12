package io.github.jhspetersson.turtlecommander.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.util.DriveLabels
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

internal class DriveComboRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val display = DriveLabels.getDisplayText(value as? String ?: "")
        return super.getListCellRendererComponent(list, display, index, isSelected, cellHasFocus)
    }
}

internal class DraggableTabbedPaneWrapper(
    tabbedPane: JBTabbedPane,
    private val panel: FileManagerPanel,
) : JPanel(BorderLayout()) {
    init {
        add(tabbedPane, BorderLayout.CENTER)
        isOpaque = false
    }

    override fun paint(g: Graphics) {
        super.paint(g)
        val rect = panel.getDropIndicatorRect() ?: return
        val g2 = g as Graphics2D
        g2.color = Color(0x3574F0) // IntelliJ blue
        g2.fillRect(rect.x, rect.y, rect.width, rect.height)
    }
}

internal class NewTabButton(private val onClick: () -> Unit) : JComponent() {
    private var hovered = false

    init {
        preferredSize = Dimension(16, 16)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "New tab"
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    onClick()
                }
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val icon = if (hovered) AllIcons.General.Add else AllIcons.General.InlineAdd
        val x = (width - icon.iconWidth) / 2
        val y = (height - icon.iconHeight) / 2
        icon.paintIcon(this, g, x, y)
    }
}

internal class ViewModeButton(
    icon: Icon,
    tooltip: String,
    selected: Boolean,
) : JToggleButton(icon, selected) {
    init {
        toolTipText = tooltip
        isFocusable = false
        preferredSize = Dimension(24, 24)
        margin = JBUI.emptyInsets()
        isContentAreaFilled = false
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
    }

    override fun paintComponent(g: Graphics) {
        if (isSelected) {
            val g2 = g as Graphics2D
            g2.color = JBColor(
                Color(0, 0, 0, 30),
                Color(255, 255, 255, 40),
            )
            g2.fillRoundRect(0, 0, width, height, 6, 6)
        }
        super.paintComponent(g)
    }
}

internal class TabCloseButton(private val onClose: () -> Unit) : JComponent() {
    private var hovered = false

    init {
        preferredSize = Dimension(16, 16)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    onClose()
                }
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val icon = if (hovered) AllIcons.Actions.CloseHovered else AllIcons.Actions.Close
        val x = (width - icon.iconWidth) / 2
        val y = (height - icon.iconHeight) / 2
        icon.paintIcon(this, g, x, y)
    }
}
