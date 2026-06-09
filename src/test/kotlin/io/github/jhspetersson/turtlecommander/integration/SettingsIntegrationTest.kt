package io.github.jhspetersson.turtlecommander.integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.settings.ColumnConfig
import io.github.jhspetersson.turtlecommander.settings.ComponentStyle
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import java.awt.Color
import java.awt.Font

/**
 * Integration tests for plugin settings: defaults, persistence, column config, component styles.
 */
class SettingsIntegrationTest : BasePlatformTestCase() {

    private lateinit var settings: TurtleCommanderSettings
    private lateinit var originalState: TurtleCommanderSettings.State

    override fun setUp() {
        super.setUp()
        settings = TurtleCommanderSettings.getInstance()
        // Save original state to restore in tearDown (settings are app-level and shared)
        originalState = TurtleCommanderSettings.State().also {
            val s = settings.state
            it.enableFileNameHighlighting = s.enableFileNameHighlighting
            it.showCommandBar = s.showCommandBar
            it.hideDriveSelector = s.hideDriveSelector
            it.hideStatusBar = s.hideStatusBar
            it.defaultOverwritePolicy = s.defaultOverwritePolicy
            it.defaultViewMode = s.defaultViewMode
            it.sortWithDirectories = s.sortWithDirectories
            it.calculateDirectorySize = s.calculateDirectorySize
            it.panelFontFamily = s.panelFontFamily
            it.panelFontSize = s.panelFontSize
            it.tabFontFamily = s.tabFontFamily
            it.tabFontSize = s.tabFontSize
        }
    }

    override fun tearDown() {
        try {
            settings.loadState(originalState)
        } finally {
            super.tearDown()
        }
    }

    // --- Default values ---

    fun testDefaultSettings() {
        val state = settings.state
        assertTrue("File name highlighting should default to true", state.enableFileNameHighlighting)
        assertTrue("Command bar should default to visible", state.showCommandBar)
        assertFalse("Drive selector should not be hidden by default", state.hideDriveSelector)
        assertFalse("Status bar should not be hidden by default", state.hideStatusBar)
        assertEquals("Default overwrite policy should be ASK", "ASK", state.defaultOverwritePolicy)
        assertEquals("Default view mode should be TABLE", "TABLE", state.defaultViewMode)
        assertFalse("Sort with directories should default to false", state.sortWithDirectories)
        assertTrue("Calculate directory size should default to true", state.calculateDirectorySize)
    }

    // --- State mutation and read-back ---

    fun testSettingsStateRoundtrip() {
        val state = settings.state

        state.enableFileNameHighlighting = false
        state.showCommandBar = false
        state.hideDriveSelector = true
        state.hideStatusBar = true
        state.defaultOverwritePolicy = "OVERWRITE_ALL"
        state.defaultViewMode = "LIST"
        state.sortWithDirectories = true
        state.calculateDirectorySize = false

        // Read back through the same instance
        assertFalse(settings.state.enableFileNameHighlighting)
        assertFalse(settings.state.showCommandBar)
        assertTrue(settings.state.hideDriveSelector)
        assertTrue(settings.state.hideStatusBar)
        assertEquals("OVERWRITE_ALL", settings.state.defaultOverwritePolicy)
        assertEquals("LIST", settings.state.defaultViewMode)
        assertTrue(settings.state.sortWithDirectories)
        assertFalse(settings.state.calculateDirectorySize)
    }

    @Suppress("DEPRECATION")
    fun testLegacyAlwaysOverwriteMigratesToPolicy() {
        // Pre-migration state: legacy boolean set to true, new field still at its default.
        val newState = TurtleCommanderSettings.State().apply {
            alwaysOverwriteFiles = true
        }

        settings.loadState(newState)

        // loadState's migration step folds the bool into the new field and clears it.
        assertEquals("OVERWRITE_ALL", settings.state.defaultOverwritePolicy)
        assertFalse("legacy bool should be cleared after migration", settings.state.alwaysOverwriteFiles)
    }

    @Suppress("DEPRECATION")
    fun testLegacyAlwaysOverwriteDoesNotClobberExplicitPolicy() {
        // If the user already saved a non-default policy, the legacy bool must not win.
        val newState = TurtleCommanderSettings.State().apply {
            alwaysOverwriteFiles = true
            defaultOverwritePolicy = "SKIP_ALL"
        }

        settings.loadState(newState)

        assertEquals("SKIP_ALL", settings.state.defaultOverwritePolicy)
    }

