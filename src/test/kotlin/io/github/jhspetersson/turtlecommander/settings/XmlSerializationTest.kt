package io.github.jhspetersson.turtlecommander.settings

import com.intellij.openapi.util.JDOMUtil
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
            styles.panelStyle = ComponentStyle().apply {
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
        assertEquals("Consolas", restored.styles.panelStyle.fontFamily)
        assertEquals(14, restored.styles.panelStyle.fontSize)
        assertEquals(Font.BOLD, restored.styles.panelStyle.fontStyle)
        assertEquals("#00FFFF", restored.styles.panelStyle.fontColor)
        assertEquals("#000080", restored.styles.panelStyle.backgroundColor)
        assertEquals("#000050", restored.styles.panelStyle.selectedColor)
        assertEquals("#0000AA", restored.styles.panelStyle.activeSelectedColor)
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
            styles.panelStyle = ComponentStyle().apply { copyFrom(testStyle) }
            styles.tabStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "Tab" }
            styles.pathBarStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "PathBar" }
            styles.statusBarStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "StatusBar" }
            styles.commandBarStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "CommandBar" }
            styles.commandButtonStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "CommandButton" }
            styles.driveSelectorStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "DriveSelector" }
            styles.columnHeaderStyle = ComponentStyle().apply { copyFrom(testStyle); fontFamily = "ColumnHeader" }
        }
        val restored = roundTrip(state)
        assertEquals("Arial", restored.styles.panelStyle.fontFamily)
        assertEquals("Tab", restored.styles.tabStyle.fontFamily)
        assertEquals("PathBar", restored.styles.pathBarStyle.fontFamily)
        assertEquals("StatusBar", restored.styles.statusBarStyle.fontFamily)
        assertEquals("CommandBar", restored.styles.commandBarStyle.fontFamily)
        assertEquals("CommandButton", restored.styles.commandButtonStyle.fontFamily)
        assertEquals("DriveSelector", restored.styles.driveSelectorStyle.fontFamily)
        assertEquals("ColumnHeader", restored.styles.columnHeaderStyle.fontFamily)
    }

    @Test
    fun `pre-theme styles survive round-trip`() {
        val state = TurtleCommanderSettings.State().apply {
            preThemeStyles.panelStyle = ComponentStyle().apply { fontColor = "#111111" }
            preThemeStyles.tabStyle = ComponentStyle().apply { fontColor = "#222222" }
            preThemeStyles.pathBarStyle = ComponentStyle().apply { fontColor = "#333333" }
            preThemeStyles.statusBarStyle = ComponentStyle().apply { fontColor = "#444444" }
            preThemeStyles.commandBarStyle = ComponentStyle().apply { fontColor = "#555555" }
            preThemeStyles.commandButtonStyle = ComponentStyle().apply { fontColor = "#565656" }
            preThemeStyles.driveSelectorStyle = ComponentStyle().apply { fontColor = "#666666" }
            preThemeStyles.columnHeaderStyle = ComponentStyle().apply { fontColor = "#777777" }
        }
        val restored = roundTrip(state)
        assertEquals("#111111", restored.preThemeStyles.panelStyle.fontColor)
        assertEquals("#222222", restored.preThemeStyles.tabStyle.fontColor)
        assertEquals("#333333", restored.preThemeStyles.pathBarStyle.fontColor)
        assertEquals("#444444", restored.preThemeStyles.statusBarStyle.fontColor)
        assertEquals("#555555", restored.preThemeStyles.commandBarStyle.fontColor)
        assertEquals("#565656", restored.preThemeStyles.commandButtonStyle.fontColor)
        assertEquals("#666666", restored.preThemeStyles.driveSelectorStyle.fontColor)
        assertEquals("#777777", restored.preThemeStyles.columnHeaderStyle.fontColor)
    }

    @Test
    fun `XML output actually contains themes`() {
        val state = TurtleCommanderSettings.State().apply {
            themesInitialized = true
            themes = mutableListOf(SavedTheme.fromTheme(Theme.CLASSIC_NC))
        }
        val element: Element = XmlSerializer.serialize(state)
        val xmlOutput = JDOMUtil.writeElement(element)
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

        val element: Element = XmlSerializer.serialize(state)
        val xmlOutput = JDOMUtil.writeElement(element)
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
            styles.panelStyle = ComponentStyle().apply {
                selectedColor = "#AABBCC"
                activeSelectedColor = "#DDEEFF"
            }
        }
        val restored = roundTrip(state)
        assertEquals("#AABBCC", restored.styles.panelStyle.selectedColor)
        assertEquals("#DDEEFF", restored.styles.panelStyle.activeSelectedColor)
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
        assertTrue(restored.styles.panelStyle.isDefault())
        assertTrue(restored.styles.commandButtonStyle.isDefault())
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
        state.preThemeStyles.panelStyle = ComponentStyle().apply { fontColor = "#FFFFFF" }

        val restored = roundTrip(state)

        assertTrue(restored.themesInitialized)
        assertEquals("Classic NC", restored.themeName)
        assertEquals(Theme.INITIAL_THEMES.size, restored.themes.size)
        assertEquals("#00FFFF", restored.styles.panelStyle.fontColor)
        assertEquals("#000080", restored.styles.panelStyle.backgroundColor)
        assertEquals("#000050", restored.styles.panelStyle.selectedColor)
        assertEquals("#0000AA", restored.styles.panelStyle.activeSelectedColor)
        assertEquals("#000000", restored.styles.commandBarStyle.backgroundColor)
        assertEquals("#000000", restored.styles.commandButtonStyle.fontColor)
        assertEquals("#00AAAA", restored.styles.commandButtonStyle.backgroundColor)
        assertEquals("#FFFFFF", restored.preThemeStyles.panelStyle.fontColor)
    }

    @Test
    fun `StyleSet survives round-trip`() {
        val state = TurtleCommanderSettings.State().apply {
            styles.panelStyle.fontColor = "#AABBCC"
            styles.tabStyle.fontFamily = "Consolas"
            preThemeStyles.panelStyle.backgroundColor = "#112233"
        }
        val restored = roundTrip(state)
        assertEquals("#AABBCC", restored.styles.panelStyle.fontColor)
        assertEquals("Consolas", restored.styles.tabStyle.fontFamily)
        assertEquals("#112233", restored.preThemeStyles.panelStyle.backgroundColor)
    }
}
