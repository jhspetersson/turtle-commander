package io.github.jhspetersson.turtlecommander.settings

import java.awt.Font

data class ThemeStyle(
    val fontFamily: String = "",
    val fontSize: Int = 0,
    val fontStyle: Int = Font.PLAIN,
    val fontColor: String = "",
    val backgroundColor: String = "",
    val selectedColor: String = "",
    val activeSelectedColor: String = "",
) {
    fun toComponentStyle(): ComponentStyle = ComponentStyle().apply {
        fontFamily = this@ThemeStyle.fontFamily
        fontSize = this@ThemeStyle.fontSize
        fontStyle = this@ThemeStyle.fontStyle
        fontColor = this@ThemeStyle.fontColor
        backgroundColor = this@ThemeStyle.backgroundColor
        selectedColor = this@ThemeStyle.selectedColor
        activeSelectedColor = this@ThemeStyle.activeSelectedColor
    }

    companion object {
        fun fromComponentStyle(cs: ComponentStyle) = ThemeStyle(
            fontFamily = cs.fontFamily,
            fontSize = cs.fontSize,
            fontStyle = cs.fontStyle,
            fontColor = cs.fontColor,
            backgroundColor = cs.backgroundColor,
            selectedColor = cs.selectedColor,
            activeSelectedColor = cs.activeSelectedColor,
        )
    }
}

