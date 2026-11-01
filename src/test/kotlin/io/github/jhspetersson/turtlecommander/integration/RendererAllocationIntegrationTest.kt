package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.settings.ResolvedStyle
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.FileThumbnailCellRenderer
import io.github.jhspetersson.turtlecommander.ui.fileEntryIcon
import io.github.jhspetersson.turtlecommander.ui.fontJBColor
import io.github.jhspetersson.turtlecommander.ui.inactiveSelectionBackground
import io.github.jhspetersson.turtlecommander.ui.strikeFont
import java.awt.Color
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.swing.JLabel
import javax.swing.JPanel

class RendererAllocationIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var tab: FileTab
    private var savedSelectedColor = ""
    private val parentDisposable = Disposer.newDisposable("turtle-test-renderer-allocation")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        savedSelectedColor = TurtleCommanderSettings.getInstance().state.styles.panelStyle.selectedColor
        tempDir = Files.createTempDirectory("turtle-test-renderer-allocation-")
        panel = FileManagerPanel(project = project, initialPath = tempDir, otherPanelPathProvider = { tempDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), project.service())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        tab = panel.getActiveTab()!!
    }

    override fun tearDown() {
        try {
            TurtleCommanderSettings.getInstance().state.styles.panelStyle.selectedColor = savedSelectedColor
            Disposer.dispose(parentDisposable)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun entry(name: String, isDirectory: Boolean = false, isSymbolicLink: Boolean = false): FileEntry {
        val stamp = FileTime.fromMillis(0)
        return FileEntry(
            name, tempDir.resolve(name), isDirectory, 1, stamp, stamp,
            permissions = "", isSymbolicLink = isSymbolicLink,
        )
    }

    fun testInactiveSelectionBackgroundIsReusedUntilItsInputsChange() {
        TurtleCommanderSettings.getInstance().state.styles.panelStyle.selectedColor = ""
        val first = tab.inactiveSelectionBackground()
        assertSame(first, tab.inactiveSelectionBackground())

        TurtleCommanderSettings.getInstance().state.styles.panelStyle.selectedColor = "#123456"
        val custom = tab.inactiveSelectionBackground()
        assertEquals(Color(0x123456), custom)
        assertSame(custom, tab.inactiveSelectionBackground())

        TurtleCommanderSettings.getInstance().state.styles.panelStyle.selectedColor = ""
        tab.table.background = Color(0x000000)
        tab.table.selectionBackground = Color(0x0000FE)
        assertEquals(Color(0, 0, 0x7F), tab.inactiveSelectionBackground())
    }

    fun testComposedEntryIconsAreReused() {
        val dotted = ResolvedStyle(iconDotColor = "#FF0000")
        val link = entry("link.txt", isSymbolicLink = true)
        assertSame(fileEntryIcon(link, true, dotted), fileEntryIcon(link, true, dotted))

        val dir = entry("dir", isDirectory = true)
        assertSame(fileEntryIcon(dir, true, dotted), fileEntryIcon(dir, true, dotted))
        assertNotSame(fileEntryIcon(dir, true, dotted), fileEntryIcon(dir, true, ResolvedStyle(iconDotColor = "#00FF00")))
    }

    fun testParsedRuleColorsAreReused() {
        val style = ResolvedStyle(fontColor = "#ABCDEF")
        assertEquals(Color(0xABCDEF), style.fontJBColor())
        assertSame(style.fontJBColor(), style.fontJBColor())
        assertNull(ResolvedStyle(fontColor = "nope").fontJBColor())
    }

    fun testStrikeFontIsReused() {
        val base = Font("Dialog", Font.PLAIN, 12)
        assertSame(strikeFont(base, true), strikeFont(base, true))
        assertSame(base, strikeFont(base, false))
    }

    fun testThumbnailRendererReusesTheScaledIcon() {
        val renderer = FileThumbnailCellRenderer(tab)
        val target = entry("notes.txt")
        fun renderedIcon() = ((renderer.getListCellRendererComponent(tab.thumbnailList, target, 0, false, false) as JPanel)
            .getComponent(0) as JLabel).icon
        assertSame(renderedIcon(), renderedIcon())
    }
}
