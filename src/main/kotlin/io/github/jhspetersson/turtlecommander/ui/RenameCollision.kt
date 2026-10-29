package io.github.jhspetersson.turtlecommander.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal enum class RenameCollisionChoice { OVERWRITE, RENAME_TARGET, KEEP_EDITING, CANCEL }

internal class RenameCollision(
    val source: Path,
    val target: Path,
    val newName: String,
    val targetIsDirectory: Boolean,
    val canOverwrite: Boolean,
)

internal fun detectRenameCollision(source: Path, target: Path, newName: String): RenameCollision? {
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
    if (runCatching { Files.isSameFile(source, target) }.getOrDefault(false)) return null
    val isDirectory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
    val nonEmpty = isDirectory && Files.list(target).use { it.findAny().isPresent }
    return RenameCollision(source, target, newName, targetIsDirectory = isDirectory, canOverwrite = !nonEmpty)
}

internal fun showRenameCollisionDialog(project: Project, collision: RenameCollision): RenameCollisionChoice {
    val choices = buildList {
        if (collision.canOverwrite) add(RenameCollisionChoice.OVERWRITE to "Overwrite")
        add(RenameCollisionChoice.RENAME_TARGET to "Rename Existing...")
        add(RenameCollisionChoice.KEEP_EDITING to "Keep Editing")
        add(RenameCollisionChoice.CANCEL to "Cancel")
    }
    val kind = if (collision.targetIsDirectory) "A directory" else "A file"
    val message = buildString {
        append(kind).append(" named \"").append(collision.newName).append("\" already exists.")
        if (!collision.canOverwrite) append("\nIt is not empty, so it cannot be overwritten.")
        append("\n\nWhat would you like to do?")
    }
    val index = Messages.showDialog(
        project,
        message,
        "Rename",
        choices.map { it.second }.toTypedArray(),
        0,
        Messages.getQuestionIcon(),
    )
    return choices.getOrNull(index)?.first ?: RenameCollisionChoice.CANCEL
}

internal fun showRenameExistingDialog(project: Project, target: Path): String? {
    val current = target.fileName.toString()
    return Messages.showInputDialog(
        project,
        "New name for the existing \"$current\":",
        "Rename Existing",
        Messages.getQuestionIcon(),
        current,
        null,
    )?.trim()?.takeIf { it.isNotEmpty() && it != current }
}