data class Theme(
    val name: String,
    val panelStyle: ThemeStyle = ThemeStyle(),
    val tabStyle: ThemeStyle = ThemeStyle(),
    val pathBarStyle: ThemeStyle = ThemeStyle(),
    val statusBarStyle: ThemeStyle = ThemeStyle(),
    val commandBarStyle: ThemeStyle = ThemeStyle(),
    val commandButtonStyle: ThemeStyle = ThemeStyle(),
    val driveSelectorStyle: ThemeStyle = ThemeStyle(),
    val columnHeaderStyle: ThemeStyle = ThemeStyle(),
) {
    fun applyTo(state: TurtleCommanderSettings.State) {
        state.styles.panelStyle = panelStyle.toComponentStyle()
        state.styles.tabStyle = tabStyle.toComponentStyle()
        state.styles.pathBarStyle = pathBarStyle.toComponentStyle()
        state.styles.statusBarStyle = statusBarStyle.toComponentStyle()
        state.styles.commandBarStyle = commandBarStyle.toComponentStyle()
        state.styles.commandButtonStyle = commandButtonStyle.toComponentStyle()
        state.styles.driveSelectorStyle = driveSelectorStyle.toComponentStyle()
        state.styles.columnHeaderStyle = columnHeaderStyle.toComponentStyle()
    }

    override fun toString(): String = name

    companion object {
        val DEFAULT = Theme(name = "Default")

        val CLASSIC_NC = Theme(
            name = "Classic NC",
            panelStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#00FFFF",
                backgroundColor = "#000080",
                selectedColor = "#000050",
                activeSelectedColor = "#0000AA",
            ),
            tabStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 12,
            ),
            pathBarStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#FFFFFF",
                backgroundColor = "#000080",
            ),
            statusBarStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#00FFFF",
                backgroundColor = "#000080",
            ),
            commandBarStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                backgroundColor = "#000000",
            ),
            commandButtonStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#000000",
                backgroundColor = "#00AAAA",
            ),
            driveSelectorStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#FFFFFF",
                backgroundColor = "#000080",
            ),
            columnHeaderStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#FFFF00",
                backgroundColor = "#000080",
            ),
        )

        val GREEN_TERMINAL = Theme(
            name = "Green Terminal",
            panelStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#33FF33",
                backgroundColor = "#0A0A0A",
                selectedColor = "#1A3A1A",
                activeSelectedColor = "#0A4A0A",
            ),
            tabStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 12,
            ),
            pathBarStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#33FF33",
                backgroundColor = "#0A0A0A",
            ),
            statusBarStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#33FF33",
                backgroundColor = "#0A0A0A",
            ),
            commandBarStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                backgroundColor = "#0A0A0A",
            ),
            commandButtonStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#33FF33",
                backgroundColor = "#1A1A1A",
            ),
            driveSelectorStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#33FF33",
                backgroundColor = "#0A0A0A",
            ),
            columnHeaderStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#00CC00",
                backgroundColor = "#1A1A1A",
            ),
        )

        val BROWN_OLDSCHOOL = Theme(
            name = "Brown Oldschool",
            panelStyle = ThemeStyle(
                fontFamily = "Courier New",
                fontSize = 13,
                fontColor = "#FFD700",
                backgroundColor = "#3B2507",
                selectedColor = "#5C3A11",
                activeSelectedColor = "#7A4E1A",
            ),
            tabStyle = ThemeStyle(
                fontFamily = "Courier New",
                fontSize = 12,
            ),
            pathBarStyle = ThemeStyle(
                fontFamily = "Courier New",
                fontSize = 13,
                fontColor = "#FFDEAD",
                backgroundColor = "#3B2507",
            ),
            statusBarStyle = ThemeStyle(
                fontFamily = "Courier New",
                fontSize = 13,
                fontColor = "#FFDEAD",
                backgroundColor = "#3B2507",
            ),
            commandBarStyle = ThemeStyle(
                fontFamily = "Courier New",
                fontSize = 13,
                backgroundColor = "#3B2507",
            ),
            commandButtonStyle = ThemeStyle(
                fontFamily = "Courier New",
                fontSize = 13,
                fontColor = "#FFD700",
                backgroundColor = "#5C3A11",
            ),
            driveSelectorStyle = ThemeStyle(
                fontFamily = "Courier New",
                fontSize = 13,
                fontColor = "#FFD700",
                backgroundColor = "#3B2507",
            ),
            columnHeaderStyle = ThemeStyle(
                fontFamily = "Courier New",
                fontSize = 13,
                fontColor = "#FFA500",
                backgroundColor = "#5C3A11",
            ),
        )

        val GREY_ASH = Theme(
            name = "Grey Ash",
            panelStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#CCCCBB",
                backgroundColor = "#3D3D35",
                selectedColor = "#4D4D45",
                activeSelectedColor = "#5D5D55",
            ),
            tabStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 12,
            ),
            pathBarStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#DDDDCC",
                backgroundColor = "#3D3D35",
            ),
            statusBarStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#BBBBAA",
                backgroundColor = "#3D3D35",
            ),
            commandBarStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                backgroundColor = "#3D3D35",
            ),
            commandButtonStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#CCCCBB",
                backgroundColor = "#4D4D45",
            ),
            driveSelectorStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#CCCCBB",
                backgroundColor = "#3D3D35",
            ),
            columnHeaderStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#AAAA99",
                backgroundColor = "#4D4D45",
            ),
        )

        val MONOCHROME = Theme(
            name = "Monochrome",
            panelStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#FFFFFF",
                backgroundColor = "#000000",
                selectedColor = "#222222",
                activeSelectedColor = "#444444",
            ),
            tabStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 12,
            ),
            pathBarStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#FFFFFF",
                backgroundColor = "#000000",
            ),
            statusBarStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#CCCCCC",
                backgroundColor = "#000000",
            ),
            commandBarStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                backgroundColor = "#000000",
            ),
            commandButtonStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#FFFFFF",
                backgroundColor = "#000000",
            ),
            driveSelectorStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#FFFFFF",
                backgroundColor = "#000000",
            ),
            columnHeaderStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#999999",
                backgroundColor = "#000000",
            ),
        )

        /** Themes seeded on first plugin install. */
        val INITIAL_THEMES = listOf(DEFAULT, CLASSIC_NC, GREEN_TERMINAL, BROWN_OLDSCHOOL, GREY_ASH, MONOCHROME)
    }
}

/**
 * Serializable theme entry for persistence in settings.
 * IntelliJ's XmlSerializer needs mutable fields with no-arg constructor.
 */
