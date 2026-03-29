package io.github.jhspetersson.turtlecommander.settings

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.awt.Font

class ThemeManagerTest {

    private lateinit var state: TurtleCommanderSettings.State

    @Before
    fun setUp() {
        state = TurtleCommanderSettings.State()
        // Mark initialized to prevent auto-seeding — tests manage themes explicitly
        state.themesInitialized = true
    }

    private fun customTheme(name: String, fg: String = "#AABBCC", bg: String = "#112233") = Theme(
        name = name,
        panelStyle = ThemeStyle(fontColor = fg, backgroundColor = bg),
        tabStyle = ThemeStyle(fontColor = fg, backgroundColor = bg),
    )

    // --- seeding ---

    @Test
    fun `getAllThemes seeds initial themes on first use`() {
        val fresh = TurtleCommanderSettings.State()
        assertFalse(fresh.themesInitialized)
        val all = ThemeManager.getAllThemes(fresh)
        assertEquals(Theme.INITIAL_THEMES.size, all.size)
        // Default first, rest sorted by name
        assertEquals("Default", all[0].name)
        assertEquals(
            Theme.INITIAL_THEMES.filter { it.name != "Default" }.map { it.name }.sorted(),
            all.drop(1).map { it.name }
        )
        assertTrue(fresh.themesInitialized)
    }

    @Test
    fun `seeding does not repeat after themes are deleted`() {
        val fresh = TurtleCommanderSettings.State()
        ThemeManager.getAllThemes(fresh) // seeds
        // Delete all themes
        for (theme in ThemeManager.getAllThemes(fresh).toList()) {
            ThemeManager.deleteTheme(fresh, theme.name)
        }
        assertTrue(ThemeManager.getAllThemes(fresh).isEmpty())
    }

    @Test
    fun `seeding only happens once`() {
        val fresh = TurtleCommanderSettings.State()
        ThemeManager.getAllThemes(fresh)
        ThemeManager.getAllThemes(fresh)
        assertEquals(Theme.INITIAL_THEMES.size, fresh.themes.size)
    }

    // --- getAllThemes ---

    @Test
    fun `getAllThemes returns empty when initialized with no themes`() {
        val all = ThemeManager.getAllThemes(state)
        assertTrue(all.isEmpty())
    }

    @Test
    fun `getAllThemes returns added themes`() {
        state.themes.add(SavedTheme.fromTheme(customTheme("My Theme")))
        val all = ThemeManager.getAllThemes(state)
        assertEquals(1, all.size)
        assertEquals("My Theme", all[0].name)
    }

    @Test
    fun `getAllThemes sorts themes by name`() {
        state.themes.add(SavedTheme.fromTheme(customTheme("Gamma")))
        state.themes.add(SavedTheme.fromTheme(customTheme("Alpha")))
        state.themes.add(SavedTheme.fromTheme(customTheme("Beta")))
        val all = ThemeManager.getAllThemes(state)
        assertEquals(listOf("Alpha", "Beta", "Gamma"), all.map { it.name })
    }

    @Test
    fun `getAllThemes puts Default first regardless of sort order`() {
        state.themes.add(SavedTheme.fromTheme(customTheme("Zebra")))
        state.themes.add(SavedTheme.fromTheme(Theme(name = "Default")))
        state.themes.add(SavedTheme.fromTheme(customTheme("Alpha")))
        val all = ThemeManager.getAllThemes(state)
        assertEquals("Default", all[0].name)
        assertEquals(listOf("Default", "Alpha", "Zebra"), all.map { it.name })
    }

    // --- saveTheme ---

    @Test
    fun `saveTheme adds new theme`() {
        val result = ThemeManager.saveTheme(state, customTheme("My Theme"))
        assertEquals(ThemeManager.SaveResult.Saved, result)
        assertEquals(1, state.themes.size)
        assertEquals("My Theme", state.themes[0].name)
    }

    @Test
    fun `saveTheme replaces existing theme`() {
        ThemeManager.saveTheme(state, customTheme("My Theme", fg = "#111111"))
        val result = ThemeManager.saveTheme(state, customTheme("My Theme", fg = "#222222"))
        assertEquals(ThemeManager.SaveResult.Replaced, result)
        assertEquals(1, state.themes.size)
        assertEquals("#222222", state.themes[0].panelFontColor)
    }

