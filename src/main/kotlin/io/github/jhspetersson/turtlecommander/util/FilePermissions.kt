package io.github.jhspetersson.turtlecommander.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * OS-agnostic bag of file-permission flags used by the search dialog's permissions filter.
 * POSIX flags are only populated on Linux/macOS and DOS flags only on Windows — the UI picks
 * which subset to show based on the host OS, so a single criterion set can travel through the
 * search pipeline without branching at every call site.
 */
enum class PermissionFlag {
    OWNER_READ, OWNER_WRITE, OWNER_EXECUTE,
    GROUP_READ, GROUP_WRITE, GROUP_EXECUTE,
    OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE,
    SETUID, SETGID, STICKY,
    DOS_READ_ONLY, DOS_HIDDEN, DOS_SYSTEM, DOS_ARCHIVE;

    companion object {
        val POSIX_FLAGS: List<PermissionFlag> = listOf(
            OWNER_READ, OWNER_WRITE, OWNER_EXECUTE,
            GROUP_READ, GROUP_WRITE, GROUP_EXECUTE,
            OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE,
            SETUID, SETGID, STICKY,
        )
        val DOS_FLAGS: List<PermissionFlag> = listOf(
            DOS_READ_ONLY, DOS_HIDDEN, DOS_SYSTEM, DOS_ARCHIVE,
        )
    }
}

private fun PosixFilePermission.toFlag(): PermissionFlag = when (this) {
    PosixFilePermission.OWNER_READ -> PermissionFlag.OWNER_READ
    PosixFilePermission.OWNER_WRITE -> PermissionFlag.OWNER_WRITE
    PosixFilePermission.OWNER_EXECUTE -> PermissionFlag.OWNER_EXECUTE
    PosixFilePermission.GROUP_READ -> PermissionFlag.GROUP_READ
    PosixFilePermission.GROUP_WRITE -> PermissionFlag.GROUP_WRITE
    PosixFilePermission.GROUP_EXECUTE -> PermissionFlag.GROUP_EXECUTE
    PosixFilePermission.OTHERS_READ -> PermissionFlag.OTHERS_READ
    PosixFilePermission.OTHERS_WRITE -> PermissionFlag.OTHERS_WRITE
    PosixFilePermission.OTHERS_EXECUTE -> PermissionFlag.OTHERS_EXECUTE
}

fun readFilePermissionFlags(path: Path, isWindows: Boolean): Set<PermissionFlag> {
    return try {
        if (isWindows) {
            val attrs = Files.readAttributes(path, DosFileAttributes::class.java)
            buildSet {
                if (attrs.isReadOnly) add(PermissionFlag.DOS_READ_ONLY)
                if (attrs.isHidden) add(PermissionFlag.DOS_HIDDEN)
                if (attrs.isSystem) add(PermissionFlag.DOS_SYSTEM)
                if (attrs.isArchive) add(PermissionFlag.DOS_ARCHIVE)
            }
        } else {
            // Prefer the raw mode so the setuid/setgid/sticky bits are filterable (the
            // PosixFilePermission set below omits them). Mirrors readFilePermissions.
            val mode = try {
                Files.getAttribute(path, "unix:mode") as? Int
            } catch (_: Exception) {
                null
            }
            if (mode != null) {
                posixModeToFlags(mode)
            } else {
                Files.getPosixFilePermissions(path).mapTo(mutableSetOf()) { it.toFlag() }
            }
        }
    } catch (_: Exception) {
        emptySet()
    }
}
