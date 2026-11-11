package io.github.jhspetersson.turtlecommander.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeDraftTest {

    private fun seeded(): TurtleCommanderSettings.State =
        TurtleCommanderSettings.State().also { ThemeManager.ensureInitialThemes(it) }

    private fun names(state: TurtleCommanderSettings.State) = ThemeManager.getAllThemes(state).map { it.name }

    @Test
    fun `a fresh draft is not modified`() {
        val state = seeded()
        assertFalse(ThemeDraft(state).isModified(state))
    }

    @Test
    fun `save rename and delete stay in the draft until applied`() {
        val state = seeded()
        val before = names(state)
        val draft = ThemeDraft(state)

        ThemeManager.saveTheme(draft.state, Theme("Mine"))
        ThemeManager.renameTheme(draft.state, "Grey Ash", "Ash")
        ThemeManager.deleteTheme(draft.state, "Monochrome")

        assertTrue(draft.isModified(state))
        assertEquals(before, names(state))

        draft.applyTo(state)

        assertFalse(draft.isModified(state))
        assertEquals(names(draft.state), names(state))
        assertTrue("Mine" in names(state))
        assertTrue("Ash" in names(state))
        assertFalse("Monochrome" in names(state))
    }

    @Test
    fun `pre-theme styles are copied rather than shared`() {
        val state = seeded()
        val draft = ThemeDraft(state)

        draft.state.preThemeStyles.panelStyle.fontColor = "#123456"

        assertEquals("", state.preThemeStyles.panelStyle.fontColor)
        assertTrue(draft.isModified(state))
        draft.applyTo(state)
        assertEquals("#123456", state.preThemeStyles.panelStyle.fontColor)
        draft.state.preThemeStyles.reset()
        assertEquals("#123456", state.preThemeStyles.panelStyle.fontColor)
    }

    @Test
    fun `applying a draft does not alias its theme objects`() {
        val state = seeded()
        val draft = ThemeDraft(state)
        ThemeManager.saveTheme(draft.state, Theme("Mine"))
        draft.applyTo(state)

        ThemeManager.renameTheme(draft.state, "Mine", "Yours")

        assertTrue("Mine" in names(state))
        assertFalse("Yours" in names(state))
    }
}