    @Test
    fun `saveTheme preserves styles in SavedTheme`() {
        val theme = Theme(
            name = "Full Theme",
            panelStyle = ThemeStyle("Consolas", 14, Font.BOLD, "#AA0000", "#001100"),
            tabStyle = ThemeStyle("Arial", 12, Font.ITALIC, "#BB0000", "#002200"),
            pathBarStyle = ThemeStyle("Courier New", 13, Font.PLAIN, "#CC0000", "#003300"),
            statusBarStyle = ThemeStyle("Monospaced", 11, Font.PLAIN, "#DD0000", "#004400"),
            commandBarStyle = ThemeStyle("Dialog", 15, Font.BOLD, "#EE0000", "#005500"),
            commandButtonStyle = ThemeStyle("Dialog", 14, Font.PLAIN, "#DD0000", "#004400"),
            driveSelectorStyle = ThemeStyle("SansSerif", 13, Font.PLAIN, "#FF0000", "#006600"),
            columnHeaderStyle = ThemeStyle("Serif", 13, Font.PLAIN, "#110000", "#007700"),
        )
        ThemeManager.saveTheme(state, theme)
        val restored = state.themes[0].toTheme()
        assertEquals(theme.panelStyle, restored.panelStyle)
        assertEquals(theme.tabStyle, restored.tabStyle)
        assertEquals(theme.pathBarStyle, restored.pathBarStyle)
        assertEquals(theme.statusBarStyle, restored.statusBarStyle)
        assertEquals(theme.commandBarStyle, restored.commandBarStyle)
        assertEquals(theme.commandButtonStyle, restored.commandButtonStyle)
        assertEquals(theme.driveSelectorStyle, restored.driveSelectorStyle)
        assertEquals(theme.columnHeaderStyle, restored.columnHeaderStyle)
    }

    // --- deleteTheme ---

    @Test
    fun `deleteTheme removes theme`() {
        ThemeManager.saveTheme(state, customTheme("My Theme"))
        assertTrue(ThemeManager.deleteTheme(state, "My Theme"))
        assertTrue(state.themes.isEmpty())
    }

    @Test
    fun `deleteTheme returns false for non-existent theme`() {
        assertFalse(ThemeManager.deleteTheme(state, "No Such Theme"))
    }

    @Test
    fun `deleteTheme only removes the specified theme`() {
        ThemeManager.saveTheme(state, customTheme("Alpha"))
        ThemeManager.saveTheme(state, customTheme("Beta"))
        ThemeManager.saveTheme(state, customTheme("Gamma"))
        ThemeManager.deleteTheme(state, "Beta")
        assertEquals(2, state.themes.size)
        assertEquals("Alpha", state.themes[0].name)
        assertEquals("Gamma", state.themes[1].name)
    }

    @Test
    fun `deleteTheme can remove all themes`() {
        ThemeManager.saveTheme(state, customTheme("A"))
        ThemeManager.saveTheme(state, customTheme("B"))
        ThemeManager.deleteTheme(state, "A")
        ThemeManager.deleteTheme(state, "B")
        assertTrue(ThemeManager.getAllThemes(state).isEmpty())
    }

    // --- renameTheme ---

    @Test
    fun `renameTheme renames theme`() {
        ThemeManager.saveTheme(state, customTheme("Old Name"))
        val result = ThemeManager.renameTheme(state, "Old Name", "New Name")
        assertEquals(ThemeManager.RenameResult.Success, result)
        assertEquals("New Name", state.themes[0].name)
    }

    @Test
    fun `renameTheme rejects blank name`() {
        ThemeManager.saveTheme(state, customTheme("Foo"))
        assertEquals(ThemeManager.RenameResult.InvalidName, ThemeManager.renameTheme(state, "Foo", ""))
        assertEquals(ThemeManager.RenameResult.InvalidName, ThemeManager.renameTheme(state, "Foo", "   "))
    }

    @Test
    fun `renameTheme succeeds when old equals new`() {
        ThemeManager.saveTheme(state, customTheme("Foo"))
        assertEquals(ThemeManager.RenameResult.Success, ThemeManager.renameTheme(state, "Foo", "Foo"))
    }