    fun testLoadStateOverwritesExisting() {
        val newState = TurtleCommanderSettings.State().apply {
            defaultViewMode = "THUMBNAIL"
            enableFileNameHighlighting = false
            panelFontFamily = "Monospaced"
            panelFontSize = 16
        }

        settings.loadState(newState)

        assertEquals("THUMBNAIL", settings.state.defaultViewMode)
        assertFalse(settings.state.enableFileNameHighlighting)
        assertEquals("Monospaced", settings.state.panelFontFamily)
        assertEquals(16, settings.state.panelFontSize)
    }

    // --- Column config ---

    fun testEffectiveColumnsReturnsDefaults() {
        settings.state.columns.clear()

        val columns = settings.getEffectiveColumns()

        assertEquals("Should have all 9 default columns", 9, columns.size)
        assertEquals("Name", columns[0].id)
        assertEquals("Ext", columns[1].id)
        assertEquals("Size", columns[2].id)
        assertEquals("Date Created", columns[3].id)
        assertEquals("Date Modified", columns[4].id)
        assertEquals("User", columns[5].id)
        assertEquals("Group", columns[6].id)
        assertEquals("Permissions", columns[7].id)
        assertEquals("Inode", columns[8].id)
        assertFalse("Inode column should be hidden by default", columns[8].visible)
    }

    fun testEffectiveColumnsPreservesCustomization() {
        settings.state.columns.clear()
        settings.state.columns.add(ColumnConfig().apply { id = "Name"; visible = true })
        settings.state.columns.add(ColumnConfig().apply { id = "Size"; visible = false })

        val columns = settings.getEffectiveColumns()

        // Should include the 2 saved columns + fill in the 6 missing ones
        assertTrue("Should have at least 8 columns", columns.size >= 8)
        val sizeCol = columns.find { it.id == "Size" }
        assertNotNull(sizeCol)
        assertFalse("Size column should be hidden", sizeCol!!.visible)
    }

    fun testEffectiveColumnsAddsNewColumns() {
        // Simulate having only 3 saved columns (as if from an older version)
        settings.state.columns.clear()
        settings.state.columns.add(ColumnConfig().apply { id = "Name" })
        settings.state.columns.add(ColumnConfig().apply { id = "Size" })
        settings.state.columns.add(ColumnConfig().apply { id = "Date Modified" })

        val columns = settings.getEffectiveColumns()

        // Missing columns (Ext, Date Created, User, Group, Permissions) should be appended
        val ids = columns.map { it.id }.toSet()
        assertTrue("Should include Ext", "Ext" in ids)
        assertTrue("Should include Date Created", "Date Created" in ids)
        assertTrue("Should include User", "User" in ids)
        assertTrue("Should include Group", "Group" in ids)
        assertTrue("Should include Permissions", "Permissions" in ids)
    }

