package io.github.jhspetersson.turtlecommander.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ThemeDraftConfigurableTest : BasePlatformTestCase() {

    private fun names(state: TurtleCommanderSettings.State) = ThemeManager.getAllThemes(state).map { it.name }

    fun testThemeEditsStayInTheDialogUntilApplied() {
        val settings = TurtleCommanderSettings.getInstance().state
        ThemeManager.deleteTheme(settings, "Draft Only")
        val before = names(settings)
        val configurable = TurtleCommanderConfigurable(project)
        try {
            configurable.createComponent()
            configurable.reset()
            assertFalse(configurable.isModified())

            ThemeManager.saveTheme(configurable.themeDraft!!.state, Theme("Draft Only"))

            assertTrue(configurable.isModified())
            assertEquals(before, names(settings))

            configurable.reset()

            assertFalse(configurable.isModified())
            assertFalse("Draft Only" in names(configurable.themeDraft!!.state))

            ThemeManager.saveTheme(configurable.themeDraft!!.state, Theme("Draft Only"))
            configurable.apply()

            assertTrue("Draft Only" in names(settings))
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
            ThemeManager.deleteTheme(settings, "Draft Only")
        }
    }
}
