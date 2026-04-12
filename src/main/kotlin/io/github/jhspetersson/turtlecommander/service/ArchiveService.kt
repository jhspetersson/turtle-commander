package io.github.jhspetersson.turtlecommander.service
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import io.github.jhspetersson.turtlecommander.operation.TarOutputStream

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPOutputStream

enum class OverwriteResponse { YES, NO, YES_TO_ALL, NO_TO_ALL }

@Service(Service.Level.PROJECT)
class ArchiveService {

    suspend fun packZip(
        archivePath: Path,
        sourcePaths: List<Path>,
        appendToExisting: Boolean,
        archiveExists: Boolean,
        onProgress: (packedCount: Int, fileName: String) -> Unit,
        onError: (path: Path, error: Exception) -> Unit,
        isCancelled: () -> Boolean,
    ): Int {
        val env = mutableMapOf<String, String>()
        if (!appendToExisting && !archiveExists) {
            env["create"] = "true"
        }
        if (!appendToExisting && archiveExists) {
            withContext(Dispatchers.IO) { Files.delete(archivePath) }
            env["create"] = "true"
        }

        val uri = URI.create("jar:" + archivePath.toUri())
        return withContext(Dispatchers.IO) {
            // Single counter: the progress UI and the method's return value must agree on what
            // "packed" means. We count files only — directory entries are created implicitly by
            // walkFileTree and are not interesting to the user as progress ticks.
            var successCount = 0
            FileSystems.newFileSystem(uri, env).use { zipFs ->
                for (source in sourcePaths) {
                    if (isCancelled()) break
                    if (source.toFile().isDirectory) {
                        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                                if (isCancelled()) return FileVisitResult.TERMINATE
                                val relativePath = (source.parent ?: source).relativize(dir).toString().replace("\\", "/")
                                val zipDir = zipFs.getPath(relativePath)
                                try { Files.createDirectories(zipDir) } catch (_: FileAlreadyExistsException) {}
                                // Report the directory name for UX but do not tick the counter —
                                // otherwise the final count exceeds the number of files actually
                                // written, which is what the method returns.
                                onProgress(successCount, dir.fileName.toString())
                                return FileVisitResult.CONTINUE
                            }

                            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                                if (isCancelled()) return FileVisitResult.TERMINATE
                                try {
                                    val relativePath = (source.parent ?: source).relativize(file).toString().replace("\\", "/")
                                    val zipEntry = zipFs.getPath(relativePath)
                                    Files.copy(file, zipEntry, StandardCopyOption.REPLACE_EXISTING)
                                    successCount++
                                    onProgress(successCount, file.fileName.toString())
                                } catch (e: Exception) {
                                    onError(file, e)
                                }
                                return FileVisitResult.CONTINUE
                            }

                            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                                if (isCancelled()) return FileVisitResult.TERMINATE
                                onError(file, exc)
                                return FileVisitResult.CONTINUE
                            }
                        })
                    } else {
                        try {
                            val zipEntry = zipFs.getPath(source.fileName.toString())
                            Files.copy(source, zipEntry, StandardCopyOption.REPLACE_EXISTING)
                            successCount++
                            onProgress(successCount, source.fileName.toString())
                        } catch (e: Exception) {
                            onError(source, e)
                        }
                    }
                }
            }
            successCount
        }
    }

    suspend fun packTarGz(
        archivePath: Path,
        sourcePaths: List<Path>,
        onProgress: (packedCount: Int, fileName: String) -> Unit,
        onError: (path: Path, error: Exception) -> Unit,
        isCancelled: () -> Boolean,
    ): Int {
        return withContext(Dispatchers.IO) {
            var successCount = 0
            BufferedOutputStream(Files.newOutputStream(archivePath)).use { fos ->
                GZIPOutputStream(fos).use { gzos ->
                    TarOutputStream(gzos).use { tarOs ->
                        var packedCount = 0
                        for (source in sourcePaths) {
                            if (isCancelled()) break
                            if (source.toFile().isDirectory) {
                                Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                                        if (isCancelled()) return FileVisitResult.TERMINATE
                                        val relativePath = (source.parent ?: source).relativize(dir).toString().replace("\\", "/")
                                        if (relativePath.isNotEmpty()) {
                                            tarOs.putDirectoryEntry("$relativePath/", attrs.lastModifiedTime().toMillis())
                                        }
                                        packedCount++
                                        onProgress(packedCount, dir.fileName.toString())
                                        return FileVisitResult.CONTINUE
                                    }

                                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                                        if (isCancelled()) return FileVisitResult.TERMINATE
                                        try {
                                            val relativePath = (source.parent ?: source).relativize(file).toString().replace("\\", "/")
                                            tarOs.putFileEntry(relativePath, file, attrs.lastModifiedTime().toMillis())
                                            successCount++
                                        } catch (e: Exception) {
                                            onError(file, e)
                                        }
                                        packedCount++
                                        onProgress(packedCount, file.fileName.toString())
                                        return FileVisitResult.CONTINUE
                                    }

                                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                                        if (isCancelled()) return FileVisitResult.TERMINATE
                                        onError(file, exc)
                                        return FileVisitResult.CONTINUE
                                    }
                                })
                            } else {
                                try {
                                    val attrs = Files.readAttributes(source, BasicFileAttributes::class.java)
                                    tarOs.putFileEntry(source.fileName.toString(), source, attrs.lastModifiedTime().toMillis())
                                    successCount++
                                } catch (e: Exception) {
                                    onError(source, e)
                                }
                                packedCount++
                                onProgress(packedCount, source.fileName.toString())
                            }
                        }
                        tarOs.finish()
                    }
                }
            }
            successCount
        }
    }

    suspend fun countArchiveEntries(archivePath: Path): Int = withContext(Dispatchers.IO) {
        // Prefer a header-only scan: the previous VFS-based count extracted the archive
        // to a temp directory, which doubled the work of a subsequent
        // extractArchiveWithProgress call. For known formats we can enumerate entries
        // without decompressing file bodies.
        try {
            val fast = countArchiveEntriesFast(archivePath)
            if (fast >= 0) return@withContext fast
        } catch (e: Exception) {
            thisLogger().debug("Header-only count failed for $archivePath, falling back to VFS walk", e)
        }
        countArchiveEntriesViaVfs(archivePath)
    }

    /** Returns entry count via header-only scan, or -1 if the format isn't recognized. */
    private fun countArchiveEntriesFast(archivePath: Path): Int {
        val name = archivePath.fileName?.toString()?.lowercase() ?: return -1
        return when {
            name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".war") ||
                name.endsWith(".ear") || name.endsWith(".apk") -> countZipEntries(archivePath)
            name.endsWith(".7z") -> countSevenZEntries(archivePath)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                countTarEntries(GzipCompressorInputStream(Files.newInputStream(archivePath)))
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") ->
                countTarEntries(BZip2CompressorInputStream(Files.newInputStream(archivePath)))
            name.endsWith(".tar.xz") || name.endsWith(".txz") ->
                countTarEntries(XZCompressorInputStream(Files.newInputStream(archivePath)))
            name.endsWith(".tar") -> countTarEntries(Files.newInputStream(archivePath))
            name.endsWith(".ar") || name.endsWith(".deb") || name.endsWith(".a") ->
                countArEntries(archivePath)
            // Standalone single-file compressors wrap exactly one inner file.
            name.endsWith(".gz") || name.endsWith(".bz2") || name.endsWith(".xz") -> 1
            else -> -1
        }
    }

    private fun countZipEntries(archivePath: Path): Int =
        ZipFile.builder().setPath(archivePath).get().use { zf ->
            var c = 0
            val it = zf.entries
            while (it.hasMoreElements()) { it.nextElement(); c++ }
            c
        }

    private fun countSevenZEntries(archivePath: Path): Int =
        SevenZFile.builder().setPath(archivePath).get().use { szf ->
            var c = 0
            while (szf.nextEntry != null) c++
            c
        }

    private fun countTarEntries(raw: InputStream): Int =
        raw.use { input ->
            TarArchiveInputStream(input).use { tar ->
                var c = 0
                while (tar.nextEntry != null) c++
                c
            }
        }

    private fun countArEntries(archivePath: Path): Int =
        Files.newInputStream(archivePath).use { input ->
            ArArchiveInputStream(input).use { ar ->
                var c = 0
                while (ar.nextEntry != null) c++
                c
            }
        }

    private fun countArchiveEntriesViaVfs(archivePath: Path): Int {
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
        return count
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
