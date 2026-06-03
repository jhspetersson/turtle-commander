package io.github.jhspetersson.turtlecommander.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.util.formatSize
import java.awt.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*

data class DriveInfo(
    val label: String,
    val totalSpace: Long,
    val freeSpace: Long,
    val usedSpace: Long,
)

fun collectDriveInfo(): List<DriveInfo> {
    val result = mutableListOf<DriveInfo>()
    for (root in File.listRoots()) {
        try {
            val store = Files.getFileStore(root.toPath())
            val total = store.totalSpace
            if (total <= 0) continue
            val free = store.usableSpace
            val used = total - free
            val label = buildString {
                append(root.absolutePath.trimEnd(File.separatorChar, '/'))
                val storeName = store.name()
                if (storeName.isNotBlank()) {
                    append(" ($storeName)")
                }
            }
            result.add(DriveInfo(label, total, free, used))
        } catch (_: Exception) {
            // skip inaccessible drives
        }
    }

    // On Unix, also add mounted volumes
    if (!SystemInfo.isWindows) {
        val mountDirs = listOf("/Volumes", "/media", "/mnt")
        for (mountDir in mountDirs) {
            val dir = Path.of(mountDir)
            if (!Files.isDirectory(dir)) continue
            try {
                Files.newDirectoryStream(dir).use { stream ->
                    for (entry in stream) {
                        if (!Files.isDirectory(entry)) continue
                        try {
                            val store = Files.getFileStore(entry)
                            val total = store.totalSpace
                            if (total <= 0) continue
                            if (result.any { it.label.startsWith(entry.toString()) }) continue
                            val free = store.usableSpace
                            val used = total - free
                            val label = buildString {
                                append(entry.toString())
                                val storeName = store.name()
                                if (storeName.isNotBlank()) {
                                    append(" ($storeName)")
                                }
                            }
                            result.add(DriveInfo(label, total, free, used))
                        } catch (_: Exception) {
                            // skip
                        }
                    }
                }
            } catch (_: Exception) {
                // skip
            }
        }
    }

    return result
}

class DriveSpaceDialog(
    project: Project,
    private val drives: List<DriveInfo>,
) : DialogWrapper(project) {

    init {
        title = "Drive Space"
        setOKButtonText("Close")
        setCancelButtonText("")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        for ((index, info) in drives.withIndex()) {
            panel.add(createDrivePanel(info, index))
            panel.add(Box.createVerticalStrut(12))
        }

        return JBScrollPane(panel).apply {
            preferredSize = Dimension(480, drives.size.coerceIn(1, 6) * 80 + 16)
            border = BorderFactory.createEmptyBorder()
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun createDrivePanel(info: DriveInfo, index: Int): JPanel {
        val panel = JPanel(BorderLayout(8, 4)).apply {
            border = JBUI.Borders.empty(4, 8)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 64)
        }

        val label = JBLabel("${info.label}  —  ${formatSize(info.freeSpace)} free of ${formatSize(info.totalSpace)}").apply {
            font = font.deriveFont(Font.BOLD)
        }
        panel.add(label, BorderLayout.NORTH)

        val bar = DriveSpaceBar(info, index)
        bar.preferredSize = Dimension(0, 24)
        panel.add(bar, BorderLayout.CENTER)

        return panel
    }

    private class DriveSpaceBar(private val info: DriveInfo, index: Int) : JPanel() {

        companion object {
            private val COLORS = arrayOf(
                Color(0x4A90D9),
                Color(0x50B86C),
                Color(0xE8A838),
                Color(0xD95040),
                Color(0x9B59B6),
                Color(0x1ABC9C),
                Color(0xE67E22),
                Color(0x3498DB),
            )
        }

        private val barColor = COLORS[index % COLORS.size]

        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val w = width
            val h = height
            val arc = 8

            // Background (free space)
            g2.color = JBColor.lazy { UIManager.getColor("Panel.background").darker() }
            g2.fillRoundRect(0, 0, w, h, arc, arc)

            // Used space
            val usedFraction = if (info.totalSpace > 0) info.usedSpace.toDouble() / info.totalSpace else 0.0
            val usedWidth = (w * usedFraction).toInt().coerceIn(0, w)
            if (usedWidth > 0) {
                g2.color = barColor
                g2.fillRoundRect(0, 0, usedWidth, h, arc, arc)
                // If used portion doesn't fill the full width, square off the right edge
                if (usedWidth < w - arc) {
                    g2.fillRect(usedWidth - arc, 0, arc, h)
                }
            }

            // Percentage text
            val pct = if (info.totalSpace > 0) (info.usedSpace * 100 / info.totalSpace) else 0
            val text = "${pct}% used"
            g2.font = font.deriveFont(11f)
            g2.color = JBColor.lazy { UIManager.getColor("Label.foreground") }
            val fm = g2.fontMetrics
            val textX = (w - fm.stringWidth(text)) / 2
            val textY = (h + fm.ascent - fm.descent) / 2
            g2.drawString(text, textX, textY)

            g2.dispose()
        }
    }
}
