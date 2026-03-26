package io.github.jhspetersson.turtlecommander.settings

import org.junit.Assert.*
import org.junit.Test
import java.awt.Color
import java.awt.Font

class ComponentStyleTest {

    // --- isDefault ---

    @Test
    fun `default style is default`() {
        assertTrue(ComponentStyle().isDefault())
    }

    @Test
    fun `style with custom font family is not default`() {
        val style = ComponentStyle().apply { fontFamily = "Monospaced" }
        assertFalse(style.isDefault())
    }

    @Test
    fun `style with custom font size is not default`() {
        val style = ComponentStyle().apply { fontSize = 14 }
        assertFalse(style.isDefault())
    }

    @Test
    fun `style with custom font style is not default`() {
        val style = ComponentStyle().apply { fontStyle = Font.BOLD }
        assertFalse(style.isDefault())
    }

    @Test
    fun `style with custom font color is not default`() {
        val style = ComponentStyle().apply { fontColor = "#FF0000" }
        assertFalse(style.isDefault())
    }

    // --- getFont ---

    @Test
    fun `getFont returns null for default style`() {
        assertNull(ComponentStyle().getFont(Font(Font.DIALOG, Font.PLAIN, 12)))
    }

    @Test
    fun `getFont returns custom font when family is set`() {
        val style = ComponentStyle().apply { fontFamily = "Monospaced" }
        val font = style.getFont(null)
        assertNotNull(font)
        assertEquals("Monospaced", font!!.family)
    }

    @Test
    fun `getFont returns custom font when size is set`() {
        val style = ComponentStyle().apply { fontSize = 20 }
        val font = style.getFont(Font(Font.DIALOG, Font.PLAIN, 12))
        assertNotNull(font)
        assertEquals(20, font!!.size)
    }

    @Test
    fun `getFont returns custom font when style is bold`() {
        val style = ComponentStyle().apply { fontStyle = Font.BOLD }
        val font = style.getFont(Font(Font.DIALOG, Font.PLAIN, 12))
        assertNotNull(font)
        assertEquals(Font.BOLD, font!!.style)
    }

    @Test
    fun `getFont uses default size when fontSize is zero`() {
        val style = ComponentStyle().apply { fontFamily = "Monospaced" }
        val font = style.getFont(Font(Font.DIALOG, Font.PLAIN, 15))
        assertEquals(15, font!!.size)
    }

    @Test
    fun `getFont uses fallback size 13 when no default font`() {
        val style = ComponentStyle().apply { fontFamily = "Monospaced" }
        val font = style.getFont(null)
        assertEquals(13, font!!.size)
    }

    // --- getFontColor ---

    @Test
    fun `getFontColor returns null for empty color`() {
        assertNull(ComponentStyle().getFontColor())
    }

    @Test
    fun `getFontColor returns null for blank color`() {
        val style = ComponentStyle().apply { fontColor = "   " }
        assertNull(style.getFontColor())
    }

    @Test
    fun `getFontColor parses valid hex color`() {
        val style = ComponentStyle().apply { fontColor = "#FF0000" }
        assertEquals(Color.RED, style.getFontColor())
    }

    @Test
    fun `getFontColor returns null for invalid color`() {
        val style = ComponentStyle().apply { fontColor = "not-a-color" }
        assertNull(style.getFontColor())
    }

    // --- copyFrom ---

    @Test
    fun `copyFrom copies all fields`() {
        val source = ComponentStyle().apply {
            fontFamily = "Serif"
            fontSize = 18
            fontStyle = Font.ITALIC
            fontColor = "#00FF00"
        }
        val target = ComponentStyle()
        target.copyFrom(source)

        assertEquals("Serif", target.fontFamily)
        assertEquals(18, target.fontSize)
        assertEquals(Font.ITALIC, target.fontStyle)
        assertEquals("#00FF00", target.fontColor)
    }

    // --- equals and hashCode ---

    @Test
    fun `equal styles are equal`() {
        val a = ComponentStyle().apply { fontFamily = "Serif"; fontSize = 12 }
        val b = ComponentStyle().apply { fontFamily = "Serif"; fontSize = 12 }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different styles are not equal`() {
        val a = ComponentStyle().apply { fontFamily = "Serif" }
        val b = ComponentStyle().apply { fontFamily = "Sans" }
        assertNotEquals(a, b)
    }
}
