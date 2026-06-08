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
    /** Filesystem inode number (Unix `unix:ino`); null on Windows or when not read. */
    val inode: Long? = null,
    val isParentLink: Boolean = false,
    /** True when the entry is a symbolic link (regardless of whether its target resolves). */
    val isSymbolicLink: Boolean = false,
    /** True for a symlink whose target cannot be resolved (dangling link). */
    val isBrokenSymlink: Boolean = false,
    /** The raw link target, for display, when [isSymbolicLink]; null otherwise or if unreadable. */
    val linkTarget: String? = null,
)
