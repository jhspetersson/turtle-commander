package io.github.jhspetersson.turtlecommander.dialog

import io.github.jhspetersson.turtlecommander.operation.SplitFileOperation
import io.github.jhspetersson.turtlecommander.service.targetIsInsideSource
import io.github.jhspetersson.turtlecommander.ui.resolveDestinationPath
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal fun validatePackArchivePath(input: String, base: Path, sources: List<Path> = emptyList()): String? {
    val text = input.trim()
    if (text.isEmpty()) return "Enter an archive path"
    val path = try {
        resolveDestinationPath(text, base)
    } catch (e: InvalidPathException) {
        return "Invalid archive path: ${e.reason ?: text}"
    }
    if (Files.isDirectory(path)) return "Archive path points to an existing directory"
    if (sources.any { it.fileSystem == path.fileSystem && targetIsInsideSource(it, path) }) {
        return "Archive cannot be created inside an item being packed"
    }
    return null
}

internal fun validateTargetDirectory(input: String, base: Path): String? {
    val text = input.trim()
    if (text.isEmpty()) return "Target directory cannot be empty"
    val path = try {
        resolveDestinationPath(text, base)
    } catch (e: InvalidPathException) {
        return "Invalid target directory: ${e.reason ?: text}"
    }
    if (Files.exists(path) && !Files.isDirectory(path)) return "Target directory is an existing file"
    return null
}

internal fun validateTargetFileName(input: String): String? {
    val name = input.trim()
    if (name.isEmpty()) return "Target file name cannot be empty"
    if (name == "." || name == ".." || name.any { it == '/' || it == '\\' }) {
        return "Target file name cannot contain a path"
    }
    try {
        Path.of(name)
    } catch (e: InvalidPathException) {
        return "Invalid target file name: ${e.reason ?: name}"
    }
    return null
}

internal fun validateChunkSize(chunkSize: Long, fileSize: Long): String? {
    if (chunkSize <= 0) return "Invalid chunk size"
    val parts = SplitFileOperation.chunkCount(fileSize, chunkSize)
    if (parts > SplitFileOperation.MAX_CHUNKS) {
        return "Chunk size is too small: $parts parts, the limit is ${SplitFileOperation.MAX_CHUNKS}"
    }
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
