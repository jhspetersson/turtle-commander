package io.github.jhspetersson.turtlecommander.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import java.awt.Color
import java.awt.Font

interface TurtleCommanderSettingsListener {
    fun settingsChanged()
}

class ColumnConfig {
    var id: String = ""
    var visible: Boolean = true
    var style: ComponentStyle = ComponentStyle()

    override fun equals(other: Any?): Boolean {
        if (other !is ColumnConfig) return false
        return id == other.id && visible == other.visible && style == other.style
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + visible.hashCode()
        result = 31 * result + style.hashCode()
        return result
    }

    companion object {
        val ALL_COLUMN_IDS = listOf("Name", "Ext", "Size", "Date Created", "Date Modified", "Permissions")

        fun defaults(): List<ColumnConfig> = ALL_COLUMN_IDS.map { id ->
            ColumnConfig().apply { this.id = id }
        }
    }
}

class ComponentStyle {
    var fontFamily: String = ""
    var fontSize: Int = 0
    var fontStyle: Int = Font.PLAIN
    var fontColor: String = ""
    var backgroundColor: String = ""
    var selectedColor: String = ""
    var activeSelectedColor: String = ""

    fun getFont(defaultFont: Font?): Font? {
        val baseSize = if (fontSize > 0) fontSize else defaultFont?.size ?: 13
        val family = fontFamily.ifEmpty { defaultFont?.family ?: Font.DIALOG }
        return if (fontFamily.isNotEmpty() || fontSize > 0 || fontStyle != Font.PLAIN) {
            Font(family, fontStyle, baseSize)
        } else {
            null
        }
    }

    fun parsedFontColor(): Color? = parseColor(fontColor)

    fun parsedBackgroundColor(): Color? = parseColor(backgroundColor)

    fun parsedSelectedColor(): Color? = parseColor(selectedColor)

    fun parsedActiveSelectedColor(): Color? = parseColor(activeSelectedColor)

    fun copyFrom(other: ComponentStyle) {
        fontFamily = other.fontFamily
        fontSize = other.fontSize
        fontStyle = other.fontStyle
        fontColor = other.fontColor
        backgroundColor = other.backgroundColor
        selectedColor = other.selectedColor
        activeSelectedColor = other.activeSelectedColor
    }

    fun isDefault(): Boolean =
        fontFamily.isEmpty() && fontSize == 0 && fontStyle == Font.PLAIN
            && fontColor.isEmpty() && backgroundColor.isEmpty()
            && selectedColor.isEmpty() && activeSelectedColor.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (other !is ComponentStyle) return false
        return fontFamily == other.fontFamily && fontSize == other.fontSize
            && fontStyle == other.fontStyle && fontColor == other.fontColor
            && backgroundColor == other.backgroundColor
            && selectedColor == other.selectedColor
            && activeSelectedColor == other.activeSelectedColor
    }

    override fun hashCode(): Int {
        var result = fontFamily.hashCode()
        result = 31 * result + fontSize
        result = 31 * result + fontStyle
        result = 31 * result + fontColor.hashCode()
        result = 31 * result + backgroundColor.hashCode()
        result = 31 * result + selectedColor.hashCode()
        result = 31 * result + activeSelectedColor.hashCode()
        return result
    }
}

private fun parseColor(hex: String): Color? {
    if (hex.isBlank()) return null
    return try { Color.decode(hex) } catch (_: Exception) { null }
}

@Service(Service.Level.APP)
@State(
    name = "TurtleCommanderSettings",
    storages = [Storage("turtleCommanderSettings.xml")],
)
class TurtleCommanderSettings : PersistentStateComponent<TurtleCommanderSettings.State> {

    class State {
        var enableFileNameHighlighting: Boolean = true
        var showCommandBar: Boolean = true
        var hideDriveSelector: Boolean = false
        var hideStatusBar: Boolean = false
        var alwaysOverwriteFiles: Boolean = false
        var panelFontFamily: String = ""
        var panelFontSize: Int = 0
        var tabFontFamily: String = ""
        var tabFontSize: Int = 0
        var defaultViewMode: String = "TABLE"
        var sortWithDirectories: Boolean = false
        var calculateDirectorySize: Boolean = true

        var panelStyle: ComponentStyle = ComponentStyle()
        var tabStyle: ComponentStyle = ComponentStyle()
        var pathBarStyle: ComponentStyle = ComponentStyle()
        var statusBarStyle: ComponentStyle = ComponentStyle()
        var commandBarStyle: ComponentStyle = ComponentStyle()
        var driveSelectorStyle: ComponentStyle = ComponentStyle()
        var columnHeaderStyle: ComponentStyle = ComponentStyle()
        var themeName: String = ""
        var themes: MutableList<SavedTheme> = mutableListOf()
        var themesInitialized: Boolean = false

        // Styles saved before a theme was applied, so "Default" can restore them
        var preThemePanelStyle: ComponentStyle = ComponentStyle()
        var preThemeTabStyle: ComponentStyle = ComponentStyle()
        var preThemePathBarStyle: ComponentStyle = ComponentStyle()
        var preThemeStatusBarStyle: ComponentStyle = ComponentStyle()
        var preThemeCommandBarStyle: ComponentStyle = ComponentStyle()
        var preThemeDriveSelectorStyle: ComponentStyle = ComponentStyle()
        var preThemeColumnHeaderStyle: ComponentStyle = ComponentStyle()

        var columns: MutableList<ColumnConfig> = mutableListOf()
    }

    fun getEffectiveColumns(): List<ColumnConfig> {
        val saved = myState.columns
        if (saved.isEmpty()) return ColumnConfig.defaults()
        // Ensure all known columns are present (in case new columns were added)
        val savedIds = saved.map { it.id }.toSet()
        val result = saved.toMutableList()
        for (id in ColumnConfig.ALL_COLUMN_IDS) {
            if (id !in savedIds) {
                result.add(ColumnConfig().apply { this.id = id })
            }
        }
        return result
    }

    fun getPanelFont(): Font? {
        val s = myState
        // Use new panelStyle if it has values, otherwise fall back to legacy fields
        if (!s.panelStyle.isDefault()) return s.panelStyle.getFont(null)
        if (s.panelFontFamily.isEmpty() && s.panelFontSize <= 0) return null
        return if (s.panelFontFamily.isNotEmpty()) {
            Font(s.panelFontFamily, Font.PLAIN, if (s.panelFontSize > 0) s.panelFontSize else 13)
        } else {
            null
        }
    }

    fun getPanelFontSize(): Int {
        val s = myState
        if (s.panelStyle.fontSize > 0) return s.panelStyle.fontSize
        return s.panelFontSize
    }

    fun getTabFont(): Font? {
        val s = myState
        if (!s.tabStyle.isDefault()) return s.tabStyle.getFont(null)
        if (s.tabFontFamily.isEmpty() && s.tabFontSize <= 0) return null
        return if (s.tabFontFamily.isNotEmpty()) {
            Font(s.tabFontFamily, Font.PLAIN, if (s.tabFontSize > 0) s.tabFontSize else 12)
        } else {
            null
        }
    }

    fun getTabFontSize(): Int {
        val s = myState
        if (s.tabStyle.fontSize > 0) return s.tabStyle.fontSize
        return s.tabFontSize
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun fireSettingsChanged() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(TOPIC)
            .settingsChanged()
    }

    companion object {
        val TOPIC = Topic.create("TurtleCommanderSettings", TurtleCommanderSettingsListener::class.java)

        fun getInstance(): TurtleCommanderSettings =
            ApplicationManager.getApplication().getService(TurtleCommanderSettings::class.java)
    }
}
