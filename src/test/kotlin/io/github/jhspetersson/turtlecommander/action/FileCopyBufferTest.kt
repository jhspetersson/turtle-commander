package io.github.jhspetersson.turtlecommander.action
import io.github.jhspetersson.turtlecommander.model.FileEntry

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class FileCopyBufferTest {

    private fun fileEntry(name: String) = FileEntry(
        name = name,
        path = Path.of("/test/$name"),
        isDirectory = false,
        size = 100,
        lastModified = FileTime.fromMillis(0),
        permissions = "rw-r--r--",
    )

    @Before
    fun setUp() {
        FileCopyBuffer.entries = emptyList()
        FileCopyBuffer.isCut = false
    }

    @Test
    fun testInitiallyEmpty() {
        assertTrue(FileCopyBuffer.entries.isEmpty())
        assertFalse(FileCopyBuffer.isCut)
    }

    @Test
    fun testSetEntries() {
        val entries = listOf(fileEntry("a.txt"), fileEntry("b.txt"))
        FileCopyBuffer.entries = entries
        assertEquals(2, FileCopyBuffer.entries.size)
        assertEquals("a.txt", FileCopyBuffer.entries[0].name)
    }

    @Test
    fun testChangeListenerFires() {
        var callCount = 0
        FileCopyBuffer.addChangeListener { callCount++ }
        FileCopyBuffer.entries = listOf(fileEntry("a.txt"))
        assertTrue("Listener should have been called", callCount > 0)
    }

    @Test
    fun testChangeListenerFiresOnClear() {
        var callCount = 0
        FileCopyBuffer.addChangeListener { callCount++ }
        FileCopyBuffer.entries = listOf(fileEntry("a.txt"))
        val countAfterSet = callCount
        FileCopyBuffer.entries = emptyList()
        assertTrue("Listener should fire on clear too", callCount > countAfterSet)
    }
}
