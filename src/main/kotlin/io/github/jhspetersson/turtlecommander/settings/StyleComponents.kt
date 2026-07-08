package io.github.jhspetersson.turtlecommander.settings

import com.intellij.openapi.application.ApplicationBundle
import com.intellij.ui.ColorChooserService
import com.intellij.ui.FontComboBox
import com.intellij.ui.GroupedComboBoxRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.ui.FontInfo
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.ui.FAVORITE_PRESET_COLORS
import java.awt.*
import javax.swing.*

internal class ComponentStyleEditor(
    val label: String,
    style: ComponentStyle,
    legacyFamily: String = "",
    legacySize: Int = 0,
) {
    val fontCombo = FontComboBox(false, false, true).apply {
        preferredSize = Dimension(160, preferredSize.height)
        maximumSize = Dimension(160, maximumSize.height)
        renderer = object : GroupedComboBoxRenderer<Any?>(this) {
            override fun separatorFor(value: Any?): ListSeparator? {
                if (value !is FontInfo) return null
                var firstMono: FontInfo? = null
                var firstProportional: FontInfo? = null
                for (i in 0 until model.size) {
                    val info = model.getElementAt(i) as? FontInfo ?: continue
                    if (info.isMonospaced) {
                        if (firstMono == null) firstMono = info
                    } else {
                        if (firstProportional == null) firstProportional = info
                    }
                    if (firstMono != null && firstProportional != null) break
                }
                return when (value) {
                    firstMono -> ListSeparator(ApplicationBundle.message("settings.editor.font.monospaced"))
                    firstProportional -> ListSeparator(ApplicationBundle.message("settings.editor.font.proportional"))
                    else -> null
                }
            }

            override fun customize(
                item: SimpleColoredComponent, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ) {
                item.font = if (value is FontInfo && index != -1) value.getFont() else JBUI.Fonts.label()
                item.append(value?.toString().orEmpty())
            }
        }
    }
    val sizeSpinner = JSpinner(SpinnerNumberModel(13, 8, 48, 1)).apply {
        preferredSize = Dimension(60, preferredSize.height)
    }
    val styleCombo = ComboBox(DefaultComboBoxModel(arrayOf("Plain", "Bold", "Italic", "Bold Italic")))
    val colorButton = ColorPickerButton("...")
    val bgColorButton = ColorPickerButton("...")
    val selectedColorButton = ColorPickerButton("...")
    val activeSelectedColorButton = ColorPickerButton("...")

    init {
        resetFrom(style, legacyFamily, legacySize)
    }

    fun resetFrom(style: ComponentStyle, legacyFamily: String = "", legacySize: Int = 0) {
        val family = style.fontFamily.ifEmpty { legacyFamily }
        fontCombo.fontName = family.takeIf { it.isNotEmpty() }
        val size = if (style.fontSize > 0) style.fontSize else if (legacySize > 0) legacySize else 13
        sizeSpinner.value = size
        styleCombo.selectedIndex = when (style.fontStyle) {
            Font.BOLD -> 1
            Font.ITALIC -> 2
            Font.BOLD or Font.ITALIC -> 3
            else -> 0
        }
        colorButton.setColor(style.parsedFontColor())
        bgColorButton.setColor(style.parsedBackgroundColor())
        selectedColorButton.setColor(style.parsedSelectedColor())
        activeSelectedColorButton.setColor(style.parsedActiveSelectedColor())
    }

    fun applyTo(style: ComponentStyle) {
        style.fontFamily = if (fontCombo.isNoFontSelected) "" else fontCombo.fontName.orEmpty()
        style.fontSize = (sizeSpinner.value as? Number)?.toInt() ?: 0
        style.fontStyle = when (styleCombo.selectedIndex) {
            1 -> Font.BOLD
            2 -> Font.ITALIC
            3 -> Font.BOLD or Font.ITALIC
            else -> Font.PLAIN
        }
        style.fontColor = colorButton.getColorHex()
        style.backgroundColor = bgColorButton.getColorHex()
        style.selectedColor = selectedColorButton.getColorHex()
        style.activeSelectedColor = activeSelectedColorButton.getColorHex()
    }

    fun isModified(style: ComponentStyle, legacyFamily: String = "", legacySize: Int = 0): Boolean {
        val tmp = ComponentStyle()
        applyTo(tmp)
        val expected = ComponentStyle().apply {
            fontFamily = style.fontFamily.ifEmpty { legacyFamily }
            fontSize = if (style.fontSize > 0) style.fontSize else if (legacySize > 0) legacySize else 13
            fontStyle = style.fontStyle
            fontColor = style.fontColor
            backgroundColor = style.backgroundColor
            selectedColor = style.selectedColor
            activeSelectedColor = style.activeSelectedColor
        }
        return tmp.fontFamily != expected.fontFamily
            || tmp.fontSize != expected.fontSize
            || tmp.fontStyle != expected.fontStyle
            || tmp.fontColor != expected.fontColor
            || tmp.backgroundColor != expected.backgroundColor
            || tmp.selectedColor != expected.selectedColor
            || tmp.activeSelectedColor != expected.activeSelectedColor
    }
}

