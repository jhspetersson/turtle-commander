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

/**
 * Always-human-readable size for drive/free-space displays, where a raw byte count
 * (hundreds of gigabytes as a 12-digit number) is unreadable even when the user
 * prefers [FileSizeFormat.BYTES] for per-file sizes. Keeps the user's SI vs binary
 * unit choice when they picked an auto mode; the BYTES setting falls back to binary.
 */
fun formatSizeAuto(bytes: Long): String {
    val format = runCatching { TurtleCommanderSettings.getInstance().getFileSizeFormat() }
        .getOrDefault(FileSizeFormat.AUTO_BINARY)
    return when (format) {
        FileSizeFormat.AUTO_SI -> formatSize(bytes, FileSizeFormat.AUTO_SI)
        else -> formatSize(bytes, FileSizeFormat.AUTO_BINARY)
    }
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

/**
 * Reads the Unix inode number (`unix:ino`). Returns null on filesystems that don't
 * expose it (e.g. Windows, where the attribute view is unsupported) or on any I/O error.
 */
fun readFileInode(path: Path): Long? {
    return try {
        Files.getAttribute(path, "unix:ino") as? Long
    } catch (_: Exception) {
        null
    }
}

/**
 * Reads the hard-link count (`unix:nlink`). Returns null on filesystems that don't
 * expose it (e.g. Windows, where the attribute view is unsupported) or on any I/O error.
 */
fun readFileNlink(path: Path): Int? {
    return try {
        Files.getAttribute(path, "unix:nlink") as? Int
    } catch (_: Exception) {
        null
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
            // Prefer the raw mode so setuid/setgid/sticky bits show up in ls style
            // (e.g. `rwsr-xr-t`). The `unix` attribute view is available on Linux and macOS;
            // if it isn't, fall back to the standard 9-bit permission set, which renders the
            // same rwx string minus the special bits.
            val mode = try {
                Files.getAttribute(path, "unix:mode") as? Int
            } catch (_: Exception) {
                null
            }
            if (mode != null) {
                formatPosixMode(mode)
            } else {
                PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
            }
        }
    } catch (_: Exception) {
        ""
    }
}

private const val S_IFMT = 0xF000  // 0170000 type mask
private const val S_IFIFO = 0x1000 // 0010000 named pipe (FIFO)

fun isNamedPipe(path: Path): Boolean {
    return try {
        val mode = Files.getAttribute(path, "unix:mode") as? Int ?: return false
        isNamedPipeMode(mode)
    } catch (_: Exception) {
        false
    }
}

internal fun isNamedPipeMode(mode: Int): Boolean = (mode and S_IFMT) == S_IFIFO

// POSIX mode bits (octal in comments) used to render the permissions string.
private const val S_ISUID = 0x800 // 04000 setuid
private const val S_ISGID = 0x400 // 02000 setgid
private const val S_ISVTX = 0x200 // 01000 sticky
private const val S_IRUSR = 0x100 // 0400
private const val S_IWUSR = 0x080 // 0200
private const val S_IXUSR = 0x040 // 0100
private const val S_IRGRP = 0x020 // 0040
private const val S_IWGRP = 0x010 // 0020
private const val S_IXGRP = 0x008 // 0010
private const val S_IROTH = 0x004 // 0004
private const val S_IWOTH = 0x002 // 0002
private const val S_IXOTH = 0x001 // 0001

/**
 * Renders a Unix file mode as the 9-character `ls`-style permission string, including the
 * setuid/setgid/sticky substitutions in the execute positions: `s`/`S` for setuid (owner)
 * and setgid (group), `t`/`T` for the sticky bit (others). Uppercase means the special bit
 * is set but the underlying execute bit is not. Only the low permission bits are read; the
 * file-type bits in the high nibble are ignored.
 */
internal fun formatPosixMode(mode: Int): String = buildString(9) {
    append(if (mode and S_IRUSR != 0) 'r' else '-')
    append(if (mode and S_IWUSR != 0) 'w' else '-')
    append(specialExecChar(mode and S_IXUSR != 0, mode and S_ISUID != 0, 's', 'S'))
    append(if (mode and S_IRGRP != 0) 'r' else '-')
    append(if (mode and S_IWGRP != 0) 'w' else '-')
    append(specialExecChar(mode and S_IXGRP != 0, mode and S_ISGID != 0, 's', 'S'))
    append(if (mode and S_IROTH != 0) 'r' else '-')
    append(if (mode and S_IWOTH != 0) 'w' else '-')
    append(specialExecChar(mode and S_IXOTH != 0, mode and S_ISVTX != 0, 't', 'T'))
}

private fun specialExecChar(executeSet: Boolean, specialSet: Boolean, onChar: Char, offChar: Char): Char = when {
    specialSet -> if (executeSet) onChar else offChar
    executeSet -> 'x'
    else -> '-'
}

/**
 * Maps a raw Unix file mode to the OS-agnostic [PermissionFlag] set used by the search
 * permissions filter, including the setuid/setgid/sticky special bits. The fallback path in
 * [readFilePermissionFlags] uses [java.nio.file.Files.getPosixFilePermissions], which cannot
 * represent the special bits; this raw-mode path is preferred so they can be filtered on.
 */
internal fun posixModeToFlags(mode: Int): Set<PermissionFlag> = buildSet {
    if (mode and S_IRUSR != 0) add(PermissionFlag.OWNER_READ)
    if (mode and S_IWUSR != 0) add(PermissionFlag.OWNER_WRITE)
    if (mode and S_IXUSR != 0) add(PermissionFlag.OWNER_EXECUTE)
    if (mode and S_IRGRP != 0) add(PermissionFlag.GROUP_READ)
    if (mode and S_IWGRP != 0) add(PermissionFlag.GROUP_WRITE)
    if (mode and S_IXGRP != 0) add(PermissionFlag.GROUP_EXECUTE)
    if (mode and S_IROTH != 0) add(PermissionFlag.OTHERS_READ)
    if (mode and S_IWOTH != 0) add(PermissionFlag.OTHERS_WRITE)
    if (mode and S_IXOTH != 0) add(PermissionFlag.OTHERS_EXECUTE)
    if (mode and S_ISUID != 0) add(PermissionFlag.SETUID)
    if (mode and S_ISGID != 0) add(PermissionFlag.SETGID)
    if (mode and S_ISVTX != 0) add(PermissionFlag.STICKY)
}
