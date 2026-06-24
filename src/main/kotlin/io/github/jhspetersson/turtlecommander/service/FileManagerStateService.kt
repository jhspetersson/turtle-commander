package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import io.github.jhspetersson.turtlecommander.settings.PanelLayout
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.CommandInputBar
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab

interface FavoritesChangeListener {
    fun favoritesChanged()
}

@Service(Service.Level.PROJECT)
@State(
    name = "TurtleCommanderFileManager",
    storages = [Storage("turtleCommander.xml")],
)
class FileManagerStateService(
    private val project: Project,
) : PersistentStateComponent<FileManagerStateService.FileManagerState> {

    private var myState = FileManagerState()

    var leftPanel: FileManagerPanel? = null
        private set
    var rightPanel: FileManagerPanel? = null
        private set
    private var splitter: OnePixelSplitter? = null
    private var swapSinglePanelCallback: (() -> Unit)? = null
    private var commandInputBar: CommandInputBar? = null

    fun registerPanels(left: FileManagerPanel, right: FileManagerPanel, split: OnePixelSplitter?) {
        leftPanel = left
        rightPanel = right
        splitter = split
    }

    fun updateSplitter(split: OnePixelSplitter?) {
        splitter = split
    }

    fun setSwapSinglePanelCallback(cb: (() -> Unit)?) {
        swapSinglePanelCallback = cb
    }

    fun registerCommandInputBar(bar: CommandInputBar) {
        commandInputBar = bar
    }

    /** Show the bottom command line, targeting whichever panel is currently active. */
    fun activateCommandInput() {
        commandInputBar?.activate(getActivePanel())
    }

    override fun getState(): FileManagerState {
        leftPanel?.let { myState.leftPanel = it.saveState() }
        rightPanel?.let { myState.rightPanel = it.saveState() }
        splitter?.let { myState.splitProportion = it.proportion }
        return myState
    }

    override fun loadState(state: FileManagerState) {
        myState = state
        myState.leftPanel.migrateIfNeeded()
        myState.rightPanel.migrateIfNeeded()
        migrateColumnStateToTabs()
    }

    private fun migrateColumnStateToTabs() {
        if (myState.pathColumns.isEmpty()) return
        for (panel in listOf(myState.leftPanel, myState.rightPanel)) {
            for (tab in panel.tabs) {
                if (tab.columnWidths.isEmpty() && tab.columnOrder.isEmpty()) {
                    val entry = myState.pathColumns.find { it.path == tab.path }
                    if (entry != null) {
                        tab.columnWidths = entry.columnWidths
                        tab.columnOrder = entry.columnOrder
                    }
                }
            }
        }
    }

    class FileManagerState {
        var splitProportion: Float = 0.5f

        @Tag("leftPanel")
        var leftPanel: PanelState = PanelState()

        @Tag("rightPanel")
        var rightPanel: PanelState = PanelState()

        @XCollection
        var pathColumns: MutableList<PathColumnEntry> = mutableListOf()

        @XCollection(elementTypes = [String::class])
        var favoritePaths: MutableList<String> = mutableListOf()

        @XCollection
        var favoriteEntries: MutableList<FavoriteEntry> = mutableListOf()

    }

    @Tag("favorite")
    class FavoriteEntry {
        @Attribute
        var path: String = ""
        @Attribute
        var color: String = ""

        /** Stable [io.github.jhspetersson.turtlecommander.settings.RuleIcons] key, or empty for the default folder icon. */
        @Attribute
        var icon: String = ""

        /** Required by XML deserializer */
        constructor()

        constructor(path: String, color: String = "", icon: String = "") {
            this.path = path
            this.color = color
            this.icon = icon
        }
    }

    @Tag("tab")
    class TabState {
        @Attribute var path: String = ""
        @Attribute var viewMode: String = "TABLE"
        var columnWidths: String = ""
        var columnOrder: String = ""
        var sortColumn: Int = -1
        var sortAscending: Boolean = true

        @Attribute var showAllNestedFiles: Boolean = false

        constructor()

        constructor(
            path: String,
            viewMode: String = "TABLE",
            columnWidths: String = "",
            columnOrder: String = "",
            sortColumn: Int = -1,
            sortAscending: Boolean = true,
            showAllNestedFiles: Boolean = false,
        ) {
            this.path = path
            this.viewMode = viewMode
            this.columnWidths = columnWidths
            this.columnOrder = columnOrder
            this.sortColumn = sortColumn
            this.sortAscending = sortAscending
            this.showAllNestedFiles = showAllNestedFiles
        }
    }

    @Tag("panel")
    class PanelState {
        @XCollection
        var tabs: MutableList<TabState> = mutableListOf()
        var selectedTabIndex: Int = 0

        // Legacy fields for backward compatibility
        @XCollection(elementTypes = [String::class])
        var tabPaths: MutableList<String> = mutableListOf()
        @XCollection(elementTypes = [String::class])
        var tabViewModes: MutableList<String> = mutableListOf()

        fun migrateIfNeeded() {
            if (tabs.isEmpty() && tabPaths.isNotEmpty()) {
                for ((i, path) in tabPaths.withIndex()) {
                    val mode = tabViewModes.getOrElse(i) { "TABLE" }
                    tabs.add(TabState(path = path, viewMode = mode))
                }
                tabPaths.clear()
                tabViewModes.clear()
            }
        }
    }

    @Tag("path-columns")
    class PathColumnEntry {
        var path: String = ""
        var columnWidths: String = ""
        var columnOrder: String = ""
    }

    fun getActivePanel(): FileManagerPanel? {
        val left = leftPanel ?: return null
        val right = rightPanel ?: return null
        return if (left.hasFocusInPanel()) left else right
    }

    fun getActiveTab(): FileTab? {
        return getActivePanel()?.getActiveTab()
    }

    private fun migrateFavoritesIfNeeded() {
        if (myState.favoritePaths.isNotEmpty() && myState.favoriteEntries.isEmpty()) {
            myState.favoriteEntries.addAll(myState.favoritePaths.map { FavoriteEntry(it) })
            myState.favoritePaths.clear()
        }
    }

    fun addFavorite(path: String, color: String = "") {
        migrateFavoritesIfNeeded()
        if (myState.favoriteEntries.none { it.path == path }) {
            myState.favoriteEntries.add(FavoriteEntry(path, color))
            fireFavoritesChanged()
        }
    }

    fun removeFavorite(path: String) {
        migrateFavoritesIfNeeded()
        if (myState.favoriteEntries.removeAll { it.path == path }) {
            fireFavoritesChanged()
        }
    }

    fun getFavorites(): List<String> {
        migrateFavoritesIfNeeded()
        return myState.favoriteEntries.map { it.path }
    }

    fun getFavoriteEntries(): List<FavoriteEntry> {
        migrateFavoritesIfNeeded()
        return myState.favoriteEntries.toList()
    }

    fun setFavoriteEntries(entries: List<FavoriteEntry>) {
        myState.favoriteEntries.clear()
        myState.favoriteEntries.addAll(entries)
        myState.favoritePaths.clear()
        fireFavoritesChanged()
    }

    private fun fireFavoritesChanged() {
        project.messageBus.syncPublisher(FAVORITES_TOPIC).favoritesChanged()
    }

    companion object {
        val FAVORITES_TOPIC = Topic.create("TurtleCommanderFavorites", FavoritesChangeListener::class.java)
    }

    fun switchToOtherPanel() {
        commandInputBar?.let {
            if (it.hasFieldFocus()) {
                it.switchTargetPanel()
                return
            }
        }
        val left = leftPanel ?: return
        val right = rightPanel ?: return
        val currentlyLeft = left.hasFocusInPanel()
        if (TurtleCommanderSettings.getInstance().state.panelLayout == PanelLayout.SINGLE.name) {
            swapSinglePanelCallback?.invoke()
        }
        if (currentlyLeft) right.focusActiveTab() else left.focusActiveTab()
    }
}
