package io.github.jhspetersson.turtlecommander.settings

internal class ThemeDraft(source: TurtleCommanderSettings.State) {

    val state: TurtleCommanderSettings.State = TurtleCommanderSettings.State().also { draft ->
        ThemeManager.ensureInitialThemes(source)
        draft.themes = copyThemes(source.themes)
        draft.themesInitialized = true
        draft.preThemeStyles = source.preThemeStyles.copy()
    }

    fun isModified(target: TurtleCommanderSettings.State): Boolean =
        themesOf(state) != themesOf(target) || state.preThemeStyles != target.preThemeStyles

    fun applyTo(target: TurtleCommanderSettings.State) {
        target.themes = copyThemes(state.themes)
        target.themesInitialized = true
        target.preThemeStyles = state.preThemeStyles.copy()
    }

    private fun themesOf(s: TurtleCommanderSettings.State): List<Theme> = s.themes.map { it.toTheme() }

    private fun copyThemes(themes: List<SavedTheme>): MutableList<SavedTheme> =
        themes.map { SavedTheme.fromTheme(it.toTheme()) }.toMutableList()
}
