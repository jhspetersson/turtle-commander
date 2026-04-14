package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.diagnostic.thisLogger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cleans up stale `turtle-vfs-*` temp directories left over from previous plugin
 * sessions. These dirs are created to host files extracted from archives for
 * viewing/editing; they were previously only marked with [java.io.File.deleteOnExit],
 * which is unreliable across JVM crashes and accumulates registrations indefinitely.
 *
 * Runs once per JVM, the first time any archive-temp dir is created. Only removes
 * directories whose last-modified time is older than [MAX_AGE_MS] so we do not
 * clobber dirs a concurrent plugin instance is still using.
 */
internal object VfsTempCleanup {
    private const val PREFIX = "turtle-vfs-"
    private const val MAX_AGE_MS = 60L * 60 * 1000 // 1 hour

    private val done = AtomicBoolean(false)

    fun cleanupOnce() {
        if (!done.compareAndSet(false, true)) return
        runCatching { cleanNow(defaultTempDir(), MAX_AGE_MS) }
            .onFailure { thisLogger().debug("VfsTempCleanup failed", it) }
    }

    internal fun defaultTempDir(): Path = Path.of(System.getProperty("java.io.tmpdir"))

    /** Visible for testing. Removes `turtle-vfs-*` dirs in [tempDir] older than [maxAgeMs]. */
    internal fun cleanNow(tempDir: Path, maxAgeMs: Long): Int {
        if (!Files.isDirectory(tempDir)) return 0
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var removed = 0
        Files.list(tempDir).use { stream ->
            for (path in stream) {
                val name = path.fileName?.toString() ?: continue
                if (!name.startsWith(PREFIX)) continue
                val mtime = runCatching { Files.getLastModifiedTime(path) }.getOrNull() ?: continue
                if (mtime.toMillis() > cutoff) continue
                if (deleteRecursive(path)) removed++
            }
        }
        return removed
    }

    private fun deleteRecursive(path: Path): Boolean {
        if (!Files.exists(path)) return false
        return runCatching {
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
            !Files.exists(path)
        }.getOrDefault(false)
    }

    @Suppress("unused") // for test-only reset
    internal fun resetForTesting() {
        done.set(false)
    }
}