class SavedTheme {
    var name: String = ""
    var panelFontFamily: String = ""
    var panelFontSize: Int = 0
    var panelFontStyle: Int = Font.PLAIN
    var panelFontColor: String = ""
    var panelBackgroundColor: String = ""
    var panelSelectedColor: String = ""
    var panelActiveSelectedColor: String = ""
    var tabFontFamily: String = ""
    var tabFontSize: Int = 0
    var tabFontStyle: Int = Font.PLAIN
    var tabFontColor: String = ""
    var tabBackgroundColor: String = ""
    var pathBarFontFamily: String = ""
    var pathBarFontSize: Int = 0
    var pathBarFontStyle: Int = Font.PLAIN
    var pathBarFontColor: String = ""
    var pathBarBackgroundColor: String = ""
    var statusBarFontFamily: String = ""
    var statusBarFontSize: Int = 0
    var statusBarFontStyle: Int = Font.PLAIN
    var statusBarFontColor: String = ""
    var statusBarBackgroundColor: String = ""
    var commandBarFontFamily: String = ""
    var commandBarFontSize: Int = 0
    var commandBarFontStyle: Int = Font.PLAIN
    var commandBarFontColor: String = ""
    var commandBarBackgroundColor: String = ""
    var commandButtonFontFamily: String = ""
    var commandButtonFontSize: Int = 0
    var commandButtonFontStyle: Int = Font.PLAIN
    var commandButtonFontColor: String = ""
    var commandButtonBackgroundColor: String = ""
    var driveSelectorFontFamily: String = ""
    var driveSelectorFontSize: Int = 0
    var driveSelectorFontStyle: Int = Font.PLAIN
    var driveSelectorFontColor: String = ""
    var driveSelectorBackgroundColor: String = ""
    var columnHeaderFontFamily: String = ""
    var columnHeaderFontSize: Int = 0
    var columnHeaderFontStyle: Int = Font.PLAIN
    var columnHeaderFontColor: String = ""
    var columnHeaderBackgroundColor: String = ""

    fun toTheme(): Theme = Theme(
        name = name,
        panelStyle = ThemeStyle(panelFontFamily, panelFontSize, panelFontStyle, panelFontColor, panelBackgroundColor, panelSelectedColor, panelActiveSelectedColor),
        tabStyle = ThemeStyle(tabFontFamily, tabFontSize, tabFontStyle, tabFontColor, tabBackgroundColor),
        pathBarStyle = ThemeStyle(pathBarFontFamily, pathBarFontSize, pathBarFontStyle, pathBarFontColor, pathBarBackgroundColor),
        statusBarStyle = ThemeStyle(statusBarFontFamily, statusBarFontSize, statusBarFontStyle, statusBarFontColor, statusBarBackgroundColor),
        commandBarStyle = ThemeStyle(commandBarFontFamily, commandBarFontSize, commandBarFontStyle, commandBarFontColor, commandBarBackgroundColor),
        commandButtonStyle = ThemeStyle(commandButtonFontFamily, commandButtonFontSize, commandButtonFontStyle, commandButtonFontColor, commandButtonBackgroundColor),
        driveSelectorStyle = ThemeStyle(driveSelectorFontFamily, driveSelectorFontSize, driveSelectorFontStyle, driveSelectorFontColor, driveSelectorBackgroundColor),
        columnHeaderStyle = ThemeStyle(columnHeaderFontFamily, columnHeaderFontSize, columnHeaderFontStyle, columnHeaderFontColor, columnHeaderBackgroundColor),
    )