    @Test
    fun `renameTheme rejects conflict with another theme`() {
        ThemeManager.saveTheme(state, customTheme("Alpha"))
        ThemeManager.saveTheme(state, customTheme("Beta"))
        assertEquals(ThemeManager.RenameResult.Conflict, ThemeManager.renameTheme(state, "Alpha", "Beta"))
    }

    @Test
    fun `renameTheme returns NotFound for missing theme`() {
        assertEquals(ThemeManager.RenameResult.NotFound, ThemeManager.renameTheme(state, "Ghost", "New"))
    }

    @Test
    fun `renameTheme preserves theme styles`() {
        val theme = customTheme("Original", fg = "#AAAAAA", bg = "#BBBBBB")
        ThemeManager.saveTheme(state, theme)
        ThemeManager.renameTheme(state, "Original", "Renamed")
        val saved = state.themes[0]
        assertEquals("#AAAAAA", saved.panelFontColor)
        assertEquals("#BBBBBB", saved.panelBackgroundColor)
    }

    // --- exportTheme / importTheme ---

    @Test
    fun `export and import round-trips a theme`() {
        val theme = Theme(
            name = "Exported Theme",
            panelStyle = ThemeStyle("Consolas", 14, Font.BOLD, "#AA0000", "#001100", "#111111", "#222222"),
            tabStyle = ThemeStyle("Arial", 12, Font.ITALIC, "#BB0000", "#002200"),
            pathBarStyle = ThemeStyle("Courier New", 13, Font.PLAIN, "#CC0000", "#003300"),
            statusBarStyle = ThemeStyle("Monospaced", 11, Font.PLAIN, "#DD0000", "#004400"),
            commandBarStyle = ThemeStyle("Dialog", 15, Font.BOLD, "#EE0000", "#005500"),
            commandButtonStyle = ThemeStyle("Dialog", 14, Font.PLAIN, "#DD0000", "#004400"),
            driveSelectorStyle = ThemeStyle("SansSerif", 13, Font.PLAIN, "#FF0000", "#006600"),
            columnHeaderStyle = ThemeStyle("Serif", 13, Font.PLAIN, "#110000", "#007700"),
        )
        val text = ThemeManager.exportTheme(theme)
        val imported = ThemeManager.importTheme(text)
        assertNotNull(imported)
        assertEquals(theme.name, imported!!.name)
        assertEquals(theme.panelStyle, imported.panelStyle)
        assertEquals(theme.tabStyle, imported.tabStyle)
        assertEquals(theme.pathBarStyle, imported.pathBarStyle)
        assertEquals(theme.statusBarStyle, imported.statusBarStyle)
        assertEquals(theme.commandBarStyle, imported.commandBarStyle)
        assertEquals(theme.commandButtonStyle, imported.commandButtonStyle)
        assertEquals(theme.driveSelectorStyle, imported.driveSelectorStyle)
        assertEquals(theme.columnHeaderStyle, imported.columnHeaderStyle)
    }

    @Test
    fun `export default theme produces minimal output`() {
        val text = ThemeManager.exportTheme(Theme.DEFAULT)
        assertTrue(text.contains("name=Default"))
        val styleLines = text.lines().filter { it.contains(".font") || it.contains(".background") }
        assertTrue(styleLines.isEmpty())
    }

    @Test
    fun `import returns null for empty text`() {
        assertNull(ThemeManager.importTheme(""))
    }

    @Test
    fun `import returns null for text without name`() {
        assertNull(ThemeManager.importTheme("panel.fontColor=#FF0000"))
    }

    @Test
    fun `import returns null for blank name`() {
        assertNull(ThemeManager.importTheme("name=   "))
    }

    @Test
    fun `import ignores comment lines`() {
        val text = """
            # This is a comment
            name=Test
            panel.fontColor=#FF0000
        """.trimIndent()
        val theme = ThemeManager.importTheme(text)
        assertNotNull(theme)
        assertEquals("Test", theme!!.name)
        assertEquals("#FF0000", theme.panelStyle.fontColor)
    }

