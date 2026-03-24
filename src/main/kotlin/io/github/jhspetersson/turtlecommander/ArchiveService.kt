package io.github.jhspetersson.turtlecommander

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

enum class OverwriteResponse { YES, NO, YES_TO_ALL, NO_TO_ALL }

@Service(Service.Level.PROJECT)
class ArchiveService {

    suspend fun packZip(
        archivePath: Path,
        sourcePaths: List<Path>,
        appendToExisting: Boolean,
        archiveExists: Boolean,
        onProgress: (packedCount: Int, fileName: String) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val env = mutableMapOf<String, String>()
        if (!appendToExisting && !archiveExists) {
            env["create"] = "true"
        }
        if (!appendToExisting && archiveExists) {
            withContext(Dispatchers.IO) { Files.delete(archivePath) }
            env["create"] = "true"
        }

        val uri = java.net.URI.create("jar:" + archivePath.toUri())
        withContext(Dispatchers.IO) {
            FileSystems.newFileSystem(uri, env).use { zipFs ->
                var packedCount = 0
                for (source in sourcePaths) {
                    if (isCancelled()) break
                    if (source.toFile().isDirectory) {
                        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                                if (isCancelled()) return FileVisitResult.TERMINATE
                                val relativePath = source.parent.relativize(dir).toString().replace("\\", "/")
                                val zipDir = zipFs.getPath(relativePath)
                                try { Files.createDirectories(zipDir) } catch (_: FileAlreadyExistsException) {}
                                packedCount++
                                onProgress(packedCount, dir.fileName.toString())
                                return FileVisitResult.CONTINUE
                            }

                            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                                if (isCancelled()) return FileVisitResult.TERMINATE
                                val relativePath = source.parent.relativize(file).toString().replace("\\", "/")
                                val zipEntry = zipFs.getPath(relativePath)
                                Files.copy(file, zipEntry, StandardCopyOption.REPLACE_EXISTING)
                                packedCount++
                                onProgress(packedCount, file.fileName.toString())
                                return FileVisitResult.CONTINUE
                            }
                        })
                    } else {
                        val zipEntry = zipFs.getPath(source.fileName.toString())
                        Files.copy(source, zipEntry, StandardCopyOption.REPLACE_EXISTING)
                        packedCount++
                        onProgress(packedCount, source.fileName.toString())
                    }
                }
            }
        }
    }

    suspend fun packTarGz(
        archivePath: Path,
        sourcePaths: List<Path>,
        onProgress: (packedCount: Int, fileName: String) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        withContext(Dispatchers.IO) {
            java.io.BufferedOutputStream(Files.newOutputStream(archivePath)).use { fos ->
                java.util.zip.GZIPOutputStream(fos).use { gzos ->
                    TarOutputStream(gzos).use { tarOs ->
                        var packedCount = 0
                        for (source in sourcePaths) {
                            if (isCancelled()) break
                            if (source.toFile().isDirectory) {
                                Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                                        if (isCancelled()) return FileVisitResult.TERMINATE
                                        val relativePath = source.parent.relativize(dir).toString().replace("\\", "/")
                                        if (relativePath.isNotEmpty()) {
                                            tarOs.putDirectoryEntry("$relativePath/", attrs.lastModifiedTime().toMillis())
                                        }
                                        packedCount++
                                        onProgress(packedCount, dir.fileName.toString())
                                        return FileVisitResult.CONTINUE
                                    }

                                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                                        if (isCancelled()) return FileVisitResult.TERMINATE
                                        val relativePath = source.parent.relativize(file).toString().replace("\\", "/")
                                        tarOs.putFileEntry(relativePath, file, attrs.size(), attrs.lastModifiedTime().toMillis())
                                        packedCount++
                                        onProgress(packedCount, file.fileName.toString())
                                        return FileVisitResult.CONTINUE
                                    }
                                })
                            } else {
                                val attrs = Files.readAttributes(source, BasicFileAttributes::class.java)
                                tarOs.putFileEntry(source.fileName.toString(), source, attrs.size(), attrs.lastModifiedTime().toMillis())
                                packedCount++
                                onProgress(packedCount, source.fileName.toString())
                            }
                        }
                        tarOs.finish()
                    }
                }
            }
        }
    }

    suspend fun countArchiveEntries(archivePath: Path): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            VirtualFileSystemRegistry.create(archivePath).use { vfs ->
                val root = vfs.root
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        count++
                        return FileVisitResult.CONTINUE
                    }

                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir != root) count++
                        return FileVisitResult.CONTINUE
                    }
                })
            }
        } catch (_: Exception) {
        }
        count
    }

    suspend fun extractArchiveWithProgress(
        archivePath: Path,
        destination: Path,
        overwriteAll: Boolean,
        onProgress: suspend (extractedCount: Int, currentFile: String) -> Unit,
        onOverwriteConfirm: suspend (path: Path) -> OverwriteResponse,
        onError: suspend (path: Path, error: Exception) -> Unit,
        isCancelled: () -> Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        var extractedCount = 0
        var autoOverwrite = overwriteAll
        var autoSkip = false

        try {
            VirtualFileSystemRegistry.create(archivePath).use { vfs ->
                val root = vfs.root
                data class VfsEntry(val sourcePath: Path, val relativePath: String, val isDirectory: Boolean)
                val entries = mutableListOf<VfsEntry>()
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir != root) {
                            entries.add(VfsEntry(dir, root.relativize(dir).toString(), true))
                        }
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        entries.add(VfsEntry(file, root.relativize(file).toString(), false))
                        return FileVisitResult.CONTINUE
                    }
                })
                for (entry in entries) {
                    if (isCancelled()) break
                    if (entry.isDirectory) {
                        val targetDir = destination.resolve(entry.relativePath)
                        try {
                            Files.createDirectories(targetDir)
                        } catch (e: Exception) {
                            onError(targetDir, e)
                        }
                    } else {
                        val targetFile = destination.resolve(entry.relativePath)
                        try {
                            Files.createDirectories(targetFile.parent)
                            if (Files.exists(targetFile)) {
                                if (autoOverwrite) {
                                    Files.copy(entry.sourcePath, targetFile, StandardCopyOption.REPLACE_EXISTING)
                                } else if (!autoSkip) {
                                    val response = onOverwriteConfirm(targetFile)
                                    when (response) {
                                        OverwriteResponse.YES -> Files.copy(entry.sourcePath, targetFile, StandardCopyOption.REPLACE_EXISTING)
                                        OverwriteResponse.NO -> {}
                                        OverwriteResponse.YES_TO_ALL -> {
                                            autoOverwrite = true
                                            Files.copy(entry.sourcePath, targetFile, StandardCopyOption.REPLACE_EXISTING)
                                        }
                                        OverwriteResponse.NO_TO_ALL -> {
                                            autoSkip = true
                                        }
                                    }
                                }
                            } else {
                                Files.copy(entry.sourcePath, targetFile)
                            }
                        } catch (e: Exception) {
                            onError(targetFile, e)
                        }
                    }
                    extractedCount++
                    onProgress(extractedCount, entry.relativePath)
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to extract archive $archivePath: ${e.message}")
            onError(archivePath, e)
        }
    }
}
