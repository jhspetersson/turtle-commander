package io.github.jhspetersson.turtlecommander

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import java.awt.Font

interface TurtleCommanderSettingsListener {
    fun settingsChanged()
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
    }

    fun getPanelFont(): Font? {
        val s = myState
        if (s.panelFontFamily.isEmpty() && s.panelFontSize <= 0) return null
        return if (s.panelFontFamily.isNotEmpty()) {
            Font(s.panelFontFamily, Font.PLAIN, if (s.panelFontSize > 0) s.panelFontSize else 13)
        } else {
            null // size-only; handled by applyPanelFont with deriveFont
        }
    }

    fun getPanelFontSize(): Int = myState.panelFontSize

    fun getTabFont(): Font? {
        val s = myState
        if (s.tabFontFamily.isEmpty() && s.tabFontSize <= 0) return null
        return if (s.tabFontFamily.isNotEmpty()) {
            Font(s.tabFontFamily, Font.PLAIN, if (s.tabFontSize > 0) s.tabFontSize else 12)
        } else {
            null // size-only; handled by applyFonts with deriveFont
        }
    }

    fun getTabFontSize(): Int = myState.tabFontSize

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