    @Test
    fun `import ignores blank lines`() {
        val text = "name=Test\n\n\npanel.fontColor=#FF0000\n\n"
        val theme = ThemeManager.importTheme(text)
        assertNotNull(theme)
        assertEquals("Test", theme!!.name)
    }

    @Test
    fun `import ignores lines without equals sign`() {
        val text = "name=Test\nbadline\npanel.fontColor=#FF0000"
        val theme = ThemeManager.importTheme(text)
        assertNotNull(theme)
        assertEquals("#FF0000", theme!!.panelStyle.fontColor)
    }

    @Test
    fun `import trims whitespace around keys and values`() {
        val text = "  name  =  My Theme  \n  panel.fontColor  =  #FF0000  "
        val theme = ThemeManager.importTheme(text)
        assertNotNull(theme)
        assertEquals("My Theme", theme!!.name)
        assertEquals("#FF0000", theme.panelStyle.fontColor)
    }

    @Test
    fun `export and import round-trips fontStyle`() {
        val theme = Theme(
            name = "Styled",
            panelStyle = ThemeStyle("Consolas", 14, Font.BOLD, "#AA0000", "#001100"),
            tabStyle = ThemeStyle("Arial", 12, Font.ITALIC, "#BB0000", "#002200"),
            commandBarStyle = ThemeStyle("Dialog", 15, Font.BOLD or Font.ITALIC, "#EE0000", "#005500"),
        )
        val text = ThemeManager.exportTheme(theme)
        val imported = ThemeManager.importTheme(text)!!
        assertEquals(Font.BOLD, imported.panelStyle.fontStyle)
        assertEquals(Font.ITALIC, imported.tabStyle.fontStyle)
        assertEquals(Font.BOLD or Font.ITALIC, imported.commandBarStyle.fontStyle)
    }

    @Test
    fun `export and import round-trips selection colors`() {
        val theme = Theme(
            name = "Selection Test",
            panelStyle = ThemeStyle(fontColor = "#FFFFFF", backgroundColor = "#000000", selectedColor = "#111111", activeSelectedColor = "#222222"),
        )
        val text = ThemeManager.exportTheme(theme)
        assertTrue(text.contains("panel.selectedColor=#111111"))
        assertTrue(text.contains("panel.activeSelectedColor=#222222"))
        val imported = ThemeManager.importTheme(text)!!
        assertEquals("#111111", imported.panelStyle.selectedColor)
        assertEquals("#222222", imported.panelStyle.activeSelectedColor)
    }

    @Test
    fun `import with unknown prefixes ignores them gracefully`() {
        val text = "name=Test\nunknown.fontColor=#FF0000\npanel.fontColor=#00FF00"
        val theme = ThemeManager.importTheme(text)
        assertNotNull(theme)
        assertEquals("#00FF00", theme!!.panelStyle.fontColor)
    }

    @Test
    fun `import with partial style fills only specified fields`() {
        val text = "name=Partial\npanel.fontColor=#FF0000"
        val theme = ThemeManager.importTheme(text)!!
        assertEquals("#FF0000", theme.panelStyle.fontColor)
        assertEquals("", theme.panelStyle.fontFamily)
        assertEquals(0, theme.panelStyle.fontSize)
        assertEquals("", theme.panelStyle.backgroundColor)
        assertTrue(theme.tabStyle.toComponentStyle().isDefault())
    }

    @Test
    fun `export includes only non-default style fields`() {
        val theme = Theme(
            name = "Sparse",
            panelStyle = ThemeStyle(fontColor = "#FF0000"),
        )
        val text = ThemeManager.exportTheme(theme)
        assertTrue(text.contains("panel.fontColor=#FF0000"))
        assertFalse(text.contains("panel.fontFamily"))
        assertFalse(text.contains("panel.fontSize"))
        assertFalse(text.contains("panel.fontStyle"))
        assertFalse(text.contains("panel.backgroundColor"))
    }

