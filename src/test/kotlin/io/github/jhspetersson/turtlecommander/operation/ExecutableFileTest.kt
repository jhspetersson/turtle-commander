package io.github.jhspetersson.turtlecommander.operation

import io.github.jhspetersson.turtlecommander.model.FileEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class ExecutableFileTest {

    private fun entry(
        name: String,
        isDirectory: Boolean = false,
        isParentLink: Boolean = false,
        isBrokenSymlink: Boolean = false,
        isNamedPipe: Boolean = false,
    ) = FileEntry(
        name = name,
        path = Path.of("/tmp").resolve(name),
        isDirectory = isDirectory,
        size = 0,
        lastModified = null,
        permissions = "",
        isParentLink = isParentLink,
        isBrokenSymlink = isBrokenSymlink,
        isNamedPipe = isNamedPipe,
    )

    @Test
    fun `windows recognizes executables by extension regardless of case`() {
        for (name in listOf("tool.exe", "TOOL.EXE", "run.bat", "run.cmd", "old.com")) {
            assertTrue(name, ExecutableFile.isExecutable(entry(name), isWindows = true) { false })
        }
    }

    @Test
    fun `windows ignores the execute bit for other extensions`() {
        for (name in listOf("notes.txt", "script.sh", "archive.exe.zip", "noext")) {
            assertFalse(name, ExecutableFile.isExecutable(entry(name), isWindows = true) { true })
        }
    }

    @Test
    fun `unix relies on the execute bit only`() {
        assertTrue(ExecutableFile.isExecutable(entry("tool"), isWindows = false) { true })
        assertTrue(ExecutableFile.isExecutable(entry("script.sh"), isWindows = false) { true })
        assertFalse(ExecutableFile.isExecutable(entry("tool.exe"), isWindows = false) { false })
        assertFalse(ExecutableFile.isExecutable(entry("script.sh"), isWindows = false) { false })
    }

    @Test
    fun `directories, parent links, broken symlinks and pipes are never executable`() {
        for (isWindows in listOf(true, false)) {
            assertFalse(ExecutableFile.isExecutable(entry("bin.exe", isDirectory = true), isWindows) { true })
            assertFalse(ExecutableFile.isExecutable(entry("..", isParentLink = true), isWindows) { true })
            assertFalse(ExecutableFile.isExecutable(entry("gone.exe", isBrokenSymlink = true), isWindows) { true })
            assertFalse(ExecutableFile.isExecutable(entry("fifo.exe", isNamedPipe = true), isWindows) { true })
        }
    }
}
