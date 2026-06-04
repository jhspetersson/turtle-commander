package io.github.jhspetersson.turtlecommander.service
import com.intellij.openapi.util.SystemInfo
import io.github.jhspetersson.turtlecommander.util.PermissionFlag
import io.github.jhspetersson.turtlecommander.util.readFileGroup
import io.github.jhspetersson.turtlecommander.util.readFileOwner
import io.github.jhspetersson.turtlecommander.util.readFilePermissionFlags
import io.github.jhspetersson.turtlecommander.util.readFilePermissions
import io.github.jhspetersson.turtlecommander.util.wrapAsSubstringGlobIfPlain
import io.github.jhspetersson.turtlecommander.dialog.DateFilterMode
import io.github.jhspetersson.turtlecommander.dialog.DateFilter
import io.github.jhspetersson.turtlecommander.dialog.PermissionsFilter
import io.github.jhspetersson.turtlecommander.dialog.PermissionsFilterMode
import io.github.jhspetersson.turtlecommander.dialog.ResultKind
import io.github.jhspetersson.turtlecommander.dialog.SizeFilterMode
import io.github.jhspetersson.turtlecommander.dialog.SizeFilter
import io.github.jhspetersson.turtlecommander.dialog.SymlinkSearchFilter
import io.github.jhspetersson.turtlecommander.dialog.NamePatternMode
import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry

import java.io.BufferedReader
import java.io.IOException
import java.nio.charset.MalformedInputException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.math.abs