    @Test
    fun `export theme saved in state round-trips through import`() {
        val theme = Theme(
            name = "Persisted",
            panelStyle = ThemeStyle("Consolas", 14, Font.BOLD, "#AA0000", "#001100"),
            tabStyle = ThemeStyle("Arial", 12, Font.ITALIC, "#BB0000", "#002200"),
        )
        ThemeManager.saveTheme(state, theme)
        val loaded = ThemeManager.getAllThemes(state).first { it.name == "Persisted" }
        val text = ThemeManager.exportTheme(loaded)
        val imported = ThemeManager.importTheme(text)!!
        assertEquals(theme.panelStyle, imported.panelStyle)
        assertEquals(theme.tabStyle, imported.tabStyle)
    }

    @Test
    fun `export and import into another state`() {
        ThemeManager.saveTheme(state, customTheme("Transfer", fg = "#AABB00"))
        val exported = ThemeManager.exportTheme(ThemeManager.getAllThemes(state).first { it.name == "Transfer" })

        val otherState = TurtleCommanderSettings.State().apply { themesInitialized = true }
        val imported = ThemeManager.importTheme(exported)!!
        val (result, name) = ThemeManager.importThemeToState(otherState, imported)
        assertEquals(ThemeManager.ImportResult.Imported, result)
        assertEquals("Transfer", name)
        assertEquals("#AABB00", otherState.themes[0].panelFontColor)
    }

    @Test
    fun `export initial theme and re-import`() {
        val text = ThemeManager.exportTheme(Theme.CLASSIC_NC)
        val imported = ThemeManager.importTheme(text)!!
        assertEquals(Theme.CLASSIC_NC.panelStyle, imported.panelStyle)
        assertEquals(Theme.CLASSIC_NC.tabStyle, imported.tabStyle)
        assertEquals(Theme.CLASSIC_NC.columnHeaderStyle, imported.columnHeaderStyle)
    }

    // --- importThemeToState ---

    @Test
    fun `importThemeToState adds new theme`() {
        val theme = customTheme("New Import")
        val (result, name) = ThemeManager.importThemeToState(state, theme)
        assertEquals(ThemeManager.ImportResult.Imported, result)
        assertEquals("New Import", name)
        assertEquals(1, state.themes.size)
    }

    @Test
    fun `importThemeToState overwrites theme when requested`() {
        ThemeManager.saveTheme(state, customTheme("Existing", fg = "#111111"))
        val theme = customTheme("Existing", fg = "#222222")
        val (result, name) = ThemeManager.importThemeToState(state, theme, overwrite = true)
        assertEquals(ThemeManager.ImportResult.Replaced, result)
        assertEquals("Existing", name)
        assertEquals(1, state.themes.size)
        assertEquals("#222222", state.themes[0].panelFontColor)
    }

    @Test
    fun `importThemeToState renames theme when not overwriting`() {
        ThemeManager.saveTheme(state, customTheme("Existing"))
        val theme = customTheme("Existing")
        val (result, name) = ThemeManager.importThemeToState(state, theme, overwrite = false)
        assertEquals(ThemeManager.ImportResult.Renamed, result)
        assertEquals("Existing (2)", name)
        assertEquals(2, state.themes.size)
    }

    @Test
    fun `importThemeToState increments counter for multiple conflicts`() {
        ThemeManager.saveTheme(state, customTheme("Foo"))
        ThemeManager.saveTheme(state, customTheme("Foo (2)"))
        val theme = customTheme("Foo")
        val (result, name) = ThemeManager.importThemeToState(state, theme, overwrite = false)
        assertEquals(ThemeManager.ImportResult.Renamed, result)
        assertEquals("Foo (3)", name)
    }

    // --- generateUniqueName ---

    @Test
    fun `generateUniqueName appends 2 when no conflict`() {
        assertEquals("Foo (2)", ThemeManager.generateUniqueName("Foo", state))
    }

    @Test
    fun `generateUniqueName increments past existing names`() {
        ThemeManager.saveTheme(state, customTheme("Foo (2)"))
        assertEquals("Foo (3)", ThemeManager.generateUniqueName("Foo", state))
    }

    @Test
    fun `generateUniqueName handles chain of conflicts`() {
        ThemeManager.saveTheme(state, customTheme("Test (2)"))
        ThemeManager.saveTheme(state, customTheme("Test (3)"))
        ThemeManager.saveTheme(state, customTheme("Test (4)"))
        assertEquals("Test (5)", ThemeManager.generateUniqueName("Test", state))
    }

    // --- SavedTheme serialization ---

