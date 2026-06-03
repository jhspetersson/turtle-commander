package io.github.jhspetersson.turtlecommander.util

import java.nio.file.Files
import java.nio.file.Path

/**
 * Launches the host OS's native file manager. Centralizes the per-OS command
 * lines and the action label so callers don't each re-derive them.
 *
 * Functions throw [java.io.IOException] (from [ProcessBuilder.start]) when the
 * launcher can't be started; callers decide whether to notify or ignore.
 */
object SystemFileManager {

    /** Menu/button label appropriate for the host file manager. */
    val revealActionText: String
        get() = when {
            Platform.isWindows -> "Open in Explorer"
            Platform.isMac -> "Reveal in Finder"
            else -> "Open in File Manager"
        }

    /** Opens [dir] in the native file manager. */
    fun openDirectory(dir: Path) {
        val command = when {
            Platform.isWindows -> listOf("explorer.exe", dir.toString())
            Platform.isMac -> listOf("open", dir.toString())
            else -> listOf("xdg-open", dir.toString())
        }
        ProcessBuilder(command).start()
    }

    /**
     * Opens the native file manager with [path] selected/highlighted where the
     * platform supports it. On Linux (no universal "select" verb) the containing
     * directory is opened instead.
     */
    fun reveal(path: Path) {
        val command = when {
            Platform.isWindows -> listOf("explorer.exe", "/select,", path.toString())
            Platform.isMac -> listOf("open", "-R", path.toString())
            else -> {
                val dir = if (Files.isDirectory(path)) path else path.parent ?: path
                listOf("xdg-open", dir.toString())
            }
        }
        ProcessBuilder(command).start()
    }
}
