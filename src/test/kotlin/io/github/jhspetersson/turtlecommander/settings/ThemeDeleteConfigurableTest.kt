package io.github.jhspetersson.turtlecommander.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ThemeDeleteConfigurableTest : BasePlatformTestCase() {

    private fun panelFontColor(configurable: TurtleCommanderConfigurable): String =
        ComponentStyle().also { configurable.styleEditors["panel"]!!.applyTo(it) }.fontColor

    fun testDeletingTheSelectedThemeRestoresTheDefaultStylesInTheEditors() {
        val settings = TurtleCommanderSettings.getInstance().state
        val savedThemeName = settings.themeName
        settings.themeName = ""
        val configurable = TurtleCommanderConfigurable(project)
        try {
            configurable.createComponent()
            configurable.reset()
            val before = panelFontColor(configurable)
            val doomed = Theme("Doomed", panelStyle = ThemeStyle(fontColor = "#112233"))
            ThemeManager.saveTheme(configurable.themeDraft!!.state, doomed)
            configurable.rebuildThemeCombo()
            val combo = configurable.themeCombo!!
            combo.selectedItem = (0 until combo.itemCount).map { combo.getItemAt(it) }.first { it.name == "Doomed" }
            assertEquals("#112233", panelFontColor(configurable))

            configurable.removeTheme(doomed)

            assertEquals(Theme.DEFAULT.name, (combo.selectedItem as Theme).name)
            assertEquals(before, panelFontColor(configurable))
            assertEquals(before, configurable.themeDraft!!.state.preThemeStyles.panelStyle.fontColor)

            combo.selectedItem = (0 until combo.itemCount).map { combo.getItemAt(it) }.first { it.name != Theme.DEFAULT.name }
            assertEquals(before, configurable.themeDraft!!.state.preThemeStyles.panelStyle.fontColor)
        } finally {
            configurable.disposeUIResources()
            settings.themeName = savedThemeName
        }
    }
}