    @Test
    fun `SavedTheme fromTheme and toTheme round-trip all eight styles`() {
        val original = Theme(
            name = "RoundTrip",
            panelStyle = ThemeStyle("Consolas", 14, Font.BOLD, "#AA0000", "#001100", "#111111", "#222222"),
            tabStyle = ThemeStyle("Arial", 12, Font.ITALIC, "#BB0000", "#002200"),
            pathBarStyle = ThemeStyle("Courier New", 13, Font.PLAIN, "#CC0000", "#003300"),
            statusBarStyle = ThemeStyle("Monospaced", 11, Font.PLAIN, "#DD0000", "#004400"),
            commandBarStyle = ThemeStyle("Dialog", 15, Font.BOLD, "#EE0000", "#005500"),
            commandButtonStyle = ThemeStyle("Dialog", 14, Font.PLAIN, "#DD0000", "#004400"),
            driveSelectorStyle = ThemeStyle("SansSerif", 13, Font.PLAIN, "#FF0000", "#006600"),
            columnHeaderStyle = ThemeStyle("Serif", 13, Font.PLAIN, "#110000", "#007700"),
        )
        val saved = SavedTheme.fromTheme(original)
        val restored = saved.toTheme()
        assertEquals(original.name, restored.name)
        assertEquals(original.panelStyle, restored.panelStyle)
        assertEquals(original.tabStyle, restored.tabStyle)
        assertEquals(original.pathBarStyle, restored.pathBarStyle)
        assertEquals(original.statusBarStyle, restored.statusBarStyle)
        assertEquals(original.commandBarStyle, restored.commandBarStyle)
        assertEquals(original.commandButtonStyle, restored.commandButtonStyle)
        assertEquals(original.driveSelectorStyle, restored.driveSelectorStyle)
        assertEquals(original.columnHeaderStyle, restored.columnHeaderStyle)
    }

    @Test
    fun `SavedTheme default constructor produces empty theme`() {
        val saved = SavedTheme()
        val theme = saved.toTheme()
        assertEquals("", theme.name)
        assertTrue(theme.panelStyle.toComponentStyle().isDefault())
    }

    @Test
    fun `SavedTheme preserves individual fields`() {
        val saved = SavedTheme.fromTheme(Theme(
            name = "Test",
            panelStyle = ThemeStyle(fontFamily = "Consolas", fontSize = 14),
            tabStyle = ThemeStyle(fontColor = "#FF0000"),
        ))
        assertEquals("Consolas", saved.panelFontFamily)
        assertEquals(14, saved.panelFontSize)
        assertEquals("#FF0000", saved.tabFontColor)
        assertEquals("", saved.tabFontFamily)
    }

    // --- themes in State ---

    @Test
    fun `themes defaults to empty list`() {
        assertTrue(TurtleCommanderSettings.State().themes.isEmpty())
    }

    @Test
    fun `themesInitialized defaults to false`() {
        assertFalse(TurtleCommanderSettings.State().themesInitialized)
    }

    @Test
    fun `multiple themes persist independently`() {
        ThemeManager.saveTheme(state, customTheme("Alpha", fg = "#111111"))
        ThemeManager.saveTheme(state, customTheme("Beta", fg = "#222222"))
        assertEquals(2, state.themes.size)
        assertEquals("#111111", state.themes[0].panelFontColor)
        assertEquals("#222222", state.themes[1].panelFontColor)
    }

    // --- Edge cases ---

    @Test
    fun `save then delete then save again works`() {
        ThemeManager.saveTheme(state, customTheme("Temp"))
        ThemeManager.deleteTheme(state, "Temp")
        assertTrue(state.themes.isEmpty())
        ThemeManager.saveTheme(state, customTheme("Temp"))
        assertEquals(1, state.themes.size)
    }

    @Test
    fun `rename then find by new name works`() {
        ThemeManager.saveTheme(state, customTheme("Before"))
        ThemeManager.renameTheme(state, "Before", "After")
        val all = ThemeManager.getAllThemes(state)
        assertTrue(all.any { it.name == "After" })
        assertFalse(all.any { it.name == "Before" })
    }

