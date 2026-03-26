package io.github.jhspetersson.turtlecommander

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

class ComponentStyle {
    var fontFamily: String = ""
    var fontSize: Int = 0
    var fontStyle: Int = Font.PLAIN
    var fontColor: String = ""

    fun getFont(defaultFont: Font?): Font? {
        val baseSize = if (fontSize > 0) fontSize else defaultFont?.size ?: 13
        val family = fontFamily.ifEmpty { defaultFont?.family ?: Font.DIALOG }
        return if (fontFamily.isNotEmpty() || fontSize > 0 || fontStyle != Font.PLAIN) {
            Font(family, fontStyle, baseSize)
        } else {
            null
        }
    }

    fun getFontColor(): Color? = parseColor(fontColor)

    fun copyFrom(other: ComponentStyle) {
        fontFamily = other.fontFamily
        fontSize = other.fontSize
        fontStyle = other.fontStyle
        fontColor = other.fontColor
    }

    fun isDefault(): Boolean =
        fontFamily.isEmpty() && fontSize == 0 && fontStyle == Font.PLAIN
            && fontColor.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (other !is ComponentStyle) return false
        return fontFamily == other.fontFamily && fontSize == other.fontSize
            && fontStyle == other.fontStyle && fontColor == other.fontColor
    }

    override fun hashCode(): Int {
        var result = fontFamily.hashCode()
        result = 31 * result + fontSize
        result = 31 * result + fontStyle
        result = 31 * result + fontColor.hashCode()
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
