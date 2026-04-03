package io.github.jhspetersson.turtlecommander.service
import io.github.jhspetersson.turtlecommander.util.readFilePermissions
import io.github.jhspetersson.turtlecommander.dialog.DateFilterMode
import io.github.jhspetersson.turtlecommander.dialog.DateFilter
import io.github.jhspetersson.turtlecommander.dialog.SizeFilterMode
import io.github.jhspetersson.turtlecommander.dialog.SizeFilter
import io.github.jhspetersson.turtlecommander.dialog.NamePatternMode
import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.model.FileEntry

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.math.abs


class FileSearchService(
    private val criteria: FileSearchCriteria,
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win"),
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
                    val wrappedPattern = if ('.' !in pattern && '*' !in pattern) "*$pattern*" else pattern
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

        val permissions = readPermissions(path)
        val entry = FileEntry(
            name = name,
            path = path,
            isDirectory = attrs.isDirectory,
            size = attrs.size(),
            creationTime = attrs.creationTime(),
            lastModified = attrs.lastModifiedTime(),
            permissions = permissions,
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

    private fun readPermissions(path: Path): String = readFilePermissions(path, isWindows)

    private fun waitWhilePaused(isCancelled: () -> Boolean) {
        while (paused && !isCancelled()) {
            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L) // 10ms
        }
    }
}
