package io.github.jhspetersson.turtlecommander.operation

import io.github.jhspetersson.turtlecommander.model.FileEntry
import java.nio.file.Files
import java.nio.file.Path

object ExecutableFile {

    private val windowsExtensions = setOf("exe", "com", "bat", "cmd")

    fun isExecutable(
        entry: FileEntry,
        isWindows: Boolean,
        hasExecuteBit: (Path) -> Boolean = { Files.isExecutable(it) },
    ): Boolean {
        if (entry.isDirectory || entry.isParentLink || entry.isBrokenSymlink || entry.isNamedPipe) return false
        return if (isWindows) {
            entry.name.substringAfterLast('.', "").lowercase() in windowsExtensions
        } else {
            hasExecuteBit(entry.path)
        }
    }
}
