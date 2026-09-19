package io.github.jhspetersson.turtlecommander.settings

import org.junit.Assert.*
import org.junit.Test

class ComponentStyleEditorTest {

    @Test
    fun `default style round-trips as default after apply`() {
        val editor = ComponentStyleEditor("x", ComponentStyle())
        val out = ComponentStyle()
        editor.applyTo(out)
        assertEquals(0, out.fontSize)
        assertEquals("", out.fontFamily)
        assertTrue(out.isDefault())
        assertFalse(editor.isModified(ComponentStyle()))
    }

    @Test
    fun `explicit size is preserved`() {
        val editor = ComponentStyleEditor("x", ComponentStyle().apply { fontSize = 13 })
        val out = ComponentStyle()
        editor.applyTo(out)
        assertEquals(13, out.fontSize)
        assertFalse(out.isDefault())
    }

    @Test
    fun `legacy size is used when style size is zero`() {
        val editor = ComponentStyleEditor("x", ComponentStyle(), legacySize = 15)
        val out = ComponentStyle()
        editor.applyTo(out)
        assertEquals(15, out.fontSize)
        assertFalse(editor.isModified(ComponentStyle(), legacySize = 15))
    }

    @Test
    fun `reset to empty style shows default size`() {
        val editor = ComponentStyleEditor("x", ComponentStyle().apply { fontSize = 20 })
        editor.resetFrom(ComponentStyle())
        val out = ComponentStyle()
        editor.applyTo(out)
        assertEquals(0, out.fontSize)
    }
}
