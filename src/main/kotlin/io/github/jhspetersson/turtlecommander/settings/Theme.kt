package io.github.jhspetersson.turtlecommander.settings

import java.awt.Font

data class ThemeStyle(
    val fontFamily: String = "",
    val fontSize: Int = 0,
    val fontStyle: Int = Font.PLAIN,
    val fontColor: String = "",
    val backgroundColor: String = "",
) {
    fun toComponentStyle(): ComponentStyle = ComponentStyle().apply {
        fontFamily = this@ThemeStyle.fontFamily
        fontSize = this@ThemeStyle.fontSize
        fontStyle = this@ThemeStyle.fontStyle
        fontColor = this@ThemeStyle.fontColor
        backgroundColor = this@ThemeStyle.backgroundColor
    }
}

data class Theme(
    val name: String,
    val panelStyle: ThemeStyle = ThemeStyle(),
    val tabStyle: ThemeStyle = ThemeStyle(),
    val pathBarStyle: ThemeStyle = ThemeStyle(),
    val statusBarStyle: ThemeStyle = ThemeStyle(),
    val commandBarStyle: ThemeStyle = ThemeStyle(),
    val driveSelectorStyle: ThemeStyle = ThemeStyle(),
    val columnHeaderStyle: ThemeStyle = ThemeStyle(),
) {
    fun applyTo(state: TurtleCommanderSettings.State) {
        state.panelStyle = panelStyle.toComponentStyle()
        state.tabStyle = tabStyle.toComponentStyle()
        state.pathBarStyle = pathBarStyle.toComponentStyle()
        state.statusBarStyle = statusBarStyle.toComponentStyle()
        state.commandBarStyle = commandBarStyle.toComponentStyle()
        state.driveSelectorStyle = driveSelectorStyle.toComponentStyle()
        state.columnHeaderStyle = columnHeaderStyle.toComponentStyle()
    }

    override fun toString(): String = name

    companion object {
        val DEFAULT = Theme(name = "Default")

        val NORTON_COMMANDER = Theme(
            name = "Norton Commander",
            panelStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 13,
                fontColor = "#00FFFF",
                backgroundColor = "#000080",
            ),
            tabStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 12,
                fontColor = "#FFFFFF",
                backgroundColor = "#000080",
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
            ),
            tabStyle = ThemeStyle(
                fontFamily = "Consolas",
                fontSize = 12,
                fontColor = "#33FF33",
                backgroundColor = "#1A1A1A",
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
            ),
            tabStyle = ThemeStyle(
                fontFamily = "Courier New",
                fontSize = 12,
                fontColor = "#FFD700",
                backgroundColor = "#5C3A11",
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

        val ALL_THEMES = listOf(DEFAULT, NORTON_COMMANDER, GREEN_TERMINAL, BROWN_OLDSCHOOL)
    }
}
