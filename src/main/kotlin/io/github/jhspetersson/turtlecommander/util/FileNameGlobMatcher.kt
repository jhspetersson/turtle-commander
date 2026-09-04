package io.github.jhspetersson.turtlecommander.util

import java.nio.file.FileSystems
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.PathMatcher

class FileNameGlobMatcher(val glob: String) {
    private val pathMatcher: PathMatcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
    private val regex: Regex by lazy { globToRegex(glob) }

    fun matches(name: String): Boolean = try {
        pathMatcher.matches(Path.of(name))
    } catch (_: InvalidPathException) {
        regex.matches(name)
    }
}

private val defaultFileSystemIgnoresCase: Boolean by lazy {
    FileSystems.getDefault().getPathMatcher("glob:A").matches(Path.of("a"))
}

internal fun globToRegex(glob: String): Regex {
    val sb = StringBuilder()
    var inGroup = false
    var i = 0
    fun peek(): Char? = if (i < glob.length) glob[i] else null
    while (i < glob.length) {
        when (val c = glob[i++]) {
            '\\' -> {
                val next = peek()
                if (next != null) {
                    i++
                    appendLiteral(sb, next)
                } else {
                    appendLiteral(sb, '\\')
                }
            }
            '*' -> if (peek() == '*') {
                i++
                sb.append(".*")
            } else {
                sb.append("[^/]*")
            }
            '?' -> sb.append("[^/]")
            '[' -> {
                sb.append("[[^/]&&[")
                if (peek() == '^') {
                    i++
                    sb.append("\\^")
                } else {
                    if (peek() == '!') {
                        i++
                        sb.append('^')
                    }
                    if (peek() == '-') {
                        i++
                        sb.append('-')
                    }
                }
                while (i < glob.length) {
                    val ch = glob[i++]
                    if (ch == ']') break
                    if (ch == '\\' || ch == '[' || (ch == '&' && peek() == '&')) sb.append('\\')
                    sb.append(ch)
                }
                sb.append("]]")
            }
            '{' -> {
                sb.append("(?:(?:")
                inGroup = true
            }
            '}' -> if (inGroup) {
                sb.append("))")
                inGroup = false
            } else {
                appendLiteral(sb, '}')
            }
            ',' -> if (inGroup) sb.append(")|(?:") else sb.append(',')
            else -> appendLiteral(sb, c)
        }
    }
    val options = if (defaultFileSystemIgnoresCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    return Regex(sb.toString(), options)
}

private fun appendLiteral(sb: StringBuilder, c: Char) {
    if (c in "\\^$.|?*+()[]{}") sb.append('\\')
    sb.append(c)
}
