package io.github.jhspetersson.turtlecommander.settings

import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.Assert.*
import org.junit.Test
import java.awt.Font

/**
 * Verifies that all State fields survive XML serialization round-trip.
 * Catches silent serialization failures like Kotlin property getter clashes.
 */
class XmlSerializationTest {

    private fun roundTrip(state: TurtleCommanderSettings.State): TurtleCommanderSettings.State {
        val element: Element = XmlSerializer.serialize(state)
        return XmlSerializer.deserialize(element, TurtleCommanderSettings.State::class.java)
    }

    @Test
    fun `themesInitialized flag survives round-trip`() {
        val state = TurtleCommanderSettings.State().apply {
            themesInitialized = true
        }
        val restored = roundTrip(state)
        assertTrue("themesInitialized should be true after round-trip", restored.themesInitialized)
    }

    @Test
    fun `themesInitialized false is preserved as default`() {
        val state = TurtleCommanderSettings.State()
        val restored = roundTrip(state)
        assertFalse("themesInitialized should default to false", restored.themesInitialized)
    }

    @Test
    fun `themeName survives round-trip`() {
        val state = TurtleCommanderSettings.State().apply {
            themeName = "Classic NC"
        }
        val restored = roundTrip(state)
        assertEquals("Classic NC", restored.themeName)
    }

    @Test
    fun `ComponentStyle all fields survive round-trip`() {
        val state = TurtleCommanderSettings.State().apply {
            panelStyle = ComponentStyle().apply {
                fontFamily = "Consolas"
                fontSize = 14
                fontStyle = Font.BOLD
                fontColor = "#00FFFF"
                backgroundColor = "#000080"
                selectedColor = "#000050"
                activeSelectedColor = "#0000AA"
            }
        }
        val restored = roundTrip(state)
        assertEquals("Consolas", restored.panelStyle.fontFamily)
        assertEquals(14, restored.panelStyle.fontSize)
        assertEquals(Font.BOLD, restored.panelStyle.fontStyle)
        assertEquals("#00FFFF", restored.panelStyle.fontColor)
        assertEquals("#000080", restored.panelStyle.backgroundColor)
        assertEquals("#000050", restored.panelStyle.selectedColor)
        assertEquals("#0000AA", restored.panelStyle.activeSelectedColor)
    }

