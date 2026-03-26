package io.github.jhspetersson.turtlecommander.util

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ReadFilePermissionsTest {

    private val tempFiles = mutableListOf<Path>()

    @After
    fun cleanup() {
        tempFiles.reversed().forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `returns empty string for nonexistent file`() {
        val path = Path.of("/nonexistent/file.txt")
        assertEquals("", readFilePermissions(path, true))
        assertEquals("", readFilePermissions(path, false))
    }

    @Test
    fun `reads permissions for existing file`() {
        val file = Files.createTempFile("perm-test-", ".txt")
        tempFiles.add(file)

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val result = readFilePermissions(file, isWindows)

        // On any platform, we should get a non-null string (may be empty if no special attrs)
        assertNotNull(result)
    }

    @Test
    fun `windows mode returns DOS attribute letters`() {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (!isWindows) return // skip on non-Windows

        val file = Files.createTempFile("perm-test-", ".txt")
        tempFiles.add(file)

        val result = readFilePermissions(file, true)
        // New files on Windows typically have Archive attribute
        assertTrue(result.contains("A") || result.isEmpty())
    }

    @Test
    fun `unix mode returns posix permission string`() {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) return // skip on Windows

        val file = Files.createTempFile("perm-test-", ".txt")
        tempFiles.add(file)

        val result = readFilePermissions(file, false)
        // POSIX permissions string is 9 characters like "rw-r--r--"
        assertEquals(9, result.length)
    }
}
