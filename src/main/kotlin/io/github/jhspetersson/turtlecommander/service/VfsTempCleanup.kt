package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.diagnostic.thisLogger
import io.github.jhspetersson.turtlecommander.service.VfsTempCleanup.MAX_AGE_MS
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cleans up stale `turtle-*` temp directories left over from previous plugin sessions:
 * the `turtle-vfs-*` dirs hosting files extracted for viewing/editing, and the
 * per-format extraction dirs of the temp-dir VFS implementations (`turtle-zip-*`,
 * `turtle-tar-*`, …). Normal teardown deletes them via the Disposer-driven
 * [io.github.jhspetersson.turtlecommander.ui.FileTab.dispose]; this sweep is the
 * safety net for JVM crashes ([java.io.File.deleteOnExit] is unreliable across
 * crashes and accumulates registrations indefinitely).
 *
 * Runs once per JVM, the first time any archive-temp dir is created. Only removes
 * directories whose last-modified time is older than [MAX_AGE_MS] so we do not
 * clobber dirs a concurrent plugin instance is still using.
 */
internal object VfsTempCleanup {
    private val PREFIXES = listOf(
        "turtle-vfs-",
        // AbstractTempDirVirtualFileSystem subclasses, see their constructor arguments.
        "turtle-zip-", "turtle-tar-", "turtle-rar-", "turtle-7z-", "turtle-iso-",
        "turtle-pak-", "turtle-rpm-", "turtle-crx-", "turtle-ar-", "turtle-decompress-",
    )
    private const val MAX_AGE_MS = 60L * 60 * 1000 // 1 hour

    private val done = AtomicBoolean(false)

    fun cleanupOnce() {
        if (!done.compareAndSet(false, true)) return
        runCatching { cleanNow(defaultTempDir(), MAX_AGE_MS) }
            .onFailure { thisLogger().debug("VfsTempCleanup failed", it) }
    }

    internal fun defaultTempDir(): Path = Path.of(System.getProperty("java.io.tmpdir"))

    /** Visible for testing. Removes stale `turtle-*` temp dirs in [tempDir] older than [maxAgeMs]. */
    internal fun cleanNow(tempDir: Path, maxAgeMs: Long): Int {
        if (!Files.isDirectory(tempDir)) return 0
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var removed = 0
        Files.list(tempDir).use { stream ->
            for (path in stream) {
                val name = path.fileName?.toString() ?: continue
                if (PREFIXES.none { name.startsWith(it) }) continue
                val mtime = runCatching { Files.getLastModifiedTime(path) }.getOrNull() ?: continue
                if (mtime.toMillis() > cutoff) continue
                if (deleteRecursive(path)) removed++
            }
        }
        return removed
    }

    private fun deleteRecursive(path: Path): Boolean {
        return Files.exists(path) && runCatching {
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
            !Files.exists(path)
        }.getOrDefault(false)
    }

    internal fun resetForTesting() {
        done.set(false)
    }
}