    fun testColumnConfigDefaults() {
        val defaults = ColumnConfig.defaults()
        assertEquals(9, defaults.size)
        assertEquals(ColumnConfig.ALL_COLUMN_IDS, defaults.map { it.id })
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            val userCol = defaults.find { it.id == "User" }!!
            val groupCol = defaults.find { it.id == "Group" }!!
            assertFalse("User column should be hidden on Windows", userCol.visible)
            assertFalse("Group column should be hidden on Windows", groupCol.visible)
        } else {
            // Inode is hidden by default everywhere; every other column is visible on Unix.
            assertTrue("Non-Inode columns visible on Unix", defaults.filter { it.id != "Inode" }.all { it.visible })
        }
        assertFalse("Inode column should be hidden by default", defaults.find { it.id == "Inode" }!!.visible)
        assertTrue("All default styles should be default", defaults.all { it.style.isDefault() })
    }

    // --- Component style ---

    fun testComponentStyleDefault() {
        val style = ComponentStyle()
        assertTrue("New style should be default", style.isDefault())
        assertEquals("", style.fontFamily)
        assertEquals(0, style.fontSize)
        assertEquals(Font.PLAIN, style.fontStyle)
        assertEquals("", style.fontColor)
        assertNull("Default style should return null font", style.getFont(null))
        assertNull("Default style should return null color", style.parsedFontColor())
    }

    fun testComponentStyleWithCustomValues() {
        val style = ComponentStyle().apply {
            fontFamily = "Monospaced"
            fontSize = 14
            fontStyle = Font.BOLD
            fontColor = "#FF0000"
        }

        assertFalse(style.isDefault())
        val font = style.getFont(null)
        assertNotNull(font)
        assertEquals("Monospaced", font!!.family)
        assertEquals(14, font.size)
        assertEquals(Font.BOLD, font.style)
        val color = style.parsedFontColor()
        assertNotNull(color)
        assertEquals(Color.RED, color)
    }

    fun testComponentStyleCopyFrom() {
        val source = ComponentStyle().apply {
            fontFamily = "Arial"
            fontSize = 18
            fontStyle = Font.ITALIC
            fontColor = "#00FF00"
        }
        val target = ComponentStyle()

        target.copyFrom(source)

        assertEquals("Arial", target.fontFamily)
        assertEquals(18, target.fontSize)
        assertEquals(Font.ITALIC, target.fontStyle)
        assertEquals("#00FF00", target.fontColor)
    }

    fun testComponentStyleEquality() {
        val a = ComponentStyle().apply { fontFamily = "Arial"; fontSize = 12 }
        val b = ComponentStyle().apply { fontFamily = "Arial"; fontSize = 12 }
        val c = ComponentStyle().apply { fontFamily = "Arial"; fontSize = 14 }

        assertEquals(a, b)
        assertNotSame(a, b)
        assertFalse(a == c)
        assertEquals(a.hashCode(), b.hashCode())
    }

    fun testComponentStyleGetFontUsesDefaultWhenPartial() {
        val defaultFont = Font("Dialog", Font.PLAIN, 13)

        // Only fontSize set — should use default family
        val sizeOnly = ComponentStyle().apply { fontSize = 20 }
        val font = sizeOnly.getFont(defaultFont)
        assertNotNull(font)
        assertEquals(20, font!!.size)

        // Only fontStyle set — should produce a font
        val styleOnly = ComponentStyle().apply { fontStyle = Font.BOLD }
        val boldFont = styleOnly.getFont(defaultFont)
        assertNotNull(boldFont)
        assertEquals(Font.BOLD, boldFont!!.style)
    }

    fun testComponentStyleInvalidColorReturnsNull() {
        val style = ComponentStyle().apply { fontColor = "not-a-color" }
        assertNull("Invalid color should return null", style.parsedFontColor())
    }

    // --- Panel/tab font methods ---

    fun testGetPanelFontWithLegacyFields() {
        settings.loadState(TurtleCommanderSettings.State().apply {
            panelFontFamily = "Monospaced"
            panelFontSize = 15
        })

        val font = settings.getPanelFont()
        assertNotNull("Legacy font family should produce a font", font)
        assertEquals(15, font!!.size)
        assertEquals(Font.PLAIN, font.style)
    }

    fun testGetPanelFontStyleOverridesLegacy() {
        settings.loadState(TurtleCommanderSettings.State().apply {
            panelFontFamily = "Courier"
            panelFontSize = 15
            styles.panelStyle = ComponentStyle().apply {
                fontFamily = "Arial"
                fontSize = 20
                fontStyle = Font.BOLD
            }
        })

        val font = settings.getPanelFont()
        assertNotNull(font)
        // Don't assert font family — "Arial" may not be installed on CI (Linux), causing fallback
        assertEquals(20, font!!.size)
        assertEquals(Font.BOLD, font.style)
    }

    fun testGetTabFontWithLegacyFields() {
        settings.loadState(TurtleCommanderSettings.State().apply {
            tabFontFamily = "Monospaced"
            tabFontSize = 11
        })

        val font = settings.getTabFont()
        assertNotNull("Legacy tab font family should produce a font", font)
        assertEquals(11, font!!.size)
    }

    fun testGetPanelFontReturnsNullWhenUnset() {
        settings.loadState(TurtleCommanderSettings.State())

        assertNull("Panel font should be null when no font configured", settings.getPanelFont())
    }

    // --- All component styles initialized ---

    fun testAllComponentStylesInitialized() {
        val state = settings.state
        assertNotNull(state.styles.panelStyle)
        assertNotNull(state.styles.tabStyle)
        assertNotNull(state.styles.pathBarStyle)
        assertNotNull(state.styles.statusBarStyle)
        assertNotNull(state.styles.commandBarStyle)
        assertNotNull(state.styles.driveSelectorStyle)
        assertNotNull(state.styles.columnHeaderStyle)
    }
}
