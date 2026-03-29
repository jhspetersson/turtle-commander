package io.github.jhspetersson.turtlecommander.settings

import org.junit.Assert.*
import org.junit.Test
import java.awt.Font

class ThemeTest {

    // --- Theme data ---

    @Test
    fun `default theme produces empty component styles`() {
        val state = TurtleCommanderSettings.State()
        Theme.DEFAULT.applyTo(state)
        assertTrue(state.panelStyle.isDefault())
        assertTrue(state.tabStyle.isDefault())
        assertTrue(state.pathBarStyle.isDefault())
        assertTrue(state.statusBarStyle.isDefault())
        assertTrue(state.commandBarStyle.isDefault())
        assertTrue(state.driveSelectorStyle.isDefault())
        assertTrue(state.columnHeaderStyle.isDefault())
    }

    @Test
    fun `norton commander theme sets expected panel colors`() {
        val state = TurtleCommanderSettings.State()
        Theme.NORTON_COMMANDER.applyTo(state)
        assertEquals("#00FFFF", state.panelStyle.fontColor)
        assertEquals("#000080", state.panelStyle.backgroundColor)
        assertEquals("Consolas", state.panelStyle.fontFamily)
        assertEquals(13, state.panelStyle.fontSize)
    }

    @Test
    fun `norton commander tab text is readable on dark background`() {
        val style = Theme.NORTON_COMMANDER.tabStyle.toComponentStyle()
        val fg = style.getFontColor()!!
        val bg = style.getBackgroundColor()!!
        val fgLuminance = 0.299 * fg.red + 0.587 * fg.green + 0.114 * fg.blue
        val bgLuminance = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
        assertTrue("Tab contrast too low: fg=$fgLuminance bg=$bgLuminance",
            Math.abs(fgLuminance - bgLuminance) > 80)
    }

    @Test
    fun `green terminal theme sets green on black`() {
        val state = TurtleCommanderSettings.State()
        Theme.GREEN_TERMINAL.applyTo(state)
        assertEquals("#33FF33", state.panelStyle.fontColor)
        assertEquals("#0A0A0A", state.panelStyle.backgroundColor)
    }

    @Test
    fun `brown oldschool theme sets gold on brown`() {
        val state = TurtleCommanderSettings.State()
        Theme.BROWN_OLDSCHOOL.applyTo(state)
        assertEquals("#FFD700", state.panelStyle.fontColor)
        assertEquals("#3B2507", state.panelStyle.backgroundColor)
        assertEquals("Courier New", state.panelStyle.fontFamily)
    }

    @Test
    fun `theme applies all seven component styles`() {
        val state = TurtleCommanderSettings.State()
        Theme.NORTON_COMMANDER.applyTo(state)
        assertFalse(state.panelStyle.isDefault())
        assertFalse(state.tabStyle.isDefault())
        assertFalse(state.pathBarStyle.isDefault())
        assertFalse(state.statusBarStyle.isDefault())
        assertFalse(state.commandBarStyle.isDefault())
        assertFalse(state.driveSelectorStyle.isDefault())
        assertFalse(state.columnHeaderStyle.isDefault())
    }

    @Test
    fun `ALL_THEMES contains exactly four themes`() {
        assertEquals(4, Theme.ALL_THEMES.size)
        assertEquals("Default", Theme.ALL_THEMES[0].name)
        assertEquals("Norton Commander", Theme.ALL_THEMES[1].name)
        assertEquals("Green Terminal", Theme.ALL_THEMES[2].name)
        assertEquals("Brown Oldschool", Theme.ALL_THEMES[3].name)
    }

    @Test
    fun `theme toString returns name`() {
        assertEquals("Norton Commander", Theme.NORTON_COMMANDER.toString())
    }

    @Test
    fun `all non-default themes have readable contrast on every component`() {
        for (theme in Theme.ALL_THEMES) {
            if (theme.name == Theme.DEFAULT.name) continue
            for ((label, themeStyle) in listOf(
                "panel" to theme.panelStyle,
                "tab" to theme.tabStyle,
                "pathBar" to theme.pathBarStyle,
                "statusBar" to theme.statusBarStyle,
                "commandBar" to theme.commandBarStyle,
                "driveSelector" to theme.driveSelectorStyle,
                "columnHeader" to theme.columnHeaderStyle,
            )) {
                val style = themeStyle.toComponentStyle()
                val fg = style.getFontColor() ?: continue
                val bg = style.getBackgroundColor() ?: continue
                val fgL = 0.299 * fg.red + 0.587 * fg.green + 0.114 * fg.blue
                val bgL = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
                assertTrue("${theme.name}/$label contrast too low: fg=$fgL bg=$bgL",
                    Math.abs(fgL - bgL) > 50)
            }
        }
    }

    // --- ComponentStyle ---