/**
 * Shows an IntelliJ-style [JBPopupFactory] list popup of preset colors (each with a swatch) plus a
 * "Custom..." entry that opens the platform color chooser, so every color picker in settings looks
 * like the icon picker. [onPick] receives the chosen color, or null for the "None" preset.
 */
internal fun showColorPopup(anchor: JComponent, title: String, current: Color?, onPick: (Color?) -> Unit) {
    val presets = FAVORITE_PRESET_COLORS.entries.toList()
    val items: List<Map.Entry<String, String>?> = presets + listOf(null) // null = "Custom..."
    val step = object : BaseListPopupStep<Map.Entry<String, String>?>(title, items) {
        override fun getTextFor(value: Map.Entry<String, String>?): String = value?.key ?: "Custom..."
        override fun getIconFor(value: Map.Entry<String, String>?): Icon? {
            val hex = value?.value
            return if (hex.isNullOrEmpty()) null else runCatching { ColorSwatchIcon(Color.decode(hex)) }.getOrNull()
        }
        override fun isSpeedSearchEnabled(): Boolean = true
        override fun onChosen(selectedValue: Map.Entry<String, String>?, finalChoice: Boolean): PopupStep<*>? {
            if (selectedValue == null) {
                return doFinalStep {
                    val chosen = ColorChooserService.getInstance()
                        .showDialog(anchor, title, current ?: JBColor.GRAY, true, emptyList(), true)
                    if (chosen != null) onPick(chosen)
                }
            }
            val hex = selectedValue.value
            return doFinalStep { onPick(if (hex.isEmpty()) null else Color.decode(hex)) }
        }
    }
    JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(anchor)
}

internal class ColorPickerButton(text: String) : JButton(text) {
    private var selectedColor: Color? = null
    var onChange: (() -> Unit)? = null

    init {
        preferredSize = Dimension(28, 28)
        isFocusable = false
        addActionListener { showColorPopup(this, "Choose Color", selectedColor) { setColor(it) } }
    }

    fun setColor(color: Color?) {
        selectedColor = color
        icon = if (color != null) ColorSwatchIcon(color) else null
        text = if (color != null) "" else "..."
        toolTipText = if (color != null) String.format("#%02X%02X%02X", color.red, color.green, color.blue) else "Default"
        onChange?.invoke()
    }

    fun getColorHex(): String {
        val c = selectedColor ?: return ""
        return String.format("#%02X%02X%02X", c.red, c.green, c.blue)
    }
}

/**
 * Picks a [RuleIcons] override icon. Opens a platform [com.intellij.openapi.ui.popup.JBPopup]
 * list popup with type-to-filter speed search and a category separator above each group; the
 * first row ("None") clears the override. [onChange] fires after every selection so the rule
 * editor can refresh its live preview.
 */
internal class IconPickerButton : JButton("...") {
    private var iconId: String = ""
    var onChange: (() -> Unit)? = null

    init {
        preferredSize = Dimension(28, 28)
        isFocusable = false
        addActionListener { showPopup() }
    }

    private fun showPopup() = showIconChooserPopup(this) { setIconId(it) }

    fun setIconId(id: String) {
        iconId = id
        val ic = RuleIcons.resolve(id)
        icon = ic
        text = if (ic != null) "" else "..."
        toolTipText = if (id.isEmpty()) "Default (file-type icon)" else (RuleIcons.label(id) ?: id)
        onChange?.invoke()
    }

    fun getIconId(): String = iconId
}

/**
 * Shows the two-level icon chooser ([RuleIcons] categories → entries, with a leading "None")
 * underneath [anchor] and invokes [onPick] with the chosen [RuleIcons.Entry.key] (empty for
 * "None"). Shared by the colorization rule editor's [IconPickerButton] and the favorites editor.
 */
internal fun showIconChooserPopup(anchor: JComponent, onPick: (String) -> Unit) {
    val entriesByCategory = RuleIcons.ENTRIES.groupBy { it.category } // insertion-ordered
    // Top level: "None" (null) followed by each category, which opens a submenu.
    val topItems: List<String?> = listOf(null) + entriesByCategory.keys
    val top = object : BaseListPopupStep<String?>("Choose Icon", topItems) {
        override fun getTextFor(value: String?): String = value ?: "None"
        override fun hasSubstep(selectedValue: String?): Boolean = selectedValue != null
        override fun onChosen(selectedValue: String?, finalChoice: Boolean): PopupStep<*>? {
            if (selectedValue == null) return doFinalStep { onPick("") }
            val entries = entriesByCategory[selectedValue].orEmpty()
            return object : BaseListPopupStep<RuleIcons.Entry>(selectedValue, entries) {
                override fun getTextFor(value: RuleIcons.Entry): String = value.label
                override fun getIconFor(value: RuleIcons.Entry): Icon = value.icon
                override fun isSpeedSearchEnabled(): Boolean = true
                override fun onChosen(value: RuleIcons.Entry, finalChoice: Boolean): PopupStep<*>? =
                    doFinalStep { onPick(value.key) }
            }
        }
    }
    JBPopupFactory.getInstance().createListPopup(top).showUnderneathOf(anchor)
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
