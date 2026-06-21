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
    // Available breadcrumb width the trail was last laid out for; lets the resize listener
    // skip rebuilds that don't change the fitting and avoids a revalidate feedback loop.
    private var lastLayoutWidth = -1
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

        // Re-fit the trail whenever the available width changes so the tail stays visible as
        // the panel/splitter is resized. Guarded on the computed width to avoid looping with
        // the revalidate inside rebuildBreadcrumbs.
        breadcrumbPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (availableBreadcrumbWidth() != lastLayoutWidth) rebuildBreadcrumbs()
            }
        })

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

        editField.installStandardContextMenu()

        editField.registerKeyboardAction(
            { switchToBreadcrumbMode() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            WHEN_FOCUSED,
        )
    }

    fun addActionListener(l: ActionListener) {
        editField.addActionListener(l)
    }

    /**
     * The breadcrumb trail is laid out in a [FlowLayout], whose minimum width equals the full
     * single-row width of every segment. Left unchecked that flows up through the header into
     * the [com.intellij.ui.OnePixelSplitter] — which honours component minimum sizes — so a
     * deep path (e.g. an archive opened inside another archive) forces the splitter to widen
     * this panel. Report a small fixed minimum width instead: the field still fills its
     * BorderLayout.CENTER slot, and the breadcrumbs clip to whatever width is available.
     */
    override fun getMinimumSize(): Dimension =
        Dimension(JBUI.scale(40), super.getMinimumSize().height)

    /**
     * Same reasoning for the preferred width: cap it so a long path can't balloon the panel's
     * preferred size (which feeds the tool window's layout). Height still follows the content.
     */
    override fun getPreferredSize(): Dimension {
        val pref = super.getPreferredSize()
        return Dimension(minOf(pref.width, JBUI.scale(200)), pref.height)
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

        val available = availableBreadcrumbWidth()
        lastLayoutWidth = available
        val metricsFont = customFont ?: UIManager.getFont("Label.font")
        val fm = breadcrumbContent.getFontMetrics(metricsFont)
        val displays = fitSegments(segments, available, fm)

        for ((i, segment) in segments.withIndex()) {
            if (i > 0) {
                val sep = JLabel(SEPARATOR).apply {
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

            val label = JLabel(displays[i]).apply {
                if (customFont != null) font = customFont
                if (customFg != null) foreground = customFg
                toolTipText = segment.name
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

    /** Width inside the breadcrumb panel's border that the trail may occupy, or -1 if unknown. */
    private fun availableBreadcrumbWidth(): Int {
        val w = breadcrumbPanel.width
        if (w <= 0) return -1
        val insets = breadcrumbPanel.insets
        return w - insets.left - insets.right
    }

    /**
     * Chooses the display text for each segment so the whole trail fits [available] pixels,
     * favouring the tail: the deepest (current-location) segments keep as much of their name as
     * possible, while leading segments are squeezed first \u2014 but every segment is always rendered
     * (down to a single letter + ellipsis), and each label still carries the full name as a
     * tooltip so any ancestor stays clickable. Falls back to the 30-char cap when the width is
     * not yet known (initial build before the first layout); the resize listener re-fits later.
     */
    private fun fitSegments(segments: List<PathSegment>, available: Int, fm: FontMetrics): List<String> {
        val desired = segments.map { shortenMiddle(it.name, 30) }
        if (available <= 0 || segments.isEmpty()) return desired

        val sepWidth = fm.stringWidth(SEPARATOR)
        val budget = available - sepWidth * (segments.size - 1)
        val minDisplays = segments.map { minimalDisplay(it.name) }
        if (budget <= 0) return minDisplays

        val desiredW = desired.map { fm.stringWidth(it) }
        if (desiredW.sum() <= budget) return desired

        val minW = minDisplays.map { fm.stringWidth(it) }
        val result = MutableList(segments.size) { "" }
        var used = 0
        // Allocate from the tail toward the head, reserving each earlier segment's minimal
        // width so the head can never be starved below one letter.
        for (i in segments.indices.reversed()) {
            val reservedForEarlier = (0 until i).sumOf { minW[it] }
            val availForThis = budget - used - reservedForEarlier
            val text = when {
                availForThis <= minW[i] -> minDisplays[i]
                desiredW[i] <= availForThis -> desired[i]
                else -> truncateMiddleToWidth(desired[i], availForThis, fm)
            }
            result[i] = text
            used += fm.stringWidth(text)
        }
        return result
    }

    private fun minimalDisplay(name: String): String =
        if (name.length <= 1) name else name.substring(0, 1) + "\u2026"

    /** Longest middle-elided form of [s] whose rendered width is <= [maxWidth]. */
    private fun truncateMiddleToWidth(s: String, maxWidth: Int, fm: FontMetrics): String {
        if (fm.stringWidth(s) <= maxWidth) return s
        val ellipsis = "\u2026"
        var left = (s.length + 1) / 2
        var right = s.length / 2
        while (left + right > 1) {
            val candidate = s.substring(0, left) + ellipsis + s.substring(s.length - right)
            if (fm.stringWidth(candidate) <= maxWidth) return candidate
            if (left >= right && left > 0) left-- else if (right > 0) right--
        }
        return minimalDisplay(s)
    }

    private fun shortenMiddle(s: String, maxLen: Int): String {
        if (s.length <= maxLen) return s
        val ellipsis = "\u2026"
        val keep = maxLen - 1
        val left = (keep + 1) / 2
        val right = keep - left
        return s.substring(0, left) + ellipsis + s.substring(s.length - right)
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
        private const val SEPARATOR = " › "
    }
}