    @Test
    fun `export theme and re-import round-trips`() {
        val original = customTheme("Test Export", fg = "#FF0000", bg = "#00FF00")
        val text = ThemeManager.exportTheme(original)
        val imported = ThemeManager.importTheme(text)!!
        assertEquals(original.panelStyle, imported.panelStyle)
        val (result, name) = ThemeManager.importThemeToState(state, imported)
        assertEquals(ThemeManager.ImportResult.Imported, result)
        assertEquals("Test Export", name)
    }

    @Test
    fun `importThemeToState preserves styles of imported theme`() {
        val theme = Theme(
            name = "Full Import",
            panelStyle = ThemeStyle("Consolas", 14, Font.BOLD, "#AA0000", "#001100"),
            tabStyle = ThemeStyle("Arial", 12, Font.ITALIC, "#BB0000", "#002200"),
            pathBarStyle = ThemeStyle("Courier New", 13, Font.PLAIN, "#CC0000", "#003300"),
            statusBarStyle = ThemeStyle("Monospaced", 11, Font.PLAIN, "#DD0000", "#004400"),
            commandBarStyle = ThemeStyle("Dialog", 15, Font.BOLD, "#EE0000", "#005500"),
            commandButtonStyle = ThemeStyle("Dialog", 14, Font.PLAIN, "#DD0000", "#004400"),
            driveSelectorStyle = ThemeStyle("SansSerif", 13, Font.PLAIN, "#FF0000", "#006600"),
            columnHeaderStyle = ThemeStyle("Serif", 13, Font.PLAIN, "#110000", "#007700"),
        )
        ThemeManager.importThemeToState(state, theme)
        val restored = state.themes[0].toTheme()
        assertEquals(theme.panelStyle, restored.panelStyle)
        assertEquals(theme.tabStyle, restored.tabStyle)
        assertEquals(theme.pathBarStyle, restored.pathBarStyle)
        assertEquals(theme.statusBarStyle, restored.statusBarStyle)
        assertEquals(theme.commandBarStyle, restored.commandBarStyle)
        assertEquals(theme.commandButtonStyle, restored.commandButtonStyle)
        assertEquals(theme.driveSelectorStyle, restored.driveSelectorStyle)
        assertEquals(theme.columnHeaderStyle, restored.columnHeaderStyle)
    }

    @Test
    fun `import overwrite preserves theme count`() {
        ThemeManager.saveTheme(state, customTheme("A"))
        ThemeManager.saveTheme(state, customTheme("B"))
        assertEquals(2, state.themes.size)
        ThemeManager.importThemeToState(state, customTheme("A", fg = "#999999"), overwrite = true)
        assertEquals(2, state.themes.size)
    }

    @Test
    fun `import rename does not modify original theme`() {
        ThemeManager.saveTheme(state, customTheme("Original", fg = "#111111"))
        ThemeManager.importThemeToState(state, customTheme("Original", fg = "#222222"), overwrite = false)
        assertEquals(2, state.themes.size)
        assertEquals("#111111", state.themes.first { it.name == "Original" }.panelFontColor)
        assertEquals("#222222", state.themes.first { it.name == "Original (2)" }.panelFontColor)
    }

    @Test
    fun `seeded themes can be edited and deleted like any other`() {
        val fresh = TurtleCommanderSettings.State()
        ThemeManager.getAllThemes(fresh) // seeds

        // Edit Classic NC
        val result = ThemeManager.saveTheme(fresh, customTheme("Classic NC", fg = "#FF0000"))
        assertEquals(ThemeManager.SaveResult.Replaced, result)
        val nc = ThemeManager.getAllThemes(fresh).first { it.name == "Classic NC" }
        assertEquals("#FF0000", nc.panelStyle.fontColor)

        // Delete Green Terminal
        assertTrue(ThemeManager.deleteTheme(fresh, "Green Terminal"))
        assertFalse(ThemeManager.getAllThemes(fresh).any { it.name == "Green Terminal" })

        // Rename Brown Oldschool
        assertEquals(ThemeManager.RenameResult.Success,
            ThemeManager.renameTheme(fresh, "Brown Oldschool", "My Brown"))
        assertTrue(ThemeManager.getAllThemes(fresh).any { it.name == "My Brown" })
    }
}