    companion object {
        fun fromTheme(theme: Theme): SavedTheme = SavedTheme().apply {
            name = theme.name
            panelFontFamily = theme.panelStyle.fontFamily; panelFontSize = theme.panelStyle.fontSize; panelFontStyle = theme.panelStyle.fontStyle; panelFontColor = theme.panelStyle.fontColor; panelBackgroundColor = theme.panelStyle.backgroundColor; panelSelectedColor = theme.panelStyle.selectedColor; panelActiveSelectedColor = theme.panelStyle.activeSelectedColor
            tabFontFamily = theme.tabStyle.fontFamily; tabFontSize = theme.tabStyle.fontSize; tabFontStyle = theme.tabStyle.fontStyle; tabFontColor = theme.tabStyle.fontColor; tabBackgroundColor = theme.tabStyle.backgroundColor
            pathBarFontFamily = theme.pathBarStyle.fontFamily; pathBarFontSize = theme.pathBarStyle.fontSize; pathBarFontStyle = theme.pathBarStyle.fontStyle; pathBarFontColor = theme.pathBarStyle.fontColor; pathBarBackgroundColor = theme.pathBarStyle.backgroundColor
            statusBarFontFamily = theme.statusBarStyle.fontFamily; statusBarFontSize = theme.statusBarStyle.fontSize; statusBarFontStyle = theme.statusBarStyle.fontStyle; statusBarFontColor = theme.statusBarStyle.fontColor; statusBarBackgroundColor = theme.statusBarStyle.backgroundColor
            commandBarFontFamily = theme.commandBarStyle.fontFamily; commandBarFontSize = theme.commandBarStyle.fontSize; commandBarFontStyle = theme.commandBarStyle.fontStyle; commandBarFontColor = theme.commandBarStyle.fontColor; commandBarBackgroundColor = theme.commandBarStyle.backgroundColor
            commandButtonFontFamily = theme.commandButtonStyle.fontFamily; commandButtonFontSize = theme.commandButtonStyle.fontSize; commandButtonFontStyle = theme.commandButtonStyle.fontStyle; commandButtonFontColor = theme.commandButtonStyle.fontColor; commandButtonBackgroundColor = theme.commandButtonStyle.backgroundColor
            driveSelectorFontFamily = theme.driveSelectorStyle.fontFamily; driveSelectorFontSize = theme.driveSelectorStyle.fontSize; driveSelectorFontStyle = theme.driveSelectorStyle.fontStyle; driveSelectorFontColor = theme.driveSelectorStyle.fontColor; driveSelectorBackgroundColor = theme.driveSelectorStyle.backgroundColor
            columnHeaderFontFamily = theme.columnHeaderStyle.fontFamily; columnHeaderFontSize = theme.columnHeaderStyle.fontSize; columnHeaderFontStyle = theme.columnHeaderStyle.fontStyle; columnHeaderFontColor = theme.columnHeaderStyle.fontColor; columnHeaderBackgroundColor = theme.columnHeaderStyle.backgroundColor
        }
    }
}

/**
 * Manages the theme list persisted in settings.
 * On first use, seeds initial themes; after that all themes are user-owned.
 */
object ThemeManager {

    fun ensureInitialThemes(state: TurtleCommanderSettings.State) {
        if (!state.themesInitialized) {
            for (theme in Theme.INITIAL_THEMES) {
                state.themes.add(SavedTheme.fromTheme(theme))
            }
            state.themesInitialized = true
        }
    }

    fun getAllThemes(state: TurtleCommanderSettings.State): List<Theme> {
        ensureInitialThemes(state)
        val themes = state.themes.map { it.toTheme() }
        val default = themes.filter { it.name == Theme.DEFAULT.name }
        val rest = themes.filter { it.name != Theme.DEFAULT.name }.sortedBy { it.name }
        return default + rest
    }

    fun saveTheme(state: TurtleCommanderSettings.State, theme: Theme): SaveResult {
        val existingIdx = state.themes.indexOfFirst { it.name == theme.name }
        if (existingIdx >= 0) {
            state.themes[existingIdx] = SavedTheme.fromTheme(theme)
            return SaveResult.Replaced
        }
        state.themes.add(SavedTheme.fromTheme(theme))
        return SaveResult.Saved
    }

    fun deleteTheme(state: TurtleCommanderSettings.State, themeName: String): Boolean {
        return state.themes.removeIf { it.name == themeName }
    }

    fun renameTheme(state: TurtleCommanderSettings.State, oldName: String, newName: String): RenameResult {
        if (newName.isBlank()) return RenameResult.InvalidName
        if (oldName == newName) return RenameResult.Success
        if (state.themes.any { it.name == newName }) return RenameResult.Conflict
        val entry = state.themes.find { it.name == oldName } ?: return RenameResult.NotFound
        entry.name = newName
        return RenameResult.Success
    }

    fun exportTheme(theme: Theme): String {
        val sb = StringBuilder()
        sb.appendLine("name=${theme.name}")
        appendStyle(sb, "panel", theme.panelStyle)
        appendStyle(sb, "tab", theme.tabStyle)
        appendStyle(sb, "pathBar", theme.pathBarStyle)
        appendStyle(sb, "statusBar", theme.statusBarStyle)
        appendStyle(sb, "commandBar", theme.commandBarStyle)
        appendStyle(sb, "commandButton", theme.commandButtonStyle)
        appendStyle(sb, "driveSelector", theme.driveSelectorStyle)
        appendStyle(sb, "columnHeader", theme.columnHeaderStyle)
        return sb.toString()
    }

