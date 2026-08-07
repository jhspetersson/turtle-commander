package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import io.github.jhspetersson.turtlecommander.operation.TarOutputStream
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry
import io.github.jhspetersson.turtlecommander.vfs.VfsOpenProgress
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
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
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPOutputStream

/**
 * One-shot decision returned by the per-file overwrite prompt. The seven values cover
 * the practical Total-Commander-style button set: act on this one, act on all, the
 * mtime-aware "if older" variants, and a global cancel.
 */
enum class OverwriteResponse {
    OVERWRITE,
    SKIP,
    OVERWRITE_ALL,
    SKIP_ALL,
    /** Overwrite this single file iff source mtime is strictly newer than target. */
    OVERWRITE_IF_OLDER,
    /** Apply the if-older rule to the current file and every remaining file in the run. */
    OVERWRITE_ALL_IF_OLDER,
    /** Abort the whole copy/move/extract operation immediately. */
    CANCEL,
}

/**
 * Long-lived policy carried across the whole copy/move/extract loop. The per-file dialog
 * may upgrade [ASK] to one of the other values; once set, the loop stops asking.
 */
enum class OverwritePolicy { ASK, OVERWRITE_ALL, SKIP_ALL, IF_OLDER }

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
                                    OpenVfsRegistry.materializeIfNeeded(file)
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
                            OpenVfsRegistry.materializeIfNeeded(source)
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
                                        onProgress(successCount, dir.fileName.toString())
                                        return FileVisitResult.CONTINUE
                                    }

                                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                                        if (isCancelled()) return FileVisitResult.TERMINATE
                                        try {
                                            val relativePath = (source.parent ?: source).relativize(file).toString().replace("\\", "/")
                                            tarOs.putFileEntry(relativePath, file, attrs.lastModifiedTime().toMillis())
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
                                    val attrs = Files.readAttributes(source, BasicFileAttributes::class.java)
                                    tarOs.putFileEntry(source.fileName.toString(), source, attrs.lastModifiedTime().toMillis())
                                    successCount++
                                    onProgress(successCount, source.fileName.toString())
                                } catch (e: Exception) {
                                    onError(source, e)
                                }
                            }
                        }
                        tarOs.finish()
                    }
                }
            }
            successCount
        }
    }

    suspend fun countArchiveEntries(archivePath: Path, openProgress: VfsOpenProgress? = null): Int = withContext(Dispatchers.IO) {
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
        countArchiveEntriesViaVfs(archivePath, openProgress)
    }

    /** Returns entry count via header-only scan, or -1 if the format isn't recognized. */
    internal fun countArchiveEntriesFast(archivePath: Path): Int {
        // A `.tar`/`.ar`/`.zip` etc. that lives as a stub inside a lazy parent VFS (.iso)
        // must be streamed to disk before the header-only readers can inspect it.
        OpenVfsRegistry.materializeIfNeeded(archivePath)
        val name = archivePath.fileName?.toString()?.lowercase() ?: return -1
        return when {
            name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".war") ||
                name.endsWith(".ear") || name.endsWith(".apk") ||
                name.endsWith(".aar") || name.endsWith(".aab") || name.endsWith(".apkg") ||
                name.endsWith(".vsix") || name.endsWith(".ipa") ||
                name.endsWith(".appx") || name.endsWith(".appxbundle") ||
                name.endsWith(".msix") || name.endsWith(".msixbundle") ||
                name.endsWith(".xps") || name.endsWith(".oxps") ||
                name.endsWith(".kmz") || name.endsWith(".usdz") ||
                name.endsWith(".oxt") ||
                name.endsWith(".pk3") || name.endsWith(".pk4") -> countZipEntries(archivePath)
            name.endsWith(".7z") || name.endsWith(".cb7") -> countSevenZEntries(archivePath)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                countTarCompressed(archivePath) { GzipCompressorInputStream(it) }
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") ->
                countTarCompressed(archivePath) { BZip2CompressorInputStream(it) }
            name.endsWith(".tar.xz") || name.endsWith(".txz") ->
                countTarCompressed(archivePath) { XZCompressorInputStream(it) }
            name.endsWith(".tar") || name.endsWith(".cbt") ->
                countTarEntries(Files.newInputStream(archivePath))
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

    /**
     * Opens [archivePath], wraps the stream with [wrap] (the compressor constructor),
     * and counts tar entries. Closes the raw file stream if the compressor constructor
     * throws — otherwise the stream would leak on corrupt archives.
     */
    internal fun countTarCompressed(archivePath: Path, wrap: (InputStream) -> InputStream): Int =
        countTarCompressed(Files.newInputStream(archivePath), wrap)

    internal fun countTarCompressed(raw: InputStream, wrap: (InputStream) -> InputStream): Int {
        val wrapped = try {
            wrap(raw)
        } catch (e: Throwable) {
            runCatching { raw.close() }
            throw e
        }
        return countTarEntries(wrapped)
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

    private fun countArchiveEntriesViaVfs(archivePath: Path, openProgress: VfsOpenProgress? = null): Int {
        var count = 0
        try {
            VirtualFileSystemRegistry.create(archivePath, openProgress).use { vfs ->
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
        initialPolicy: OverwritePolicy,
        onProgress: suspend (extractedCount: Int, currentFile: String) -> Unit,
        onOverwriteConfirm: suspend (path: Path) -> OverwriteResponse,
        onError: suspend (path: Path, error: Exception) -> Unit,
        isCancelled: () -> Boolean,
        openProgress: VfsOpenProgress? = null,
    ): Unit = withContext(Dispatchers.IO) {
        var extractedCount = 0
        var policy = initialPolicy

        try {
            VirtualFileSystemRegistry.create(archivePath, openProgress).use { vfs ->
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
                loop@ for ((sourcePath, relativePath, isDirectory) in entries) {
                    if (isCancelled()) break
                    if (isDirectory) {
                        val targetDir = destination.resolve(relativePath)
                        try {
                            Files.createDirectories(targetDir)
                        } catch (e: Exception) {
                            onError(targetDir, e)
                        }
                    } else {
                        val targetFile = destination.resolve(relativePath)
                        try {
                            // Lazy VFSs (currently .iso) populate the temp dir with sparse
                            // stubs; without this the copies below would write zero-filled
                            // files of the right size.
                            OpenVfsRegistry.materializeIfNeeded(sourcePath)
                            Files.createDirectories(targetFile.parent)
                            if (Files.exists(targetFile)) {
                                val action = resolveExtractAction(
                                    sourcePath, targetFile, policy, onOverwriteConfirm,
                                ) { policy = it }
                                when (action) {
                                    ExtractAction.OVERWRITE ->
                                        Files.copy(sourcePath, targetFile, StandardCopyOption.REPLACE_EXISTING)
                                    ExtractAction.SKIP -> { /* nothing */ }
                                    ExtractAction.CANCEL -> break@loop
                                }
                            } else {
                                Files.copy(sourcePath, targetFile)
                            }
                        } catch (e: Exception) {
                            onError(targetFile, e)
                        }
                    }
                    extractedCount++
                    onProgress(extractedCount, relativePath)
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Failed to extract archive $archivePath: ${e.message}")
            onError(archivePath, e)
        }
    }

    private enum class ExtractAction { OVERWRITE, SKIP, CANCEL }

    /**
     * Mirror of FileOperationService.resolveOverwriteAction for the extract path. The
     * service is in the same module but extract is the only consumer that can't sit
     * on the FileOperationService instance directly (it works through a VFS), so the
     * decision logic is duplicated rather than dragged through another dependency.
     */
    private suspend fun resolveExtractAction(
        source: Path,
        target: Path,
        policy: OverwritePolicy,
        onOverwriteConfirm: suspend (Path) -> OverwriteResponse,
        setPolicy: (OverwritePolicy) -> Unit,
    ): ExtractAction = when (policy) {
        OverwritePolicy.OVERWRITE_ALL -> ExtractAction.OVERWRITE
        OverwritePolicy.SKIP_ALL -> ExtractAction.SKIP
        OverwritePolicy.IF_OLDER -> ifOlder(source, target)
        OverwritePolicy.ASK -> when (onOverwriteConfirm(target)) {
            OverwriteResponse.OVERWRITE -> ExtractAction.OVERWRITE
            OverwriteResponse.SKIP -> ExtractAction.SKIP
            OverwriteResponse.OVERWRITE_ALL -> {
                setPolicy(OverwritePolicy.OVERWRITE_ALL); ExtractAction.OVERWRITE
            }
            OverwriteResponse.SKIP_ALL -> {
                setPolicy(OverwritePolicy.SKIP_ALL); ExtractAction.SKIP
            }
            OverwriteResponse.OVERWRITE_IF_OLDER -> ifOlder(source, target)
            OverwriteResponse.OVERWRITE_ALL_IF_OLDER -> {
                setPolicy(OverwritePolicy.IF_OLDER); ifOlder(source, target)
            }
            OverwriteResponse.CANCEL -> ExtractAction.CANCEL
        }
    }

    private fun ifOlder(source: Path, target: Path): ExtractAction {
        val sourceNewer = try {
            Files.getLastModifiedTime(source) > Files.getLastModifiedTime(target)
        } catch (_: Exception) {
            true
        }
        return if (sourceNewer) ExtractAction.OVERWRITE else ExtractAction.SKIP
    }
}
