package io.github.jhspetersson.turtlecommander.model

import java.nio.file.Path
import java.nio.file.attribute.FileTime

data class FileEntry(
    val name: String,
    val path: Path,
    val isDirectory: Boolean,
    val size: Long,
    val creationTime: FileTime? = null,
    val lastModified: FileTime?,
    val permissions: String,
    val isParentLink: Boolean = false,
    val directoryType: DirectoryType = DirectoryType.NONE,
)

enum class DirectoryType {
    NONE,
    GIT,
    IDEA_PROJECT,
    GRADLE,
    MAVEN,
    CARGO,
    NPM,
    PYTHON,
    CMAKE,
    DOTNET,
}
