package io.github.jhspetersson.turtlecommander.settings

import org.junit.Assert.*
import org.junit.Before
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
    fun `classic NC theme sets expected panel colors`() {
        val state = TurtleCommanderSettings.State()
        Theme.CLASSIC_NC.applyTo(state)
        assertEquals("#00FFFF", state.panelStyle.fontColor)
        assertEquals("#000080", state.panelStyle.backgroundColor)
        assertEquals("Consolas", state.panelStyle.fontFamily)
        assertEquals(13, state.panelStyle.fontSize)
    }

    @Test
    fun `classic NC tab text is readable on dark background`() {
        val style = Theme.CLASSIC_NC.tabStyle.toComponentStyle()
        val fg = style.parsedFontColor()!!
        val bg = style.parsedBackgroundColor()!!
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
        Theme.CLASSIC_NC.applyTo(state)
        assertFalse(state.panelStyle.isDefault())
        assertFalse(state.tabStyle.isDefault())
        assertFalse(state.pathBarStyle.isDefault())
        assertFalse(state.statusBarStyle.isDefault())
        assertFalse(state.commandBarStyle.isDefault())
        assertFalse(state.driveSelectorStyle.isDefault())
        assertFalse(state.columnHeaderStyle.isDefault())
    }

    @Test
    fun `INITIAL_THEMES contains six themes`() {
        assertEquals(6, Theme.INITIAL_THEMES.size)
        assertEquals("Default", Theme.INITIAL_THEMES[0].name)
        assertEquals("Classic NC", Theme.INITIAL_THEMES[1].name)
        assertEquals("Green Terminal", Theme.INITIAL_THEMES[2].name)
        assertEquals("Brown Oldschool", Theme.INITIAL_THEMES[3].name)
        assertEquals("Grey Ash", Theme.INITIAL_THEMES[4].name)
        assertEquals("Monochrome", Theme.INITIAL_THEMES[5].name)
    }

    @Test
    fun `grey ash theme sets warm grey colors`() {
        val state = TurtleCommanderSettings.State()
        Theme.GREY_ASH.applyTo(state)
        assertEquals("#CCCCBB", state.panelStyle.fontColor)
        assertEquals("#3D3D35", state.panelStyle.backgroundColor)
        assertEquals("Consolas", state.panelStyle.fontFamily)
    }

    @Test
    fun `monochrome theme sets white on black`() {
        val state = TurtleCommanderSettings.State()
        Theme.MONOCHROME.applyTo(state)
        assertEquals("#FFFFFF", state.panelStyle.fontColor)
        assertEquals("#000000", state.panelStyle.backgroundColor)
    }

    @Test
    fun `monochrome theme uses black background for all components`() {
        val theme = Theme.MONOCHROME
        for ((label, style) in listOf(
            "panel" to theme.panelStyle,
            "tab" to theme.tabStyle,
            "pathBar" to theme.pathBarStyle,
            "statusBar" to theme.statusBarStyle,
            "commandBar" to theme.commandBarStyle,
            "driveSelector" to theme.driveSelectorStyle,
            "columnHeader" to theme.columnHeaderStyle,
        )) {
            assertEquals("Monochrome/$label background should be #000000",
                "#000000", style.backgroundColor)
        }
    }

    @Test
    fun `theme toString returns name`() {
        assertEquals("Classic NC", Theme.CLASSIC_NC.toString())
    }

    @Test
    fun `all non-default initial themes have readable contrast on every component`() {
        for (theme in Theme.INITIAL_THEMES) {
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
                val fg = style.parsedFontColor() ?: continue
                val bg = style.parsedBackgroundColor() ?: continue
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
        assertNotNull(style.parsedFontColor())
        assertNotNull(style.parsedBackgroundColor())
        assertEquals(0xFF, style.parsedFontColor()!!.red)
        assertEquals(0xFF, style.parsedBackgroundColor()!!.blue)
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
        assertFalse(ComponentStyle().apply { backgroundColor = "#112233" }.isDefault())
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
        assertNull(ComponentStyle().parsedBackgroundColor())
    }

    @Test
    fun `getBackgroundColor returns null for invalid hex`() {
        assertNull(ComponentStyle().apply { backgroundColor = "not-a-color" }.parsedBackgroundColor())
    }

    // --- Initial themes invariants ---

    @Test
    fun `all initial themes have unique names`() {
        val names = Theme.INITIAL_THEMES.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `all non-default initial themes set all seven components`() {
        for (theme in Theme.INITIAL_THEMES) {
            if (theme.name == Theme.DEFAULT.name) continue
            for ((label, style) in listOf(
                "panel" to theme.panelStyle,
                "tab" to theme.tabStyle,
                "pathBar" to theme.pathBarStyle,
                "statusBar" to theme.statusBarStyle,
                "commandBar" to theme.commandBarStyle,
                "driveSelector" to theme.driveSelectorStyle,
                "columnHeader" to theme.columnHeaderStyle,
            )) {
                assertFalse("${theme.name}/$label style should not be default",
                    style.toComponentStyle().isDefault())
                assertTrue("${theme.name}/$label should have font color",
                    style.fontColor.isNotEmpty())
                assertTrue("${theme.name}/$label should have background color",
                    style.backgroundColor.isNotEmpty())
                assertTrue("${theme.name}/$label should have font family",
                    style.fontFamily.isNotEmpty())
                assertTrue("${theme.name}/$label should have font size",
                    style.fontSize > 0)
            }
        }
    }

    // --- Theme.applyTo overwrites ---

    @Test
    fun `applyTo overwrites previously applied theme`() {
        val state = TurtleCommanderSettings.State()
        Theme.CLASSIC_NC.applyTo(state)
        assertEquals("#000080", state.panelStyle.backgroundColor)
        Theme.GREEN_TERMINAL.applyTo(state)
        assertEquals("#0A0A0A", state.panelStyle.backgroundColor)
        assertEquals("#33FF33", state.panelStyle.fontColor)
    }

    @Test
    fun `applying default theme clears styles set by another theme`() {
        val state = TurtleCommanderSettings.State()
        Theme.CLASSIC_NC.applyTo(state)
        assertFalse(state.panelStyle.isDefault())
        Theme.DEFAULT.applyTo(state)
        assertTrue(state.panelStyle.isDefault())
        assertTrue(state.tabStyle.isDefault())
    }

    // --- ThemeStyle ---

    @Test
    fun `ThemeStyle toComponentStyle copies all fields`() {
        val ts = ThemeStyle("Courier", 14, Font.BOLD, "#AABBCC", "#112233")
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

    @Test
    fun `ThemeStyle fromComponentStyle round-trips`() {
        val cs = ComponentStyle().apply {
            fontFamily = "Arial"; fontSize = 16; fontStyle = Font.ITALIC
            fontColor = "#AABBCC"; backgroundColor = "#112233"
        }
        val ts = ThemeStyle.fromComponentStyle(cs)
        val cs2 = ts.toComponentStyle()
        assertEquals(cs.fontFamily, cs2.fontFamily)
        assertEquals(cs.fontSize, cs2.fontSize)
        assertEquals(cs.fontStyle, cs2.fontStyle)
        assertEquals(cs.fontColor, cs2.fontColor)
        assertEquals(cs.backgroundColor, cs2.backgroundColor)
    }

    // --- Theme.applyTo ---

    @Test
    fun `applyTo does not touch non-style state fields`() {
        val state = TurtleCommanderSettings.State().apply {
            enableFileNameHighlighting = false
            showCommandBar = false
            defaultViewMode = "LIST"
            themeName = "something"
        }
        Theme.CLASSIC_NC.applyTo(state)
        assertFalse(state.enableFileNameHighlighting)
        assertFalse(state.showCommandBar)
        assertEquals("LIST", state.defaultViewMode)
        assertEquals("something", state.themeName)
    }

    // --- Pre-theme snapshot ---

    @Test
    fun `pre-theme styles are independent from active styles`() {
        val state = TurtleCommanderSettings.State()
        state.preThemePanelStyle.fontColor = "#FF0000"
        state.preThemePanelStyle.backgroundColor = "#0000FF"
        assertEquals("", state.panelStyle.fontColor)
        assertEquals("", state.panelStyle.backgroundColor)
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
    fun `theme can be found by name in initial themes`() {
        for (theme in Theme.INITIAL_THEMES) {
            val found = Theme.INITIAL_THEMES.firstOrNull { it.name == theme.name }
            assertNotNull("Theme '${theme.name}' not found", found)
        }
    }

    @Test
    fun `empty themeName resolves to Default index 0`() {
        val idx = Theme.INITIAL_THEMES.indexOfFirst { it.name == "".ifEmpty { Theme.DEFAULT.name } }
        assertEquals(0, idx)
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
        assertEquals("", ColorPickerButton("...").getColorHex())
    }

    // --- ComponentStyleEditor ---

    @Test
    fun `ComponentStyleEditor round-trips all fields including backgroundColor`() {
        val fontItems = arrayOf("(Default)", "Arial", "Courier New")
        val original = ComponentStyle().apply {
            fontFamily = "Arial"; fontSize = 14; fontColor = "#FF0000"; backgroundColor = "#0000FF"
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
        val editor = ComponentStyleEditor("Test", fontItems, ComponentStyle().apply { backgroundColor = "#112233" })
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
        editor.bgColorButton.setColor(java.awt.Color.RED)
        assertTrue(editor.isModified(saved))
    }
}
