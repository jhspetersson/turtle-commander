package io.github.jhspetersson.turtlecommander.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.service.FileManagerStateService
import java.nio.file.Files
import java.nio.file.Path

class FavoritesEditorPathFormatTest : BasePlatformTestCase() {

    private lateinit var dir: Path
    private val stateService get() = project.service<FileManagerStateService>()

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("turtle-test-fav-format-")
        stateService.setFavoriteEntries(emptyList())
    }

    override fun tearDown() {
        try {
            stateService.setFavoriteEntries(emptyList())
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun chosen() = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!

    fun `test chosen directory is stored in the same format as panel favorites`() {
        val editor = FavoritesEditor(project)
        editor.addChosenDirectory(chosen())
        editor.apply()

        assertEquals(listOf(dir.toString()), stateService.getFavorites())
    }

    fun `test choosing a directory already added from a panel does not duplicate it`() {
        stateService.addFavorite(dir.toString())
        val editor = FavoritesEditor(project)
        editor.addChosenDirectory(chosen())

        assertFalse(editor.isModified())
    }
}
