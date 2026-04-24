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

enum class PanelLayout { HORIZONTAL, VERTICAL, SINGLE }

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
        val ALL_COLUMN_IDS = listOf("Name", "Ext", "Size", "Date Created", "Date Modified", "User", "Group", "Permissions")

        private val UNIX_ONLY_COLUMNS = setOf("User", "Group")

        fun defaults(): List<ColumnConfig> {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            return ALL_COLUMN_IDS.map { id ->
                ColumnConfig().apply {
                    this.id = id
                    this.visible = !(isWindows && id in UNIX_ONLY_COLUMNS)
                }
            }
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

class StyleSet {
    var panelStyle: ComponentStyle = ComponentStyle()
    var tabStyle: ComponentStyle = ComponentStyle()
    var pathBarStyle: ComponentStyle = ComponentStyle()
    var statusBarStyle: ComponentStyle = ComponentStyle()
    var commandBarStyle: ComponentStyle = ComponentStyle()
    var commandButtonStyle: ComponentStyle = ComponentStyle()
    var driveSelectorStyle: ComponentStyle = ComponentStyle()
    var columnHeaderStyle: ComponentStyle = ComponentStyle()

    fun reset() {
        panelStyle = ComponentStyle()
        tabStyle = ComponentStyle()
        pathBarStyle = ComponentStyle()
        statusBarStyle = ComponentStyle()
        commandBarStyle = ComponentStyle()
        commandButtonStyle = ComponentStyle()
        driveSelectorStyle = ComponentStyle()
        columnHeaderStyle = ComponentStyle()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is StyleSet) return false
        return panelStyle == other.panelStyle && tabStyle == other.tabStyle
            && pathBarStyle == other.pathBarStyle && statusBarStyle == other.statusBarStyle
            && commandBarStyle == other.commandBarStyle && commandButtonStyle == other.commandButtonStyle
            && driveSelectorStyle == other.driveSelectorStyle && columnHeaderStyle == other.columnHeaderStyle
    }

    override fun hashCode(): Int {
        var result = panelStyle.hashCode()
        result = 31 * result + tabStyle.hashCode()
        result = 31 * result + pathBarStyle.hashCode()
        result = 31 * result + statusBarStyle.hashCode()
        result = 31 * result + commandBarStyle.hashCode()
        result = 31 * result + commandButtonStyle.hashCode()
        result = 31 * result + driveSelectorStyle.hashCode()
        result = 31 * result + columnHeaderStyle.hashCode()
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
        var deleteToRecycleBin: Boolean = false
        var panelFontFamily: String = ""
        var panelFontSize: Int = 0
        var tabFontFamily: String = ""
        var tabFontSize: Int = 0
        var defaultViewMode: String = "TABLE"
        var sortWithDirectories: Boolean = false
        var calculateDirectorySize: Boolean = true
        var panelLayout: String = PanelLayout.HORIZONTAL.name

        var styles: StyleSet = StyleSet()
        var themeName: String = ""
        var themes: MutableList<SavedTheme> = mutableListOf()
        var themesInitialized: Boolean = false

        // Styles saved before a theme was applied, so "Default" can restore them
        var preThemeStyles: StyleSet = StyleSet()

        var columns: MutableList<ColumnConfig> = mutableListOf()

        var nameSearchHistory: MutableList<String> = mutableListOf()
        var regexpSearchHistory: MutableList<String> = mutableListOf()
        var contentSearchHistory: MutableList<String> = mutableListOf()

        var colorRules: MutableList<SavedColorRule> = mutableListOf()
        var colorRulesInitialized: Boolean = false
        // "WINNER" | "LAYERED" — stored as string so XmlSerializer stays happy
        var colorizationMode: String = ColorizationMode.WINNER.name
    }

    fun getNameSearchHistory(regexp: Boolean): MutableList<String> =
        if (regexp) myState.regexpSearchHistory else myState.nameSearchHistory

    fun addNameSearchHistory(pattern: String, regexp: Boolean = false) {
        if (pattern.isBlank()) return
        val list = getNameSearchHistory(regexp)
        list.remove(pattern)
        list.add(0, pattern)
        while (list.size > 10) list.removeAt(list.size - 1)
    }

    val contentSearchHistory: MutableList<String> get() = myState.contentSearchHistory

    fun addContentSearchHistory(pattern: String) {
        if (pattern.isBlank()) return
        val list = contentSearchHistory
        list.remove(pattern)
        list.add(0, pattern)
        while (list.size > 10) list.removeAt(list.size - 1)
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
        if (!s.styles.panelStyle.isDefault()) return s.styles.panelStyle.getFont(null)
        if (s.panelFontFamily.isEmpty() && s.panelFontSize <= 0) return null
        return if (s.panelFontFamily.isNotEmpty()) {
            Font(s.panelFontFamily, Font.PLAIN, if (s.panelFontSize > 0) s.panelFontSize else 13)
        } else {
            null
        }
    }

    fun getPanelFontSize(): Int {
        val s = myState
        if (s.styles.panelStyle.fontSize > 0) return s.styles.panelStyle.fontSize
        return s.panelFontSize
    }

    fun getTabFont(): Font? {
        val s = myState
        if (!s.styles.tabStyle.isDefault()) return s.styles.tabStyle.getFont(null)
        if (s.tabFontFamily.isEmpty() && s.tabFontSize <= 0) return null
        return if (s.tabFontFamily.isNotEmpty()) {
            Font(s.tabFontFamily, Font.PLAIN, if (s.tabFontSize > 0) s.tabFontSize else 12)
        } else {
            null
        }
    }

    fun getTabFontSize(): Int {
        val s = myState
        if (s.styles.tabStyle.fontSize > 0) return s.styles.tabStyle.fontSize
        return s.tabFontSize
    }

    private var myState = State()

    override fun getState(): State {
        ThemeManager.ensureInitialThemes(myState)
        ColorRuleManager.ensureInitialRules(myState)
        return myState
    }

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
