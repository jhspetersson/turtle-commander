package io.github.jhspetersson.turtlecommander.ui

import com.intellij.ui.scale.JBUIScale
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

object DirectoryIcons {

    /**
     * Overlays a dot of the given color on the [base] icon (any file-type, folder, or
     * rule-chosen override icon). The dot color is supplied by the colorization rule engine —
     * see [io.github.jhspetersson.turtlecommander.settings.ColorRule].
     */
    fun iconWithDot(base: Icon, color: Color): Icon = DotOverlayIcon(base, color)
}

private class DotOverlayIcon(
    private val baseIcon: Icon,
    private val dotColor: Color,
) : Icon {

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        baseIcon.paintIcon(c, g, x, y)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val dotSize = JBUIScale.scale(5)
        val dotX = x + iconWidth - dotSize
        val dotY = y + iconHeight - dotSize
        g2.color = dotColor
        g2.fillOval(dotX, dotY, dotSize, dotSize)
        g2.dispose()
    }

    override fun getIconWidth(): Int = baseIcon.iconWidth
    override fun getIconHeight(): Int = baseIcon.iconHeight
}
