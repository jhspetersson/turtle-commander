package io.github.jhspetersson.turtlecommander.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService

class FavoriteInvalidPathTest : BasePlatformTestCase() {

    private val badPath = "/some/dir/bad\u0000name"
    private val stateService get() = project.service<FileManagerStateService>()

    override fun tearDown() {
        try {
            stateService.setFavoriteEntries(emptyList())
        } finally {
            super.tearDown()
        }
    }

    fun `test favorite button with an invalid path is created and named by its last segment`() {
        val action = FavoriteAction(badPath, 1, "", "", project)
        assertEquals("bad\u0000name", action.templatePresentation.text)
    }

    fun `test remove button with an invalid path is created`() {
        val action = RemoveFavoriteAction(badPath, project)
        assertEquals("Remove bad\u0000name from favorites", action.templatePresentation.text)
    }

    fun `test overflow menu with an invalid path does not throw`() {
        val action = FavoriteOverflowAction(listOf(FileManagerStateService.FavoriteEntry(badPath)), 5, project)
        action.actionPerformed(TestActionEvent.createTestEvent(action))
    }

    fun `test quick access update with an invalid favorite does not throw`() {
        stateService.setFavoriteEntries(listOf(FileManagerStateService.FavoriteEntry(badPath)))
        val action = ActionManager.getInstance().getAction("TurtleCommander.QuickAccess.1")
        val context = SimpleDataContext.getProjectContext(project)
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        assertEquals("Go to bad\u0000name", event.presentation.text)
    }

    fun `test favorite display name falls back to the last segment`() {
        assertEquals("bad\u0000name", favoriteDisplayName(badPath))
        assertEquals("c\u0000d", favoriteDisplayName("C:\\a\\c\u0000d"))
        assertEquals("dir", favoriteDisplayName("/tmp/dir"))
    }

    fun `test favorite path is null for an invalid path`() {
        assertNull(favoritePathOrNull(badPath))
        assertNotNull(favoritePathOrNull("/tmp/dir"))
    }
}
