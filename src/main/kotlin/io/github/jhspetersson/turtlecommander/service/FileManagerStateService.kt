package io.github.jhspetersson.turtlecommander.service
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab

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

    fun registerPanels(left: FileManagerPanel, right: FileManagerPanel, split: OnePixelSplitter) {
        leftPanel = left
        rightPanel = right
        splitter = split
    }

    override fun getState(): FileManagerState {
        leftPanel?.let { myState.leftPanel = it.saveState() }
        rightPanel?.let { myState.rightPanel = it.saveState() }
        splitter?.let { myState.splitProportion = it.proportion }
        return myState
    }

    override fun loadState(state: FileManagerState) {
        myState = state
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

        /** Required by XML deserializer */
        @Suppress("unused")
        constructor()

        constructor(path: String, color: String = "") {
            this.path = path
            this.color = color
        }
    }

    @Tag("panel")
    class PanelState {
        @XCollection(elementTypes = [String::class])
        var tabPaths: MutableList<String> = mutableListOf()
        @XCollection(elementTypes = [String::class])
        var tabViewModes: MutableList<String> = mutableListOf()
        var selectedTabIndex: Int = 0
    }

    @Tag("path-columns")
    class PathColumnEntry {
        var path: String = ""
        var columnWidths: String = ""   // comma-separated, e.g. "200,50,80,130,80"
        var columnOrder: String = ""    // comma-separated, e.g. "0,1,2,3,4"
    }

    // Helper methods to work with path column entries as a map-like structure

    fun getColumnState(path: String): PathColumnEntry? {
        return myState.pathColumns.find { it.path == path }
    }

    fun putColumnState(path: String, widths: List<Int>, order: List<Int>) {
        val existing = myState.pathColumns.find { it.path == path }
        if (existing != null) {
            existing.columnWidths = widths.joinToString(",")
            existing.columnOrder = order.joinToString(",")
        } else {
            myState.pathColumns.add(PathColumnEntry().apply {
                this.path = path
                this.columnWidths = widths.joinToString(",")
                this.columnOrder = order.joinToString(",")
            })
        }
    }

    fun parseWidths(entry: PathColumnEntry): List<Int> {
        return entry.columnWidths.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    fun parseOrder(entry: PathColumnEntry): List<Int> {
        return entry.columnOrder.split(",").mapNotNull { it.trim().toIntOrNull() }
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
        val left = leftPanel ?: return
        val right = rightPanel ?: return
        if (left.hasFocusInPanel()) {
            right.focusActiveTab()
        } else {
            left.focusActiveTab()
        }
    }
}
