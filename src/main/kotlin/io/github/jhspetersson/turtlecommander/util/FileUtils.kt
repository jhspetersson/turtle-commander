package io.github.jhspetersson.turtlecommander.util

import io.github.jhspetersson.turtlecommander.settings.FileSizeFormat
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale

suspend fun countFiles(sources: List<Path>): Int = withContext(Dispatchers.IO) {
    var count = 0
    for (source in sources) {
        if (Files.isDirectory(source)) {
            Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    count++
                    return FileVisitResult.CONTINUE
                }
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    count++
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    return FileVisitResult.CONTINUE
                }
            })
        } else {
            count++
        }
    }
    count
}

/**
 * If [pattern] looks like plain text (contains none of `* ? [ .`), wrap it with `*…*` so a
 * substring match works through `glob:` `PathMatcher.matches`. Otherwise return it verbatim
 * so the user's intent — wildcards, character classes, or a literal filename with an
 * extension — is respected as an anchored full match.
 *
 * Used by both the FileTab quick filter and the search dialog so typing `foo?` or `foo.txt`
 * behaves the same in both places. The union of metacharacters (`* ? [`) plus `.` covers the
 * historical rules of both call sites:
 *
 *  - `* ? [` are glob metacharacters — when present the user typed a real glob and substring
 *    wrapping would either be redundant or actively wrong.
 *  - `.` is treated as a "this is an exact filename, not a substring" hint — `report.pdf`
 *    matches a file literally named that, not `oldreport.pdf.bak`.
 */
fun wrapAsSubstringGlobIfPlain(pattern: String): String {
    val hasGlobMeta = pattern.any { it == '*' || it == '?' || it == '[' || it == '.' }
    return if (hasGlobMeta) pattern else "*$pattern*"
}

fun fileErrorMessage(error: Exception): String {
    return when (error) {
        is AccessDeniedException -> "Access denied: ${error.file}"
        else -> error.message ?: error.javaClass.simpleName
    }
}

/**
 * Default-overload caller. Reads the user-configured [FileSizeFormat] from settings,
 * falling back to [FileSizeFormat.AUTO_BINARY] when the application service isn't
 * available (e.g. headless unit tests). The fallback matches the historical hardcoded
 * behavior so existing tests and external consumers see unchanged output by default.
 */
fun formatSize(bytes: Long): String {
    val format = runCatching { TurtleCommanderSettings.getInstance().getFileSizeFormat() }
        .getOrDefault(FileSizeFormat.AUTO_BINARY)
    return formatSize(bytes, format)
}

fun formatSize(bytes: Long, format: FileSizeFormat): String = when (format) {
    FileSizeFormat.BYTES -> "%,d".format(bytes)
    FileSizeFormat.AUTO_BINARY -> autoFormatSize(bytes, 1024.0, BINARY_UNITS)
    FileSizeFormat.AUTO_SI -> autoFormatSize(bytes, 1000.0, SI_UNITS)
}

private val BINARY_UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
private val SI_UNITS = arrayOf("B", "kB", "MB", "GB", "TB")

private fun autoFormatSize(bytes: Long, base: Double, units: Array<String>): String {
    if (bytes < base) return "$bytes ${units[0]}"
    var size = bytes / base
    var i = 1
    while (size >= base && i < units.size - 1) {
        size /= base
        i++
    }
    return String.format(Locale.ROOT, "%.1f %s", size, units[i])
}

fun readFileOwner(path: Path): String {
    return try {
        Files.getOwner(path).name ?: ""
    } catch (_: Exception) {
        ""
    }
}

fun readFileGroup(path: Path): String {
    return try {
        val posixView = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        posixView?.readAttributes()?.group()?.name ?: ""
    } catch (_: Exception) {
        ""
    }
}

fun readFilePermissions(path: Path, isWindows: Boolean): String {
    return try {
        if (isWindows) {
            val attrs = Files.readAttributes(path, DosFileAttributes::class.java)
            buildString {
                if (attrs.isReadOnly) append('R')
                if (attrs.isHidden) append('H')
                if (attrs.isSystem) append('S')
                if (attrs.isArchive) append('A')
            }
        } else {
            val perms = Files.getPosixFilePermissions(path)
            PosixFilePermissions.toString(perms)
        }
    } catch (_: Exception) {
        ""
    }
}
