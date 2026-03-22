package io.github.jhspetersson.turtlecommander

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

    fun search(
        onResult: (FileEntry) -> Unit,
        isCancelled: () -> Boolean,
        onProgress: (scannedCount: Int, currentDir: String) -> Unit,
    ) {
        val nameMatcher: ((String) -> Boolean)? = criteria.namePattern?.let { pattern ->
            try {
                when (criteria.namePatternMode) {
                    NamePatternMode.GLOB -> {
                        val pathMatcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
                        val fn: (String) -> Boolean = { name -> pathMatcher.matches(Path.of(name)) }
                        fn
                    }
                    NamePatternMode.REGEXP -> {
                        val re = pattern.toRegex()
                        val fn: (String) -> Boolean = { name -> re.containsMatchIn(name) }
                        fn
                    }
                }
            } catch (_: Exception) {
                null
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

            override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
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

        if (criteria.sizeFilter != null && !attrs.isDirectory) {
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
            DateFilterMode.EARLIER -> millis < filter.dateMillis
            DateFilterMode.LATER -> millis > filter.dateMillis
            DateFilterMode.APPROX_EQUAL -> {
                val tolerance = 24L * 60 * 60 * 1000 // 24 hours
                abs(millis - filter.dateMillis) <= tolerance
            }
            DateFilterMode.IN_BETWEEN -> {
                val low = minOf(filter.dateMillis, filter.dateMillis2 ?: filter.dateMillis)
                val high = maxOf(filter.dateMillis, filter.dateMillis2 ?: filter.dateMillis)
                millis in low..high
            }
        }
    }

    private fun readPermissions(path: Path): String {
        return try {
            if (isWindows) {
                val attrs = java.nio.file.Files.readAttributes(path, java.nio.file.attribute.DosFileAttributes::class.java)
                buildString {
                    if (attrs.isReadOnly) append('R')
                    if (attrs.isHidden) append('H')
                    if (attrs.isSystem) append('S')
                    if (attrs.isArchive) append('A')
                }
            } else {
                java.nio.file.Files.getPosixFilePermissions(path).joinToString("") { perm ->
                    when (perm) {
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ -> "r"
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE -> "w"
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE -> "x"
                        java.nio.file.attribute.PosixFilePermission.GROUP_READ -> "r"
                        java.nio.file.attribute.PosixFilePermission.GROUP_WRITE -> "w"
                        java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE -> "x"
                        java.nio.file.attribute.PosixFilePermission.OTHERS_READ -> "r"
                        java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE -> "w"
                        java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE -> "x"
                    }
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun waitWhilePaused(isCancelled: () -> Boolean) {
        while (paused && !isCancelled()) {
            Thread.sleep(100)
        }
    }
}
