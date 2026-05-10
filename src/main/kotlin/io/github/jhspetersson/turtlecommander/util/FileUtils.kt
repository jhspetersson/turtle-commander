package io.github.jhspetersson.turtlecommander.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

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

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    if (gb < 1024) return "%.1f GB".format(gb)
    val tb = gb / 1024.0
    return "%.1f TB".format(tb)
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
