package io.github.jhspetersson.turtlecommander.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.settings.ComponentStyle
import java.awt.*
import java.awt.event.*
import javax.swing.*

class BreadcrumbPathField : JPanel() {
    private val cardLayout = CardLayout()
    private val breadcrumbContent = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
    private val breadcrumbPanel = JPanel(GridBagLayout())
    private val editField = JBTextField()
    private var isEditMode = false
    private var customFont: Font? = null
    private var customFg: Color? = null

    var text: String = ""
        get() = if (isEditMode) editField.text else field
        set(value) {
            field = value
            editField.text = value
            rebuildBreadcrumbs()
            if (isEditMode) switchToBreadcrumbMode()
        }

    var onSegmentClick: ((String) -> Unit)? = null

    init {
        layout = cardLayout
        isOpaque = false
        border = null

        breadcrumbContent.apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    switchToEditMode()
                }
            })
        }

        breadcrumbPanel.apply {
            isOpaque = true
            background = editField.background
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(2, 5),
            )
            cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                weightx = 1.0
            }
            add(breadcrumbContent, gbc)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    switchToEditMode()
                }
            })
        }

        add(breadcrumbPanel, BREADCRUMBS)
        add(editField, EDITOR)
        cardLayout.show(this, BREADCRUMBS)

        editField.addActionListener {
            SwingUtilities.invokeLater { switchToBreadcrumbMode() }
        }

        editField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                switchToBreadcrumbMode()
            }
        })

        editField.registerKeyboardAction(
            { switchToBreadcrumbMode() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            WHEN_FOCUSED,
        )
    }

    fun addActionListener(l: ActionListener) {
        editField.addActionListener(l)
    }

    private fun switchToEditMode() {
        isEditMode = true
        editField.text = text
        cardLayout.show(this, EDITOR)
        editField.requestFocusInWindow()
        editField.selectAll()
    }

    private fun switchToBreadcrumbMode() {
        isEditMode = false
        cardLayout.show(this, BREADCRUMBS)
    }

    private fun rebuildBreadcrumbs() {
        breadcrumbContent.removeAll()
        val segments = splitPath(text)
        val linkColor = JBColor(Color(0x2470B3), Color(0x589DF6))

        for ((i, segment) in segments.withIndex()) {
            if (i > 0) {
                val sep = JLabel(" \u203A ").apply {
                    if (customFont != null) font = customFont
                    foreground = JBColor.GRAY
                    addMouseListener(object : MouseAdapter() {
                        override fun mousePressed(e: MouseEvent) {
                            e.consume()
                            switchToEditMode()
                        }
                    })
                }
                breadcrumbContent.add(sep)
            }

            val label = JLabel(segment.name).apply {
                if (customFont != null) font = customFont
                if (customFg != null) foreground = customFg
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                val defaultFg = foreground
                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        e.consume()
                        onSegmentClick?.invoke(segment.fullPath)
                    }

                    override fun mouseEntered(e: MouseEvent) {
                        foreground = linkColor
                    }

                    override fun mouseExited(e: MouseEvent) {
                        foreground = defaultFg
                    }
                })
            }
            breadcrumbContent.add(label)
        }

        breadcrumbContent.revalidate()
        breadcrumbContent.repaint()
    }

    private data class PathSegment(val name: String, val fullPath: String)

    private fun splitPath(pathStr: String): List<PathSegment> {
        if (pathStr.isEmpty()) return emptyList()

        val segments = mutableListOf<PathSegment>()
        val isWindows = pathStr.length >= 2 && pathStr[1] == ':'

        if (isWindows) {
            val parts = pathStr.split("\\")
            for (i in parts.indices) {
                val part = parts[i]
                if (part.isEmpty()) continue
                val displayName = if (i == 0) "$part\\" else part
                val fullPath = if (i == 0) "$part\\" else parts.take(i + 1).joinToString("\\")
                segments.add(PathSegment(displayName, fullPath))
            }
        } else {
            val parts = pathStr.split("/")
            if (parts.isNotEmpty() && parts[0].isEmpty()) {
                segments.add(PathSegment("/", "/"))
            }
            for (i in parts.indices) {
                val part = parts[i]
                if (part.isEmpty()) continue
                val fullPath = parts.take(i + 1).joinToString("/").ifEmpty { "/" }
                segments.add(PathSegment(part, fullPath))
            }
        }

        return segments
    }

    fun applyStyle(style: ComponentStyle) {
        customFont = style.getFont(editField.font)
        customFg = style.getFontColor()
        if (customFont != null) editField.font = customFont
        if (customFg != null) editField.foreground = customFg
        rebuildBreadcrumbs()
    }

    companion object {
        private const val BREADCRUMBS = "breadcrumbs"
        private const val EDITOR = "editor"
    }
}
