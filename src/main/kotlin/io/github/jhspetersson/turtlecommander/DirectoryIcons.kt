package io.github.jhspetersson.turtlecommander

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

object DirectoryIcons {

    private val dotColors = mapOf(
        DirectoryType.IDEA_PROJECT to JBColor(Color(0x3574B8), Color(0x5A9AD8)),
        DirectoryType.GIT to JBColor(Color(0x7A8A5C), Color(0x9AAA7C)),
        DirectoryType.GRADLE to JBColor(Color(0x3A7A6A), Color(0x69B7A2)),
        DirectoryType.MAVEN to JBColor(Color(0x5A7A9A), Color(0x7A9ABC)),
        DirectoryType.CARGO to JBColor(Color(0x8A7A60), Color(0xB0A080)),
        DirectoryType.NPM to JBColor(Color(0x5A8A7A), Color(0x7AB0A0)),
        DirectoryType.PYTHON to JBColor(Color(0x4A7A9A), Color(0x7AAAC0)),
        DirectoryType.CMAKE to JBColor(Color(0x5A6A8A), Color(0x7A8AAA)),
        DirectoryType.DOTNET to JBColor(Color(0x7A6A9A), Color(0x9A8ABB)),
    )

    private val textColors = mapOf(
        DirectoryType.IDEA_PROJECT to JBColor(Color(0x2A64A8), Color(0x5A9AD8)),
        DirectoryType.GIT to JBColor(Color(0x5A7A4A), Color(0x8AAA7A)),
        DirectoryType.GRADLE to JBColor(Color(0x2A6A5A), Color(0x69B7A2)),
        DirectoryType.MAVEN to JBColor(Color(0x4A6A8A), Color(0x7A9ABC)),
        DirectoryType.CARGO to JBColor(Color(0x7A6A50), Color(0xB0A080)),
        DirectoryType.NPM to JBColor(Color(0x4A7A6A), Color(0x7AB0A0)),
        DirectoryType.PYTHON to JBColor(Color(0x3A6A8A), Color(0x7AAAC0)),
        DirectoryType.CMAKE to JBColor(Color(0x4A5A7A), Color(0x7A8AAA)),
        DirectoryType.DOTNET to JBColor(Color(0x6A5A8A), Color(0x9A8ABB)),
    )

    private val iconCache = mutableMapOf<DirectoryType, Icon>()

    fun getIcon(type: DirectoryType): Icon {
        if (type == DirectoryType.NONE) return AllIcons.Nodes.Folder
        return iconCache.getOrPut(type) {
            DotOverlayIcon(AllIcons.Nodes.Folder, dotColors[type] ?: JBColor.GRAY)
        }
    }

    fun getColor(type: DirectoryType): Color {
        return textColors[type] ?: JBColor.foreground()
    }
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