    fun importTheme(text: String): Theme? {
        val props = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            props[trimmed.substring(0, eqIdx).trim()] = trimmed.substring(eqIdx + 1).trim()
        }
        val name = props["name"] ?: return null
        if (name.isBlank()) return null
        return Theme(
            name = name,
            panelStyle = parseStyle(props, "panel"),
            tabStyle = parseStyle(props, "tab"),
            pathBarStyle = parseStyle(props, "pathBar"),
            statusBarStyle = parseStyle(props, "statusBar"),
            commandBarStyle = parseStyle(props, "commandBar"),
            commandButtonStyle = parseStyle(props, "commandButton"),
            driveSelectorStyle = parseStyle(props, "driveSelector"),
            columnHeaderStyle = parseStyle(props, "columnHeader"),
        )
    }

    /**
     * Import a theme into state. Returns the result and the effective name used.
     */
    fun importThemeToState(state: TurtleCommanderSettings.State, theme: Theme, overwrite: Boolean = false): Pair<ImportResult, String> {
        val conflict = state.themes.any { it.name == theme.name }

        if (conflict) {
            if (overwrite) {
                val idx = state.themes.indexOfFirst { it.name == theme.name }
                state.themes[idx] = SavedTheme.fromTheme(theme)
                return Pair(ImportResult.Replaced, theme.name)
            } else {
                val newName = generateUniqueName(theme.name, state)
                state.themes.add(SavedTheme.fromTheme(theme.copy(name = newName)))
                return Pair(ImportResult.Renamed, newName)
            }
        }
        state.themes.add(SavedTheme.fromTheme(theme))
        return Pair(ImportResult.Imported, theme.name)
    }

    internal fun generateUniqueName(baseName: String, state: TurtleCommanderSettings.State): String {
        val allNames = state.themes.map { it.name }.toSet()
        var candidate = "$baseName (2)"
        var counter = 3
        while (candidate in allNames) {
            candidate = "$baseName ($counter)"
            counter++
        }
        return candidate
    }

    private fun appendStyle(sb: StringBuilder, prefix: String, style: ThemeStyle) {
        if (style.fontFamily.isNotEmpty()) sb.appendLine("$prefix.fontFamily=${style.fontFamily}")
        if (style.fontSize > 0) sb.appendLine("$prefix.fontSize=${style.fontSize}")
        if (style.fontStyle != Font.PLAIN) sb.appendLine("$prefix.fontStyle=${style.fontStyle}")
        if (style.fontColor.isNotEmpty()) sb.appendLine("$prefix.fontColor=${style.fontColor}")
        if (style.backgroundColor.isNotEmpty()) sb.appendLine("$prefix.backgroundColor=${style.backgroundColor}")
        if (style.selectedColor.isNotEmpty()) sb.appendLine("$prefix.selectedColor=${style.selectedColor}")
        if (style.activeSelectedColor.isNotEmpty()) sb.appendLine("$prefix.activeSelectedColor=${style.activeSelectedColor}")
    }

    private fun parseStyle(props: Map<String, String>, prefix: String): ThemeStyle = ThemeStyle(
        fontFamily = props["$prefix.fontFamily"] ?: "",
        fontSize = props["$prefix.fontSize"]?.toIntOrNull() ?: 0,
        fontStyle = props["$prefix.fontStyle"]?.toIntOrNull() ?: Font.PLAIN,
        fontColor = props["$prefix.fontColor"] ?: "",
        backgroundColor = props["$prefix.backgroundColor"] ?: "",
        selectedColor = props["$prefix.selectedColor"] ?: "",
        activeSelectedColor = props["$prefix.activeSelectedColor"] ?: "",
    )

    enum class SaveResult { Saved, Replaced }
    enum class RenameResult { Success, Conflict, NotFound, InvalidName }
    enum class ImportResult { Imported, Replaced, Renamed }
}
