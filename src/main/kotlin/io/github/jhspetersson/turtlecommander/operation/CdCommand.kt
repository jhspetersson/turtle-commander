package io.github.jhspetersson.turtlecommander.operation

import java.nio.file.Path

object CdCommand {

    fun parseArgument(command: String, isWindows: Boolean = false): String? {
        val match = CD_PATTERN.find(command) ?: return null
        val arg = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
        val withoutSwitch = if (isWindows) arg.replace(DRIVE_SWITCH, "") else arg
        return unquote(withoutSwitch.trim())
    }

    fun resolveTarget(arg: String, base: Path, home: Path, isWindows: Boolean): Path? {
        return try {
            val normalizedSeparators = if (isWindows) arg else arg.replace('\\', '/')
            when {
                normalizedSeparators.isEmpty() || normalizedSeparators == "~" -> return home.normalize()
                normalizedSeparators.startsWith("~/") || normalizedSeparators.startsWith("~\\") ->
                    return home.resolve(normalizedSeparators.substring(2)).normalize()
            }
            val path = Path.of(normalizedSeparators)
            val resolved = when {
                path.isAbsolute -> path
                isDriveRelative(path) -> resolveDriveRelative(path, base)
                else -> base.resolve(normalizedSeparators)
            }
            resolved.normalize()
        } catch (_: Exception) {
            null
        }
    }

    private fun isDriveRelative(path: Path): Boolean =
        !path.isAbsolute && path.root?.toString()?.endsWith(":") == true

    private fun resolveDriveRelative(path: Path, base: Path): Path {
        val drive = path.root.toString()
        val sameDrive = base.root?.toString()?.startsWith(drive, ignoreCase = true) == true
        var resolved = if (sameDrive) base else Path.of(drive.uppercase() + path.fileSystem.separator)
        for (name in path) resolved = resolved.resolve(name)
        return resolved
    }

    private val CD_PATTERN = Regex("""^\s*(?i:cd)(?:\s+(.*?)|(\.\..*?|[\\/].*?))?\s*$""")
    private val DRIVE_SWITCH = Regex("""^(?i)/d(?:\s+|$)""")

    private fun unquote(s: String): String {
        if (s.length >= 2 &&
            ((s.first() == '"' && s.last() == '"') || (s.first() == '\'' && s.last() == '\''))
        ) {
            return s.substring(1, s.length - 1)
        }
        return s
    }
}
