package io.github.jhspetersson.turtlecommander.dialog

import io.github.jhspetersson.turtlecommander.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

/**
 * Unit tests for the confirmation text shown in [DeleteDialog]. The wording changes depending
 * on whether deletion is permanent or reversible via the Recycle Bin — see `buildPrompt`.
 */
class DeleteDialogPromptTest {

    private fun entry(name: String): FileEntry =
        FileEntry(
            name = name,
            path = Path.of("/tmp/$name"),
            isDirectory = false,
            size = 0L,
            lastModified = null,
            permissions = "",
        )

    @Test
    fun `single file permanent prompt`() {
        val prompt = DeleteDialog.buildPrompt(listOf(entry("foo.txt")), useRecycleBin = false)
        assertEquals("Delete \"foo.txt\"?", prompt)
    }

    @Test
    fun `single file recycle bin prompt`() {
        val prompt = DeleteDialog.buildPrompt(listOf(entry("foo.txt")), useRecycleBin = true)
        assertEquals("Move \"foo.txt\" to Recycle Bin?", prompt)
    }

    @Test
    fun `multiple files permanent prompt`() {
        val prompt = DeleteDialog.buildPrompt(
            listOf(entry("a"), entry("b"), entry("c")),
            useRecycleBin = false,
        )
        assertEquals("Delete 3 items?", prompt)
    }

    @Test
    fun `multiple files recycle bin prompt`() {
        val prompt = DeleteDialog.buildPrompt(
            listOf(entry("a"), entry("b"), entry("c")),
            useRecycleBin = true,
        )
        assertEquals("Move 3 items to Recycle Bin?", prompt)
    }
}
