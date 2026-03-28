package io.github.jhspetersson.turtlecommander.settings

import com.intellij.ui.ColorChooserService
import com.intellij.ui.JBColor
import com.intellij.openapi.ui.ComboBox
import io.github.jhspetersson.turtlecommander.ui.FAVORITE_PRESET_COLORS
import java.awt.*
import javax.swing.*

internal class ComponentStyleEditor(
    val label: String,
    fontItems: Array<String>,
    style: ComponentStyle,
    legacyFamily: String = "",
    legacySize: Int = 0,
) {
    private val defaultLabel = "(Default)"
    val fontCombo = ComboBox(DefaultComboBoxModel(fontItems)).apply {
        preferredSize = Dimension(160, preferredSize.height)
        maximumSize = Dimension(160, maximumSize.height)
    }
    val sizeSpinner = JSpinner(SpinnerNumberModel(13, 8, 48, 1)).apply {
        preferredSize = Dimension(60, preferredSize.height)
    }
    val styleCombo = ComboBox(DefaultComboBoxModel(arrayOf("Plain", "Bold", "Italic", "Bold Italic")))
    val colorButton = ColorPickerButton("...")

    init {
        resetFrom(style, legacyFamily, legacySize)
    }

    fun resetFrom(style: ComponentStyle, legacyFamily: String = "", legacySize: Int = 0) {
        val family = style.fontFamily.ifEmpty { legacyFamily }
        fontCombo.selectedItem = family.ifEmpty { defaultLabel }
        val size = if (style.fontSize > 0) style.fontSize else if (legacySize > 0) legacySize else 13
        sizeSpinner.value = size
        styleCombo.selectedIndex = when (style.fontStyle) {
            Font.BOLD -> 1
            Font.ITALIC -> 2
            Font.BOLD or Font.ITALIC -> 3
            else -> 0
        }
        colorButton.setColor(style.getFontColor())
    }

    fun applyTo(style: ComponentStyle) {
        val sel = fontCombo.selectedItem as? String ?: ""
        style.fontFamily = if (sel == defaultLabel) "" else sel
        style.fontSize = (sizeSpinner.value as? Number)?.toInt() ?: 0
        style.fontStyle = when (styleCombo.selectedIndex) {
            1 -> Font.BOLD
            2 -> Font.ITALIC
            3 -> Font.BOLD or Font.ITALIC
            else -> Font.PLAIN
        }
        style.fontColor = colorButton.getColorHex()
    }

    fun isModified(style: ComponentStyle, legacyFamily: String = "", legacySize: Int = 0): Boolean {
        val tmp = ComponentStyle()
        applyTo(tmp)
        val expected = ComponentStyle().apply {
            fontFamily = style.fontFamily.ifEmpty { legacyFamily }
            fontSize = if (style.fontSize > 0) style.fontSize else if (legacySize > 0) legacySize else 13
            fontStyle = style.fontStyle
            fontColor = style.fontColor
        }
        return tmp.fontFamily != expected.fontFamily
            || tmp.fontSize != expected.fontSize
            || tmp.fontStyle != expected.fontStyle
            || tmp.fontColor != expected.fontColor
    }
}

internal class ColorPickerButton(text: String) : JButton(text) {
    private var selectedColor: Color? = null

    init {
        preferredSize = Dimension(28, 28)
        isFocusable = false
        addActionListener {
            val popup = JPopupMenu()
            val presets = FAVORITE_PRESET_COLORS
            for ((name, hex) in presets) {
                val item = JMenuItem(name)
                if (hex.isNotEmpty()) {
                    item.icon = ColorSwatchIcon(Color.decode(hex))
                }
                item.addActionListener {
                    setColor(if (hex.isEmpty()) null else Color.decode(hex))
                }
                popup.add(item)
            }
            popup.addSeparator()
            val customItem = JMenuItem("Custom...")
            customItem.addActionListener {
                val chosen = ColorChooserService.getInstance().showDialog(
                    this, "Choose Color", selectedColor ?: JBColor.GRAY, true, emptyList(), true
                )
                if (chosen != null) {
                    setColor(chosen)
                }
            }
            popup.add(customItem)
            popup.show(this, 0, height)
        }
    }

    fun setColor(color: Color?) {
        selectedColor = color
        icon = if (color != null) ColorSwatchIcon(color) else null
        text = if (color != null) "" else "..."
        toolTipText = if (color != null) String.format("#%02X%02X%02X", color.red, color.green, color.blue) else "Default"
    }

    fun getColorHex(): String {
        val c = selectedColor ?: return ""
        return String.format("#%02X%02X%02X", c.red, c.green, c.blue)
    }
}

internal class ColorSwatchIcon(private val color: Color) : Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = (g.create() as Graphics2D).apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        }
        try {
            g2.color = color
            g2.fillRoundRect(x + 1, y + 1, iconWidth - 2, iconHeight - 2, 4, 4)
        } finally {
            g2.dispose()
        }
    }
    override fun getIconWidth(): Int = 14
    override fun getIconHeight(): Int = 14
}