    @Test
    fun `all component styles survive round-trip`() {
        val testStyle = ComponentStyle().apply {
            fontFamily = "Arial"
            fontSize = 12
            fontColor = "#FF0000"
            backgroundColor = "#00FF00"
        }
        val state = TurtleCommanderSettings.State().apply {
            panelStyle = ComponentStyle().apply { copyFrom(testStyle) }
            tabStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "Tab" }
            pathBarStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "PathBar" }
            statusBarStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "StatusBar" }
            commandBarStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "CommandBar" }
            commandButtonStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "CommandButton" }
            driveSelectorStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "DriveSelector" }
            columnHeaderStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "ColumnHeader" }
        }
        val restored = roundTrip(state)
        assertEquals("Arial", restored.panelStyle.fontFamily)
        assertEquals("Tab", restored.tabStyle.fontFamily)
        assertEquals("PathBar", restored.pathBarStyle.fontFamily)
        assertEquals("StatusBar", restored.statusBarStyle.fontFamily)
        assertEquals("CommandBar", restored.commandBarStyle.fontFamily)
        assertEquals("CommandButton", restored.commandButtonStyle.fontFamily)
        assertEquals("DriveSelector", restored.driveSelectorStyle.fontFamily)
        assertEquals("ColumnHeader", restored.columnHeaderStyle.fontFamily)
    }

    @Test
    fun `pre-theme styles survive round-trip`() {
        val state = TurtleCommanderSettings.State().apply {
            preThemePanelStyle = ComponentStyle().apply { fontColor = "#111111" }
            preThemeTabStyle = ComponentStyle().apply { fontColor = "#222222" }
            preThemePathBarStyle = ComponentStyle().apply { fontColor = "#333333" }
            preThemeStatusBarStyle = ComponentStyle().apply { fontColor = "#444444" }
            preThemeCommandBarStyle = ComponentStyle().apply { fontColor = "#555555" }
            preThemeCommandButtonStyle = ComponentStyle().apply { fontColor = "#565656" }
            preThemeDriveSelectorStyle = ComponentStyle().apply { fontColor = "#666666" }
            preThemeColumnHeaderStyle = ComponentStyle().apply { fontColor = "#777777" }
        }
        val restored = roundTrip(state)
        assertEquals("#111111", restored.preThemePanelStyle.fontColor)
        assertEquals("#222222", restored.preThemeTabStyle.fontColor)
        assertEquals("#333333", restored.preThemePathBarStyle.fontColor)
        assertEquals("#444444", restored.preThemeStatusBarStyle.fontColor)
        assertEquals("#555555", restored.preThemeCommandBarStyle.fontColor)
        assertEquals("#565656", restored.preThemeCommandButtonStyle.fontColor)
        assertEquals("#666666", restored.preThemeDriveSelectorStyle.fontColor)
        assertEquals("#777777", restored.preThemeColumnHeaderStyle.fontColor)
    }

    @Test
    fun `XML output actually contains themes`() {
        val state = TurtleCommanderSettings.State().apply {
            themesInitialized = true
            themes = mutableListOf(SavedTheme.fromTheme(Theme.CLASSIC_NC))
        }
        val element: Element = XmlSerializer.serialize(state)
        val xmlOutput = org.jdom.output.XMLOutputter(org.jdom.output.Format.getPrettyFormat()).outputString(element)
        assertTrue("XML should contain themesInitialized:\n$xmlOutput",
            xmlOutput.contains("themesInitialized"))
        assertTrue("XML should contain Classic NC theme data:\n$xmlOutput",
            xmlOutput.contains("Classic NC"))
    }

    @Test
    fun `themes survive serialization with SkipDefaultsSerializationFilter`() {
        val state = TurtleCommanderSettings.State().apply {
            themesInitialized = true
            themes = mutableListOf(SavedTheme.fromTheme(Theme.CLASSIC_NC))
        }
        val filter = SkipDefaultsSerializationFilter()
        val element: Element = XmlSerializer.serialize(state, filter)
        val xmlOutput = org.jdom.output.XMLOutputter(org.jdom.output.Format.getPrettyFormat()).outputString(element)
        assertTrue("XML with SkipDefaults should contain themes:\n$xmlOutput",
            xmlOutput.contains("Classic NC"))
        assertTrue("XML with SkipDefaults should contain themesInitialized:\n$xmlOutput",
            xmlOutput.contains("themesInitialized"))

        // Verify round-trip through filtered serialization
        val restored = XmlSerializer.deserialize(element, TurtleCommanderSettings.State::class.java)
        assertTrue(restored.themesInitialized)
        assertEquals(1, restored.themes.size)
        assertEquals("Classic NC", restored.themes[0].name)
        assertEquals("#00FFFF", restored.themes[0].panelFontColor)
    }

    @Test
    fun `SavedTheme list survives round-trip`() {
        val state = TurtleCommanderSettings.State().apply {
            themesInitialized = true
            themes = mutableListOf(
                SavedTheme.fromTheme(Theme.CLASSIC_NC),
                SavedTheme.fromTheme(Theme.GREEN_TERMINAL),
            )
        }
        val restored = roundTrip(state)
        assertTrue(restored.themesInitialized)
        assertEquals(2, restored.themes.size)
        assertEquals("Classic NC", restored.themes[0].name)
        assertEquals("Green Terminal", restored.themes[1].name)
        // Verify deep fields on saved theme
        assertEquals("#00FFFF", restored.themes[0].panelFontColor)
        assertEquals("#000080", restored.themes[0].panelBackgroundColor)
        assertEquals("#000050", restored.themes[0].panelSelectedColor)
        assertEquals("#0000AA", restored.themes[0].panelActiveSelectedColor)
        // Verify command button fields
        assertEquals("#000000", restored.themes[0].commandButtonFontColor)
        assertEquals("#00AAAA", restored.themes[0].commandButtonBackgroundColor)
        // Verify command bar (panel-only) has bg but no font color
        assertEquals("#000000", restored.themes[0].commandBarBackgroundColor)
    }

    @Test
    fun `selectedColor and activeSelectedColor survive round-trip`() {
        val state = TurtleCommanderSettings.State().apply {
            panelStyle = ComponentStyle().apply {
                selectedColor = "#AABBCC"
                activeSelectedColor = "#DDEEFF"
            }
        }
        val restored = roundTrip(state)
        assertEquals("#AABBCC", restored.panelStyle.selectedColor)
        assertEquals("#DDEEFF", restored.panelStyle.activeSelectedColor)
    }

    @Test
    fun `boolean settings survive round-trip`() {
        val state = TurtleCommanderSettings.State().apply {
            enableFileNameHighlighting = false
            showCommandBar = false
            hideDriveSelector = true
            hideStatusBar = true
            alwaysOverwriteFiles = true
            sortWithDirectories = true
            calculateDirectorySize = false
        }
        val restored = roundTrip(state)
        assertFalse(restored.enableFileNameHighlighting)
        assertFalse(restored.showCommandBar)
        assertTrue(restored.hideDriveSelector)
        assertTrue(restored.hideStatusBar)
        assertTrue(restored.alwaysOverwriteFiles)
        assertTrue(restored.sortWithDirectories)
        assertFalse(restored.calculateDirectorySize)
    }

    @Test
    fun `columns survive round-trip`() {
        val state = TurtleCommanderSettings.State().apply {
            columns = mutableListOf(
                ColumnConfig().apply { id = "Name"; visible = true },
                ColumnConfig().apply { id = "Size"; visible = false },
            )
        }
        val restored = roundTrip(state)
        assertEquals(2, restored.columns.size)
        assertEquals("Name", restored.columns[0].id)
        assertTrue(restored.columns[0].visible)
        assertEquals("Size", restored.columns[1].id)
        assertFalse(restored.columns[1].visible)
    }

    @Test
    fun `default State round-trips cleanly`() {
        val state = TurtleCommanderSettings.State()
        val restored = roundTrip(state)
        assertTrue(restored.panelStyle.isDefault())
        assertTrue(restored.commandButtonStyle.isDefault())
        assertFalse(restored.themesInitialized)
        assertEquals("", restored.themeName)
        assertTrue(restored.themes.isEmpty())
    }

    @Test
    fun `full realistic state survives round-trip`() {
        // Simulate a user who applied Classic NC theme
        val state = TurtleCommanderSettings.State()
        state.themesInitialized = true
        state.themeName = "Classic NC"
        for (theme in Theme.INITIAL_THEMES) {
            state.themes.add(SavedTheme.fromTheme(theme))
        }
        Theme.CLASSIC_NC.applyTo(state)
        state.preThemePanelStyle = ComponentStyle().apply { fontColor = "#FFFFFF" }

        val restored = roundTrip(state)

        assertTrue(restored.themesInitialized)
        assertEquals("Classic NC", restored.themeName)
        assertEquals(Theme.INITIAL_THEMES.size, restored.themes.size)
        assertEquals("#00FFFF", restored.panelStyle.fontColor)
        assertEquals("#000080", restored.panelStyle.backgroundColor)
        assertEquals("#000050", restored.panelStyle.selectedColor)
        assertEquals("#0000AA", restored.panelStyle.activeSelectedColor)
        assertEquals("#000000", restored.commandBarStyle.backgroundColor)
        assertEquals("#000000", restored.commandButtonStyle.fontColor)
        assertEquals("#00AAAA", restored.commandButtonStyle.backgroundColor)
        assertEquals("#FFFFFF", restored.preThemePanelStyle.fontColor)
    }
}
