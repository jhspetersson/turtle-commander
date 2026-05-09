package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import io.github.jhspetersson.turtlecommander.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.*

class SevenZipFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("7z")
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem {
        return SevenZipVirtualFileSystem(archivePath)
    }
}

class SevenZipVirtualFileSystem(
    override val archivePath: Path,
) : VirtualFileSystem {

    private var tempDir: Path = extractArchive()

    override val root: Path get() = tempDir

    private fun extractArchive(): Path {
        val indicator = ProgressManager.getGlobalProgressIndicator()
        val dir = Files.createTempDirectory("turtle-7z-")
        try {
            extractWithCommonsCompress(dir, indicator)
        } catch (e: Exception) {
            // commons-compress can't decode multi-stream coders (BCJ2, certain
            // delta variants, some encrypted modes). Fall back to invoking the
            // user's installed 7-Zip CLI before giving up — it handles
            // everything 7-Zip itself can produce.
            if (looksLikeUnsupportedCoder(e)) {
                try {
                    // Wipe whatever commons-compress wrote before failing —
                    // mixing partial output with the system tool's full
                    // extract would be a mess.
                    dir.toFile().deleteRecursively()
                    Files.createDirectory(dir)
                    extractWithSystem7z(dir, indicator)
                    return dir
                } catch (toolError: Exception) {
                    dir.toFile().deleteRecursively()
                    throw IOException(
                        "This 7z archive uses a coder commons-compress doesn't support " +
                            "(e.g. BCJ2). Tried system 7-Zip but: " +
                            (toolError.message ?: toolError::class.simpleName),
                        toolError,
                    )
                }
            }
            dir.toFile().deleteRecursively()
            throw e
        }
        return dir
    }

    private fun extractWithCommonsCompress(dir: Path, indicator: ProgressIndicator?) {
        SevenZFile.builder().setPath(archivePath).get().use { sevenZ ->
            var entry = sevenZ.nextEntry
            while (entry != null) {
                if (indicator?.isCanceled == true) break
                indicator?.text2 = entry.name
                val entryPath = resolveEntryPath(dir, entry.name)
                if (entryPath == null) {
                    entry = sevenZ.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    Files.createDirectories(entryPath)
                } else {
                    Files.createDirectories(entryPath.parent)
                    Files.newOutputStream(entryPath).use { out ->
                        val buf = ByteArray(8192)
                        var len: Int
                        while (sevenZ.read(buf).also { len = it } != -1) {
                            out.write(buf, 0, len)
                        }
                    }
                }
                try {
                    if (entry.hasLastModifiedDate) {
                        Files.setLastModifiedTime(entryPath, FileTime.fromMillis(entry.lastModifiedDate.time))
                    }
                } catch (e: Exception) {
                    thisLogger().debug("Failed to set modified time for 7z entry: ${entry.name}", e)
                }
                entry = sevenZ.nextEntry
            }
        }
    }

    /**
     * Spawns the system-installed 7-Zip binary to extract [archivePath] into
     * [dir]. Throws [IOException] if no binary is found or extraction fails.
     */
    private fun extractWithSystem7z(dir: Path, indicator: ProgressIndicator?) {
        val binary = findSystem7zBinary() ?: throw IOException(
            "System 7-Zip not found. Install p7zip / 7-Zip and ensure 7z, 7zz, " +
                "or 7za is on PATH (or installed in the standard 7-Zip location on Windows).",
        )
        val process = ProcessBuilder(
            binary,
            "x",                                  // extract preserving paths
            "-y",                                 // assume yes on overwrite prompts
            "-bd",                                // disable progress bar (clean output)
            "-p",                                 // empty password — fail rather than prompt
            "-o" + dir.toAbsolutePath(),
            archivePath.toAbsolutePath().toString(),
        ).redirectErrorStream(true).start()

        // Close stdin so any unanticipated prompt sees EOF and the process
        // exits instead of deadlocking us.
        try { process.outputStream.close() } catch (_: Exception) {}

        // Drain the merged stdout/stderr into a small ring buffer so we can
        // surface the tail in the error message if extraction fails. Keeping
        // a bounded buffer protects us from runaway log volume on huge
        // archives.
        val tail = ArrayDeque<String>()
        process.inputStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                if (indicator?.isCanceled == true) {
                    process.destroy()
                    throw InterruptedException("Cancelled")
                }
                indicator?.text2 = line.trim().take(120)
                tail.addLast(line)
                if (tail.size > 30) tail.removeFirst()
            }
        }
        val exit = process.waitFor()
        if (exit != 0) {
            throw IOException(
                "system 7-Zip exited with code $exit. Tail of output:\n" +
                    tail.joinToString("\n"),
            )
        }
    }

    private fun findSystem7zBinary(): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val candidates = if (isWindows) {
            listOf("7z.exe", "7zz.exe", "7za.exe")
        } else {
            // Order: 7z (full), 7zz (newer p7zip-zstd builds, macOS Homebrew),
            // 7za (standalone — supports BCJ2 too via bundled codecs).
            listOf("7z", "7zz", "7za")
        }
        val pathEnv = System.getenv("PATH").orEmpty()
        for (segment in pathEnv.split(File.pathSeparatorChar)) {
            if (segment.isBlank()) continue
            val pathDir = try { Path.of(segment) } catch (_: Exception) { continue }
            for (name in candidates) {
                val candidate = pathDir.resolve(name)
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toString()
                }
            }
        }
        // Windows: the 7-Zip MSI doesn't add itself to PATH by default, so
        // probe the standard install locations.
        if (isWindows) {
            val roots = listOf(
                System.getenv("ProgramW6432"),
                System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)"),
            )
            for (root in roots) {
                if (root.isNullOrBlank()) continue
                val candidate = try { Path.of(root, "7-Zip", "7z.exe") } catch (_: Exception) { continue }
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toString()
                }
            }
        }
        return null
    }

    private fun looksLikeUnsupportedCoder(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            val msg = current.message.orEmpty()
            // "Multi input/output stream coders are not yet supported" is the
            // canonical commons-compress message for BCJ2 et al.; the other
            // strings cover related decoder gaps that the system tool can also
            // handle.
            if ("Multi input/output stream coders" in msg ||
                "Unsupported Codec" in msg ||
                "BCJ2" in msg
            ) return true
            current = current.cause
        }
        return false
    }

    override fun isRoot(path: Path): Boolean {
        return path.normalize() == tempDir.normalize()
    }

    override fun getPath(relativePath: String): Path {
        val clean = relativePath.removePrefix("/").removePrefix("\\")
        return if (clean.isEmpty()) tempDir else tempDir.resolve(clean)
    }

    override suspend fun listFiles(directory: Path): List<FileEntry> = withContext(Dispatchers.IO) {
        val result = mutableListOf<FileEntry>()

        if (!isRoot(directory)) {
            result.add(parentEntry(directory.parent ?: tempDir))
        } else {
            result.add(parentEntry(archivePath.parent ?: archivePath))
        }

        result.addAll(readDirectoryEntries(directory))

        result
    }

    override suspend fun renameFile(source: Path, newName: String): Path = withContext(Dispatchers.IO) {
        val parent = source.parent ?: throw IllegalArgumentException("Cannot rename a root path")
        val target = parent.resolve(newName)
        Files.move(source, target)
        repackArchive()
        target
    }

    private fun repackArchive() {
        SevenZOutputFile(archivePath.toFile()).use { out ->
            // SevenZOutputFile duck-types as an OutputStream (write(int)/write(byte[])/
            // write(byte[],int,int)) but doesn't actually extend it, so we can't hand it
            // to Files.copy directly. A thin adapter lets us replace the hand-rolled 8KB
            // read/write loop with the JDK's tuned Files.copy → OutputStream path.
            val sink = object : OutputStream() {
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            }
            forEachArchiveEntry(tempDir) { path, relativeName ->
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                val entry = SevenZArchiveEntry().apply {
                    name = relativeName + if (attrs.isDirectory) "/" else ""
                    isDirectory = attrs.isDirectory
                    if (!attrs.isDirectory) size = attrs.size()
                    if (attrs.lastModifiedTime() != null) {
                        lastModifiedDate = Date(attrs.lastModifiedTime().toMillis())
                    }
                }
                out.putArchiveEntry(entry)
                if (!attrs.isDirectory) {
                    Files.copy(path, sink)
                }
                out.closeArchiveEntry()
            }
        }
    }

    override fun flush() {
        repackArchive()
        tempDir.toFile().deleteRecursively()
        tempDir = extractArchive()
    }

    override fun close() {
        try {
            tempDir.toFile().deleteRecursively()
        } catch (e: Exception) {
            thisLogger().debug("Failed to clean up temp dir $tempDir: ${e.message}")
        }
    }
}