    @Test
    fun `component style backgroundColor round-trips`() {
        val style = ComponentStyle().apply {
            fontColor = "#FF0000"
            backgroundColor = "#0000FF"
        }
        assertNotNull(style.getFontColor())
        assertNotNull(style.getBackgroundColor())
        assertEquals(0xFF, style.getFontColor()!!.red)
        assertEquals(0xFF, style.getBackgroundColor()!!.blue)
    }

    @Test
    fun `component style copyFrom includes backgroundColor`() {
        val src = ComponentStyle().apply { backgroundColor = "#AABB00" }
        val dst = ComponentStyle()
        dst.copyFrom(src)
        assertEquals("#AABB00", dst.backgroundColor)
    }

    @Test
    fun `component style isDefault false when backgroundColor set`() {
        val style = ComponentStyle().apply { backgroundColor = "#112233" }
        assertFalse(style.isDefault())
    }

    @Test
    fun `component style isDefault true when all fields empty`() {
        assertTrue(ComponentStyle().isDefault())
    }

    @Test
    fun `component style equals includes backgroundColor`() {
        val a = ComponentStyle().apply { backgroundColor = "#FF0000" }
        val b = ComponentStyle().apply { backgroundColor = "#FF0000" }
        val c = ComponentStyle().apply { backgroundColor = "#00FF00" }
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `component style hashCode includes backgroundColor`() {
        val a = ComponentStyle().apply { backgroundColor = "#FF0000" }
        val b = ComponentStyle().apply { backgroundColor = "#FF0000" }
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `getBackgroundColor returns null for empty string`() {
        assertNull(ComponentStyle().getBackgroundColor())
    }

    @Test
    fun `getBackgroundColor returns null for invalid hex`() {
        val style = ComponentStyle().apply { backgroundColor = "not-a-color" }
        assertNull(style.getBackgroundColor())
    }

    // --- ThemeStyle / toComponentStyle ---

    @Test
    fun `ThemeStyle toComponentStyle copies all fields`() {
        val ts = ThemeStyle(
            fontFamily = "Courier",
            fontSize = 14,
            fontStyle = Font.BOLD,
            fontColor = "#AABBCC",
            backgroundColor = "#112233",
        )
        val cs = ts.toComponentStyle()
        assertEquals("Courier", cs.fontFamily)
        assertEquals(14, cs.fontSize)
        assertEquals(Font.BOLD, cs.fontStyle)
        assertEquals("#AABBCC", cs.fontColor)
        assertEquals("#112233", cs.backgroundColor)
    }

    @Test
    fun `ThemeStyle defaults produce default ComponentStyle`() {
        assertTrue(ThemeStyle().toComponentStyle().isDefault())
    }

    // --- Theme.applyTo preserves unrelated state fields ---

    @Test
    fun `applyTo does not touch non-style state fields`() {
        val state = TurtleCommanderSettings.State().apply {
            enableFileNameHighlighting = false
            showCommandBar = false
            defaultViewMode = "LIST"
            themeName = "something"
        }
        Theme.NORTON_COMMANDER.applyTo(state)
        assertFalse(state.enableFileNameHighlighting)
        assertFalse(state.showCommandBar)
        assertEquals("LIST", state.defaultViewMode)
        assertEquals("something", state.themeName)
    }

    // --- Pre-theme snapshot persistence ---

    @Test
    fun `pre-theme styles are separate fields in state`() {
        val state = TurtleCommanderSettings.State()
        state.preThemePanelStyle.fontColor = "#FF0000"
        state.preThemePanelStyle.backgroundColor = "#0000FF"
        // Verify they are independent from active styles
        assertEquals("", state.panelStyle.fontColor)
        assertEquals("", state.panelStyle.backgroundColor)
        assertEquals("#FF0000", state.preThemePanelStyle.fontColor)
        assertEquals("#0000FF", state.preThemePanelStyle.backgroundColor)
    }

    @Test
    fun `pre-theme styles default to empty`() {
        val state = TurtleCommanderSettings.State()
        assertTrue(state.preThemePanelStyle.isDefault())
        assertTrue(state.preThemeTabStyle.isDefault())
        assertTrue(state.preThemePathBarStyle.isDefault())
        assertTrue(state.preThemeStatusBarStyle.isDefault())
        assertTrue(state.preThemeCommandBarStyle.isDefault())
        assertTrue(state.preThemeDriveSelectorStyle.isDefault())
        assertTrue(state.preThemeColumnHeaderStyle.isDefault())
    }

    // --- Theme name persistence ---

    @Test
    fun `themeName defaults to empty string`() {
        assertEquals("", TurtleCommanderSettings.State().themeName)
    }

    @Test
    fun `theme can be found by name`() {
        for (theme in Theme.ALL_THEMES) {
            val found = Theme.ALL_THEMES.firstOrNull { it.name == theme.name }
            assertNotNull("Theme '${theme.name}' not found by name", found)
            assertSame(theme, found)
        }
    }

    @Test
    fun `empty themeName resolves to Default index 0`() {
        val idx = Theme.ALL_THEMES.indexOfFirst { it.name == "".ifEmpty { Theme.DEFAULT.name } }
        assertEquals(0, idx)
    }

    // --- Switching themes on State level ---

    @Test
    fun `applying theme then default restores original when pre-theme saved`() {
        val state = TurtleCommanderSettings.State()
        // Simulate user's custom styles
        state.panelStyle.fontColor = "#AAAAAA"
        state.panelStyle.backgroundColor = "#333333"
        // Save pre-theme snapshot
        state.preThemePanelStyle.copyFrom(state.panelStyle)
        // Apply NC theme
        Theme.NORTON_COMMANDER.applyTo(state)
        assertEquals("#00FFFF", state.panelStyle.fontColor)
        assertEquals("#000080", state.panelStyle.backgroundColor)
        // Restore from pre-theme
        state.panelStyle.copyFrom(state.preThemePanelStyle)
        assertEquals("#AAAAAA", state.panelStyle.fontColor)
        assertEquals("#333333", state.panelStyle.backgroundColor)
    }

    @Test
    fun `switching between non-default themes does not corrupt pre-theme snapshot`() {
        val state = TurtleCommanderSettings.State()
        // User's custom styles
        state.preThemePanelStyle.fontColor = "#AAAAAA"
        state.preThemePanelStyle.backgroundColor = "#333333"
        // Apply NC
        Theme.NORTON_COMMANDER.applyTo(state)
        // Switch to Green — pre-theme should be untouched
        Theme.GREEN_TERMINAL.applyTo(state)
        assertEquals("#AAAAAA", state.preThemePanelStyle.fontColor)
        assertEquals("#333333", state.preThemePanelStyle.backgroundColor)
        // Active styles should be Green
        assertEquals("#33FF33", state.panelStyle.fontColor)
        assertEquals("#0A0A0A", state.panelStyle.backgroundColor)
    }

    // --- ColorPickerButton ---

    @Test
    fun `ColorPickerButton setColor null clears state`() {
        val btn = ColorPickerButton("...")
        btn.setColor(java.awt.Color.RED)
        assertEquals("#FF0000", btn.getColorHex())
        btn.setColor(null)
        assertEquals("", btn.getColorHex())
    }

    @Test
    fun `ColorPickerButton getColorHex returns empty for no color`() {
        val btn = ColorPickerButton("...")
        assertEquals("", btn.getColorHex())
    }

    // --- ComponentStyleEditor round-trip ---

    @Test
    fun `ComponentStyleEditor resetFrom and applyTo round-trips backgroundColor`() {
        val fontItems = arrayOf("(Default)", "Arial", "Courier New")
        val original = ComponentStyle().apply {
            fontFamily = "Arial"
            fontSize = 14
            fontColor = "#FF0000"
            backgroundColor = "#0000FF"
        }
        val editor = ComponentStyleEditor("Test", fontItems, original)
        val result = ComponentStyle()
        editor.applyTo(result)
        assertEquals("Arial", result.fontFamily)
        assertEquals(14, result.fontSize)
        assertEquals("#FF0000", result.fontColor)
        assertEquals("#0000FF", result.backgroundColor)
    }

    @Test
    fun `ComponentStyleEditor reset to empty clears backgroundColor`() {
        val fontItems = arrayOf("(Default)", "Arial")
        val styled = ComponentStyle().apply { backgroundColor = "#112233" }
        val editor = ComponentStyleEditor("Test", fontItems, styled)
        // Verify it's set
        val tmp = ComponentStyle()
        editor.applyTo(tmp)
        assertEquals("#112233", tmp.backgroundColor)
        // Reset to empty
        editor.resetFrom(ComponentStyle())
        val result = ComponentStyle()
        editor.applyTo(result)
        assertEquals("", result.backgroundColor)
    }

    @Test
    fun `ComponentStyleEditor isModified detects backgroundColor change`() {
        val fontItems = arrayOf("(Default)", "Arial")
        val saved = ComponentStyle().apply { backgroundColor = "#112233" }
        val editor = ComponentStyleEditor("Test", fontItems, saved)
        assertFalse(editor.isModified(saved))
        // Change bg in the editor
        editor.bgColorButton.setColor(java.awt.Color.RED)
        assertTrue(editor.isModified(saved))
    }

    @Test
    fun `ComponentStyleEditor isModified false when backgroundColor matches`() {
        val fontItems = arrayOf("(Default)", "Arial")
        val saved = ComponentStyle().apply { backgroundColor = "#FF0000" }
        val editor = ComponentStyleEditor("Test", fontItems, saved)
        assertFalse(editor.isModified(saved))
    }
}
