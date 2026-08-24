package io.github.jhspetersson.turtlecommander.operation

import java.nio.file.Path

object CdCommand {

    fun parseArgument(command: String): String? {
        val match = Regex("""^\s*(?i:cd)(?:\s+(.*?))?\s*$""").find(command) ?: return null
        return unquote(match.groupValues[1].trim())
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
            val resolved = if (path.isAbsolute) path else base.resolve(normalizedSeparators)
            resolved.normalize()
        } catch (_: Exception) {
            null
        }
    }

    private fun unquote(s: String): String {
        if (s.length >= 2 &&
            ((s.first() == '"' && s.last() == '"') || (s.first() == '\'' && s.last() == '\''))
        ) {
            return s.substring(1, s.length - 1)
        }
        return s
    }
}
