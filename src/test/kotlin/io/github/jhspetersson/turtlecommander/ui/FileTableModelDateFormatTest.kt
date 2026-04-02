package io.github.jhspetersson.turtlecommander.ui

import io.github.jhspetersson.turtlecommander.model.FileEntry
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class FileTableModelDateFormatTest {

    @Test
    fun `getDisplayValue returns consistent date format`() {
        val model = FileTableModel()
        val time = FileTime.fromMillis(1700000000000L) // 2023-11-14 22:13:20 UTC
        model.setEntries(listOf(
            FileEntry(
                name = "test.txt",
                path = Path.of("/tmp/test.txt"),
                isDirectory = false,
                size = 42,
                lastModified = time,
                creationTime = time,
                permissions = "",
            )
        ))

        val dateModified = model.getDisplayValue(0, FileTableModel.COL_DATE)
        val dateCreated = model.getDisplayValue(0, FileTableModel.COL_CREATED)

        // Should be formatted as yyyy-MM-dd HH:mm
        assertTrue("Date modified should match date pattern, got: $dateModified",
            dateModified.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
        assertTrue("Date created should match date pattern, got: $dateCreated",
            dateCreated.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))

        // Multiple calls should produce the same result (no shared mutable state issue)
        assertEquals(dateModified, model.getDisplayValue(0, FileTableModel.COL_DATE))
        assertEquals(dateCreated, model.getDisplayValue(0, FileTableModel.COL_CREATED))
    }
}
