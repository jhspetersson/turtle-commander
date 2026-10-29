package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.ComponentStyle
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.ReservedTabAreaTabbedPane
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JTabbedPane

class DefaultFontRestoreIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var tab: FileTab
    private val parentDisposable = Disposer.newDisposable("turtle-test-default-font-restore")
    private val originalPanelStyle = ComponentStyle()
    private val originalTabStyle = ComponentStyle()

    override fun setUp() {
        super.setUp()
        val styles = TurtleCommanderSettings.getInstance().state.styles
        originalPanelStyle.copyFrom(styles.panelStyle)
        originalTabStyle.copyFrom(styles.tabStyle)
        styles.panelStyle = ComponentStyle().apply { fontFamily = Font.MONOSPACED; fontSize = 23 }
        styles.tabStyle = ComponentStyle().apply { fontFamily = Font.MONOSPACED; fontSize = 21 }
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-default-font-restore-")
        Files.writeString(tempDir.resolve("a.txt"), "a")
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab = panel.getActiveTab()!!
    }

    override fun tearDown() {
        try {
            val styles = TurtleCommanderSettings.getInstance().state.styles
            styles.panelStyle = ComponentStyle().apply { copyFrom(originalPanelStyle) }
            styles.tabStyle = ComponentStyle().apply { copyFrom(originalTabStyle) }
            Disposer.dispose(parentDisposable)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun resetStylesAndReapply() {
        val styles = TurtleCommanderSettings.getInstance().state.styles
        styles.panelStyle = ComponentStyle()
        styles.tabStyle = ComponentStyle()
        panel.applyFonts()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun assertSameFont(message: String, expected: Font, actual: Font) {
        assertEquals("$message: family", expected.family, actual.family)
        assertEquals("$message: size", expected.size, actual.size)
        assertEquals("$message: style", expected.style, actual.style)
    }

    fun testPanelViewsReturnToPlatformDefaultFontAfterCustomFontIsCleared() {
        val platformDefault = JBTable().font
        assertEquals(23, tab.table.font.size)

        resetStylesAndReapply()

        assertSameFont("table", platformDefault, tab.table.font)
        assertSameFont("list", platformDefault, tab.list.font)
        assertSameFont("thumbnails", platformDefault, tab.thumbnailList.font)
        assertSameFont("tree", platformDefault, tab.tree.font)
    }

    fun testTabbedPaneReturnsToPlatformDefaultFontAfterCustomFontIsCleared() {
        val platformDefault = ReservedTabAreaTabbedPane().font
        val tabbedPane = UIUtil.findComponentOfType(panel, JTabbedPane::class.java)!!
        assertEquals(21, tabbedPane.font.size)

        resetStylesAndReapply()

        assertSameFont("tabbed pane", platformDefault, tabbedPane.font)
    }
}
