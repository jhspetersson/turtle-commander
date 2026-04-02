package io.github.jhspetersson.turtlecommander.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.settings.ComponentStyle
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.event.PopupMenuEvent

class BreadcrumbPathField : JPanel() {
    private val cardLayout = CardLayout()
    private val breadcrumbContent = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
    private val breadcrumbPanel = JPanel(GridBagLayout())
    private val editField = JBTextField()
    private var isEditMode = false
    private var customFont: Font? = null
    private var customFg: Color? = null
    private val defaultEditFieldFont by lazy { editField.font }
    private val defaultEditFieldFg = UIManager.getColor("TextField.foreground")
    private val defaultEditFieldBg = UIManager.getColor("TextField.background")

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
                if (e.isTemporary) return
                val opposite = e.oppositeComponent
                if (opposite != null && SwingUtilities.isDescendingFrom(opposite, editField.componentPopupMenu)) return
                switchToBreadcrumbMode()
            }
        })

        editField.componentPopupMenu = createEditFieldPopupMenu()

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

    private fun createEditFieldPopupMenu(): JPopupMenu {
        val menu = JPopupMenu()

        val itemBorder = JBUI.Borders.empty(3, 6, 3, 0)
        menu.add(JMenuItem("Cut", AllIcons.Actions.MenuCut).apply {
            border = itemBorder
            addActionListener { editField.cut() }
        })
        menu.add(JMenuItem("Copy", AllIcons.Actions.Copy).apply {
            border = itemBorder
            addActionListener { editField.copy() }
        })
        menu.add(JMenuItem("Paste", AllIcons.Actions.MenuPaste).apply {
            border = itemBorder
            addActionListener { editField.paste() }
        })
        menu.addSeparator()
        menu.add(JMenuItem("Delete", AllIcons.Actions.GC).apply {
            border = itemBorder
            addActionListener { editField.replaceSelection("") }
        })
        menu.addSeparator()
        menu.add(JMenuItem("Select All").apply {
            border = itemBorder
            addActionListener { editField.selectAll() }
        })

        menu.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                val hasSelection = editField.selectedText != null
                val hasClipboard = try {
                    Toolkit.getDefaultToolkit().systemClipboard.getContents(null) != null
                } catch (_: Exception) {
                    false
                }
                menu.components.filterIsInstance<JMenuItem>().forEach { item ->
                    when (item.text) {
                        "Cut", "Delete" -> item.isEnabled = hasSelection
                        "Copy" -> item.isEnabled = hasSelection
                        "Paste" -> item.isEnabled = hasClipboard
                        "Select All" -> item.isEnabled = editField.text.isNotEmpty()
                    }
                }
                menu.preferredSize = Dimension(
                    maxOf(menu.preferredSize.width, JBUI.scale(180)),
                    menu.preferredSize.height,
                )
            }

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}
            override fun popupMenuCanceled(e: PopupMenuEvent) {}
        })

        return menu
    }

    fun applyStyle(style: ComponentStyle) {
        customFont = style.getFont(defaultEditFieldFont)
        customFg = style.parsedFontColor()
        editField.font = customFont ?: defaultEditFieldFont
        editField.foreground = customFg ?: defaultEditFieldFg
        val bg = style.parsedBackgroundColor()
        if (bg != null) {
            editField.background = bg
            isOpaque = true
            background = bg
            breadcrumbContent.isOpaque = true
            breadcrumbContent.background = bg
            breadcrumbPanel.isOpaque = true
            breadcrumbPanel.background = bg
        } else {
            editField.background = defaultEditFieldBg
            isOpaque = false
            breadcrumbContent.isOpaque = false
            breadcrumbPanel.isOpaque = false
        }
        rebuildBreadcrumbs()
    }

    companion object {
        private const val BREADCRUMBS = "breadcrumbs"
        private const val EDITOR = "editor"
    }
}
