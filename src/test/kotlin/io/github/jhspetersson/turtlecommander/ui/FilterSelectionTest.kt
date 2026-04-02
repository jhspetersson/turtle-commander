package io.github.jhspetersson.turtlecommander.ui

import io.github.jhspetersson.turtlecommander.model.FileEntry
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

/**
 * Tests that filtering preserves the selected entry name when possible.
 */
class FilterSelectionTest {

    @Test
    fun `findPreservedSelectionIndex returns matching index when entry survives filter`() {
        val entries = listOf(
            fileEntry("alpha.txt"),
            fileEntry("beta.txt"),
            fileEntry("gamma.txt"),
        )
        // Previously selected "beta.txt" — it should remain selected after filter
        val index = findPreservedSelectionIndex(entries, "beta.txt")
        assertEquals(1, index)
    }

    @Test
    fun `findPreservedSelectionIndex returns 0 when entry is filtered out`() {
        val entries = listOf(
            fileEntry("alpha.txt"),
            fileEntry("gamma.txt"),
        )
        // "beta.txt" was selected but no longer visible
        val index = findPreservedSelectionIndex(entries, "beta.txt")
        assertEquals(0, index)
    }

    @Test
    fun `findPreservedSelectionIndex returns 0 when no previous selection`() {
        val entries = listOf(
            fileEntry("alpha.txt"),
        )
        val index = findPreservedSelectionIndex(entries, null)
        assertEquals(0, index)
    }

    @Test
    fun `findPreservedSelectionIndex returns 0 for empty list`() {
        val index = findPreservedSelectionIndex(emptyList(), "beta.txt")
        assertEquals(-1, index)
    }

    private fun fileEntry(name: String) = FileEntry(
        name = name,
        path = Path.of("/tmp/$name"),
        isDirectory = false,
        size = 100,
        lastModified = null,
        permissions = "",
    )
}
