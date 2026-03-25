package io.github.jhspetersson.turtlecommander

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
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
                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    return FileVisitResult.CONTINUE
                }
            })
        } else {
            count++
        }
    }
    count
}

fun fileErrorMessage(error: Exception): String {
    return when (error) {
        is AccessDeniedException -> "Access denied: ${error.file}"
        else -> error.message ?: error.javaClass.simpleName
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
