package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService.FavoriteEntry
import io.github.jhspetersson.turtlecommander.settings.RuleIcons
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.ui.FileManagerPanel
import io.github.jhspetersson.turtlecommander.ui.FileTab
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class TabFavoriteIconIntegrationTest : BasePlatformTestCase() {

    private lateinit var tempDir: Path
    private lateinit var favDir: Path
    private lateinit var plainDir: Path
    private lateinit var panel: FileManagerPanel
    private lateinit var stateService: FileManagerStateService
    private val parentDisposable = Disposer.newDisposable("turtle-test-tab-favorite-icon")

    override fun setUp() {
        super.setUp()
        TurtleCommanderSettings.getInstance().state.defaultViewMode = "TABLE"
        tempDir = Files.createTempDirectory("turtle-test-tab-icon-")
        favDir = Files.createDirectories(tempDir.resolve("fav"))
        plainDir = Files.createDirectories(tempDir.resolve("plain"))
        stateService = project.service()
        stateService.setFavoriteEntries(emptyList())
        panel = FileManagerPanel(project = project, initialPath = plainDir, otherPanelPathProvider = { plainDir })
        panel.parentDisposable = parentDisposable
        panel.restoreState(FileManagerStateService.PanelState(), stateService)
        panel.addDirectoryChangeListener { directoryChanges++ }
        awaitNavigation(0, panel.getActiveTab()!!, plainDir)
    }

    override fun tearDown() {
        try {
            stateService.setFavoriteEntries(emptyList())
            Disposer.dispose(parentDisposable)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            tempDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private var directoryChanges = 0

    private fun awaitNavigation(changesBefore: Int, tab: FileTab, expected: Path) {
        val deadline = System.currentTimeMillis() + 10_000
        while (directoryChanges <= changesBefore || tab.currentPath.normalize() != expected.normalize()) {
            if (System.currentTimeMillis() > deadline) fail("Tab never finished navigating to $expected, at ${tab.currentPath}")
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun openTabAt(path: Path): FileTab {
        val before = directoryChanges
        panel.openDirectoryInNewTab(path)
        val tab = panel.getActiveTab()!!
        awaitNavigation(before, tab, path)
        return tab
    }

    private fun navigate(tab: FileTab, path: Path) {
        val before = directoryChanges
        tab.fileOps.launch { tab.navigateTo(path, requestFocus = false) }
        awaitNavigation(before, tab, path)
    }

    fun testTabInAFavoriteDirectoryShowsTheFavoriteIcon() {
        stateService.setFavoriteEntries(listOf(FavoriteEntry(favDir.toString(), "", "star")))

        val tab = openTabAt(favDir)

        assertSame(RuleIcons.resolve("star"), panel.tabIcon(tab))
    }

    fun testTabOutsideFavoritesHasNoIcon() {
        stateService.setFavoriteEntries(listOf(FavoriteEntry(favDir.toString(), "", "star")))

        assertNull(panel.tabIcon(panel.getActiveTab()!!))
    }

    fun testFavoriteWithOnlyAColorStillGivesTheTabAnIcon() {
        stateService.setFavoriteEntries(listOf(FavoriteEntry(favDir.toString(), "#5B9BD5", "")))

        val tab = openTabAt(favDir)

        assertNotNull(panel.tabIcon(tab))
    }

    fun testAddingAFavoriteUpdatesAnAlreadyOpenTab() {
        val tab = openTabAt(favDir)
        assertNull(panel.tabIcon(tab))

        stateService.setFavoriteEntries(listOf(FavoriteEntry(favDir.toString(), "", "star")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertSame(RuleIcons.resolve("star"), panel.tabIcon(tab))
    }

    fun testRemovingTheFavoriteClearsTheIcon() {
        stateService.setFavoriteEntries(listOf(FavoriteEntry(favDir.toString(), "", "star")))
        val tab = openTabAt(favDir)

        stateService.removeFavorite(favDir.toString())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertNull(panel.tabIcon(tab))
    }

    fun testNavigatingInAndOutOfAFavoriteUpdatesTheIcon() {
        stateService.setFavoriteEntries(listOf(FavoriteEntry(favDir.toString(), "", "star")))
        val tab = panel.getActiveTab()!!

        navigate(tab, favDir)
        assertSame(RuleIcons.resolve("star"), panel.tabIcon(tab))

        navigate(tab, plainDir)
        assertNull(panel.tabIcon(tab))
    }

    fun testFavoritePathSpelledDifferentlyStillMatches() {
        val spelled = plainDir.toString() + File.separator + ".." + File.separator + "fav" + File.separator
        stateService.setFavoriteEntries(listOf(FavoriteEntry(spelled, "", "star")))

        val tab = openTabAt(favDir)

        assertSame(RuleIcons.resolve("star"), panel.tabIcon(tab))
    }

    fun testInvalidFavoritePathIsIgnored() {
        stateService.state.favoriteEntries.addAll(
            listOf(
                FavoriteEntry("bad\u0000path", "", "check"),
                FavoriteEntry(favDir.toString(), "", "star"),
            ),
        )

        val tab = openTabAt(favDir)

        assertSame(RuleIcons.resolve("star"), panel.tabIcon(tab))
    }
}