class FileSearchService(
    private val criteria: FileSearchCriteria,
    private val isWindows: Boolean = SystemInfo.isWindows,
    private val ownerReader: (Path) -> String = ::readFileOwner,
    private val groupReader: (Path) -> String = ::readFileGroup,
    private val permissionsFlagReader: (Path) -> Set<PermissionFlag> = { readFilePermissionFlags(it, isWindows) },
) {
    @Volatile
    var paused = false

    class InvalidPatternException(message: String, cause: Throwable) : Exception(message, cause)

    fun search(
        onResult: (FileEntry) -> Unit,
        isCancelled: () -> Boolean,
        onProgress: (scannedCount: Int, currentDir: String) -> Unit,
    ) {
        val nameMatcher: ((String) -> Boolean)? = criteria.namePattern?.let { pattern ->
            when (criteria.namePatternMode) {
                NamePatternMode.GLOB -> {
                    val wrappedPattern = wrapAsSubstringGlobIfPlain(pattern)
                    val effectivePattern = if (criteria.caseSensitive) wrappedPattern else wrappedPattern.lowercase()
                    val pathMatcher = try {
                        FileSystems.getDefault().getPathMatcher("glob:$effectivePattern")
                    } catch (e: Exception) {
                        throw InvalidPatternException("Invalid glob pattern: $pattern", e)
                    }
                    val fn: (String) -> Boolean = { name ->
                        val effectiveName = if (criteria.caseSensitive) name else name.lowercase()
                        pathMatcher.matches(Path.of(effectiveName))
                    }
                    fn
                }
                NamePatternMode.REGEXP -> {
                    val options = if (criteria.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                    val re = try {
                        pattern.toRegex(options)
                    } catch (e: Exception) {
                        throw InvalidPatternException("Invalid regex pattern: $pattern", e)
                    }
                    val fn: (String) -> Boolean = { name -> re.containsMatchIn(name) }
                    fn
                }
            }
        }

        var scannedCount = 0

        Files.walkFileTree(criteria.rootPath, object : FileVisitor<Path> {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (isCancelled()) return FileVisitResult.TERMINATE
                waitWhilePaused(isCancelled)
                scannedCount++
                if (scannedCount % 50 == 0) {
                    onProgress(scannedCount, dir.toString())
                }
                if (dir != criteria.rootPath) {
                    checkAndReport(dir, attrs, nameMatcher, onResult)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (isCancelled()) return FileVisitResult.TERMINATE
                waitWhilePaused(isCancelled)
                scannedCount++
                if (scannedCount % 50 == 0) {
                    onProgress(scannedCount, file.parent?.toString() ?: "")
                }
                checkAndReport(file, attrs, nameMatcher, onResult)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                if (isCancelled()) return FileVisitResult.TERMINATE
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (isCancelled()) return FileVisitResult.TERMINATE
                return FileVisitResult.CONTINUE
            }
        })

        onProgress(scannedCount, "")
    }

    private fun checkAndReport(
        path: Path,
        attrs: BasicFileAttributes,
        nameMatcher: ((String) -> Boolean)?,
        onResult: (FileEntry) -> Unit,
    ) {
        val name = path.fileName?.toString() ?: return

        when (criteria.resultKind) {
            ResultKind.FILES -> if (attrs.isDirectory) return
            ResultKind.DIRS -> if (!attrs.isDirectory) return
            ResultKind.ALL -> Unit
        }

        // walkFileTree doesn't follow links, so a symlink arrives with NOFOLLOW attrs
        // (isSymbolicLink true). A link is broken when following it can't find the target.
        val isLink = attrs.isSymbolicLink
        when (criteria.symlinkFilter) {
            SymlinkSearchFilter.ONLY -> if (!isLink) return
            SymlinkSearchFilter.EXCLUDE -> if (isLink) return
            SymlinkSearchFilter.ANY -> Unit
        }

        if (nameMatcher != null && !nameMatcher(name)) return

        if (criteria.sizeFilter != null) {
            if (attrs.isDirectory) return
            if (!matchesSize(attrs.size(), criteria.sizeFilter)) return
        }

        if (criteria.creationDateFilter != null) {
            if (!matchesDate(attrs.creationTime().toMillis(), criteria.creationDateFilter)) return
        }

        if (criteria.modificationDateFilter != null) {
            if (!matchesDate(attrs.lastModifiedTime().toMillis(), criteria.modificationDateFilter)) return
        }

        val owner = if (criteria.ownerPattern != null) ownerReader(path) else ""
        val group = if (criteria.groupPattern != null && !isWindows) groupReader(path) else ""

        if (criteria.ownerPattern != null) {
            if (!owner.contains(criteria.ownerPattern, ignoreCase = true)) return
        }
        if (criteria.groupPattern != null) {
            if (!group.contains(criteria.groupPattern, ignoreCase = true)) return
        }

        if (criteria.permissionsFilter != null) {
            val flags = permissionsFlagReader(path)
            if (!matchesPermissions(flags, criteria.permissionsFilter)) return
        }

        if (criteria.contentPattern != null) {
            if (attrs.isDirectory) return
            if (!matchesContent(path, criteria.contentPattern, criteria.contentCaseSensitive)) return
        }

        val permissions = readPermissions(path)
        val brokenLink = isLink && !Files.exists(path)
        val linkTarget = if (isLink) {
            try { Files.readSymbolicLink(path).toString() } catch (_: Exception) { null }
        } else {
            null
        }
        val entry = FileEntry(
            name = name,
            path = path,
            isDirectory = attrs.isDirectory,
            size = attrs.size(),
            creationTime = attrs.creationTime(),
            lastModified = attrs.lastModifiedTime(),
            owner = owner,
            group = group,
            permissions = permissions,
            isSymbolicLink = isLink,
            isBrokenSymlink = brokenLink,
            linkTarget = linkTarget,
        )
        onResult(entry)
    }

    private fun matchesSize(size: Long, filter: SizeFilter): Boolean {
        return when (filter.mode) {
            SizeFilterMode.MORE_THAN -> size > filter.sizeBytes
            SizeFilterMode.LESS_THAN -> size < filter.sizeBytes
            SizeFilterMode.APPROX_EQUAL -> {
                val tolerance = (filter.sizeBytes * 0.1).toLong().coerceAtLeast(1)
                abs(size - filter.sizeBytes) <= tolerance
            }
            SizeFilterMode.IN_BETWEEN -> {
                val low = minOf(filter.sizeBytes, filter.sizeBytes2 ?: filter.sizeBytes)
                val high = maxOf(filter.sizeBytes, filter.sizeBytes2 ?: filter.sizeBytes)
                size in low..high
            }
        }
    }

    private fun matchesDate(millis: Long, filter: DateFilter): Boolean {
        return when (filter.mode) {
            DateFilterMode.EARLIER -> millis < filter.startMillis
            DateFilterMode.LATER -> millis > filter.endMillis
            DateFilterMode.APPROX_EQUAL -> millis in filter.startMillis..filter.endMillis
            DateFilterMode.IN_BETWEEN -> {
                val low = minOf(filter.startMillis, filter.startMillis2 ?: filter.startMillis)
                val high = maxOf(filter.endMillis, filter.endMillis2 ?: filter.endMillis)
                millis in low..high
            }
        }
    }

    private fun matchesContent(path: Path, pattern: String, caseSensitive: Boolean): Boolean {
        // Stream the bytes from the source VFS if [path] points at a lazy-VFS stub
        // (currently only .iso entries); harmless no-op for regular files and the other
        // VFS implementations that extract eagerly.
        OpenVfsRegistry.materializeIfNeeded(path)
        try {
            val reader: BufferedReader = Files.newBufferedReader(path, Charsets.UTF_8)
            reader.use { br ->
                br.lineSequence().forEach { line ->
                    if (line.contains(pattern, ignoreCase = !caseSensitive)) {
                        return true
                    }
                }
            }
        } catch (_: MalformedInputException) {
            // binary file, skip
        } catch (_: IOException) {
            // unreadable, skip
        }
        return false
    }

    private fun readPermissions(path: Path): String = readFilePermissions(path, isWindows)

    private fun matchesPermissions(fileFlags: Set<PermissionFlag>, filter: PermissionsFilter): Boolean {
        return when (filter.mode) {
            PermissionsFilterMode.EXACT_MATCH -> fileFlags == filter.flags
            PermissionsFilterMode.ALL_OF -> fileFlags.containsAll(filter.flags)
            PermissionsFilterMode.ANY_OF -> filter.flags.isEmpty() || filter.flags.any { it in fileFlags }
            PermissionsFilterMode.NONE_OF -> filter.flags.none { it in fileFlags }
        }
    }

    private fun waitWhilePaused(isCancelled: () -> Boolean) {
        while (paused && !isCancelled()) {
            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L) // 10ms
        }
    }
}
