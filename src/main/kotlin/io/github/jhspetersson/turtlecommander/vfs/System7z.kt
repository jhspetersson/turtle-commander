package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The system 7-Zip CLI is missing, or present but too old for the requested format. Consumers
 * showing the message to the user (the tool window's error notification) recognize this type and
 * attach a "Download 7-Zip" action opening [System7z.DOWNLOAD_URL]; the URL is also embedded in
 * the message text for contexts that only surface the string.
 */
internal class System7zUnavailableException(message: String) : IOException(message)

internal object System7z {

    const val DOWNLOAD_URL = "https://www.7-zip.org/download.html"

    /**
     * Standard tail for "install 7-Zip" error messages: how to make a binary discoverable,
     * plus the download link. Callers prefix the format-specific sentence.
     */
    const val INSTALL_HINT = "Install 7-Zip / p7zip and ensure 7z, 7zz, or 7za is on PATH " +
        "(or in the standard 7-Zip install location on Windows) to open it. " +
        "Download: $DOWNLOAD_URL"

    /** Locates a usable 7-Zip CLI, or `null` if none is installed / on PATH. */
    fun findBinary(): String? {
        val candidates = if (SystemInfo.isWindows) {
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
        if (SystemInfo.isWindows) {
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

    /**
     * Extracts [archive] into [into] with the system 7-Zip CLI. [password] is passed via
     * `-p`; `null`/empty means "no password", which makes 7-Zip fail fast on encrypted
     * input rather than blocking on an interactive prompt. Honors cancellation through
     * [progress]. Throws [IOException] when no binary is found or the run exits non-zero.
     */
    fun extract(archive: Path, into: Path, progress: VfsOpenProgress, password: String? = null) {
        val binary = findBinary() ?: throw System7zUnavailableException(
            "System 7-Zip not found. $INSTALL_HINT",
        )
        val process = ProcessBuilder(
            binary,
            "x",                                  // extract preserving paths
            "-y",                                 // assume yes on overwrite prompts
            "-bd",                                // disable progress bar (clean output)
            "-p" + (password ?: ""),              // empty => fail on encrypted instead of prompting
            "-o" + into.toAbsolutePath(),
            archive.toAbsolutePath().toString(),
        ).redirectErrorStream(true).start()

        // Close stdin so any unanticipated prompt sees EOF and the process
        // exits instead of deadlocking us.
        try { process.outputStream.close() } catch (_: Exception) {}

        // Drain the merged stdout/stderr into a small ring buffer so we can surface the
        // tail in the error message if extraction fails, without unbounded log volume.
        val tail = ArrayDeque<String>()
        process.inputStream.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                if (progress.isCancelled) {
                    process.destroy()
                    throw InterruptedException("Cancelled")
                }
                progress.onEntry(0, 0, line.trim().take(120))
                tail.addLast(line)
                if (tail.size > 30) tail.removeFirst()
            }
        }
        val exit = process.waitFor()
        if (exit != 0) {
            throw IOException(
                "system 7-Zip exited with code $exit. Tail of output:\n" + tail.joinToString("\n"),
            )
        }
    }

    private val versionCache = ConcurrentHashMap<String, Int>()

    /**
     * The version of the 7-Zip CLI at [binary] encoded as `major * 100 + minor` (21.02 → 2102),
     * parsed from the banner the binary prints when run without arguments, or `null` when the
     * probe fails or the banner is unrecognizable. Cached per binary path — callers use this to
     * fail fast with a clear "too old" message for formats newer than the installed 7-Zip
     * (VHDX needs 21.02+) instead of surfacing a generic "can not open" extraction error.
     */
    fun binaryVersion(binary: String): Int? {
        versionCache[binary]?.let { return it }
        val version = try {
            val process = ProcessBuilder(binary).redirectErrorStream(true).start()
            try { process.outputStream.close() } catch (_: Exception) {}
            val lines = process.inputStream.bufferedReader().use { reader ->
                generateSequence { reader.readLine() }.take(5).toList()
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroy()
            parseVersionBanner(lines)
        } catch (_: Exception) {
            null
        }
        if (version != null) versionCache[binary] = version
        return version
    }

    internal fun parseVersionBanner(lines: List<String>): Int? {
        for (line in lines) {
            val match = VERSION_PATTERN.find(line) ?: continue
            val (major, minor) = match.destructured
            return major.toInt() * 100 + minor.toInt()
        }
        return null
    }

    fun formatVersion(version: Int): String = "%d.%02d".format(version / 100, version % 100)

    private val VERSION_PATTERN = Regex("""7-Zip(?:\s+\([^)]*\)|\s+\[[^\]]*\])?\s+(\d+)\.(\d+)""")
}
