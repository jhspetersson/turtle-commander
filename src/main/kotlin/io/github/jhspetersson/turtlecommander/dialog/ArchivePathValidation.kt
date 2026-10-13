package io.github.jhspetersson.turtlecommander.dialog

import io.github.jhspetersson.turtlecommander.ui.resolveDestinationPath
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal fun validatePackArchivePath(input: String, base: Path): String? {
    val text = input.trim()
    if (text.isEmpty()) return "Enter an archive path"
    val path = try {
        resolveDestinationPath(text, base)
    } catch (e: InvalidPathException) {
        return "Invalid archive path: ${e.reason ?: text}"
    }
    if (Files.isDirectory(path)) return "Archive path points to an existing directory"
    return null
}

internal fun validateExtractDestination(input: String, base: Path): String? {
    val text = input.trim()
    if (text.isEmpty()) return "Enter a destination directory"
    val path = try {
        resolveDestinationPath(text, base)
    } catch (e: InvalidPathException) {
        return "Invalid destination path: ${e.reason ?: text}"
    }
    if (Files.exists(path) && !Files.isDirectory(path)) return "Destination is an existing file"
    return null
}
