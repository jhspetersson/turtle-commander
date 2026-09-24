package io.github.jhspetersson.turtlecommander.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService

class FavoritesEditorProjectScopeTest : BasePlatformTestCase() {

    private val stateService get() = project.service<FileManagerStateService>()

    override fun tearDown() {
        try {
            stateService.setFavoriteEntries(emptyList())
        } finally {
            super.tearDown()
        }
    }

    fun `test settings page is registered per project`() {
        val id = "io.github.jhspetersson.turtlecommander.settings"
        assertTrue(Configurable.PROJECT_CONFIGURABLE.getExtensions(project).any { it.id == id })
        assertFalse(Configurable.APPLICATION_CONFIGURABLE.extensionList.any { it.id == id })
    }

    fun `test editor loads favorites of the given project`() {
        stateService.setFavoriteEntries(listOf(FileManagerStateService.FavoriteEntry("/one", "#FF0000", "")))
        val editor = FavoritesEditor(project)
        assertFalse(editor.isModified())
    }

    fun `test apply writes favorites back to the given project`() {
        stateService.setFavoriteEntries(emptyList())
        val editor = FavoritesEditor(project)
        stateService.setFavoriteEntries(listOf(FileManagerStateService.FavoriteEntry("/two")))
        assertTrue(editor.isModified())
        editor.reset()
        assertFalse(editor.isModified())
        assertEquals(listOf("/two"), stateService.getFavoriteEntries().map { it.path })
        editor.apply()
        assertEquals(listOf("/two"), stateService.getFavoriteEntries().map { it.path })
    }

    fun `test configurable builds its favorites editor for the given project`() {
        stateService.setFavoriteEntries(listOf(FileManagerStateService.FavoriteEntry("/three")))
        val configurable = TurtleCommanderConfigurable(project)
        try {
            configurable.createComponent()
            configurable.reset()
            assertFalse(configurable.isModified())
            stateService.setFavoriteEntries(emptyList())
            assertTrue(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
        }
    }
}
