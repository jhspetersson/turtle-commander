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
    val owner: String = "",
    val group: String = "",
    val permissions: String,
    val isParentLink: Boolean = false,
)
