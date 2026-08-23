package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.diagnostic.thisLogger
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

internal object VfsTempCleanup {
    internal const val FAMILY_PREFIX = "turtle-"
    private const val MAX_AGE_MS = 60L * 60 * 1000

    private val done = AtomicBoolean(false)

    private val protectedRoots = CopyOnWriteArraySet<Path>()

    fun protect(dir: Path) {
        protectedRoots.add(dir.normalize())
    }

    fun cleanupOnce() {
        if (!done.compareAndSet(false, true)) return
        runCatching { cleanNow(defaultTempDir(), MAX_AGE_MS) }
            .onFailure { thisLogger().debug("VfsTempCleanup failed", it) }
    }

    internal fun defaultTempDir(): Path = Path.of(System.getProperty("java.io.tmpdir"))

    internal fun cleanNow(tempDir: Path, maxAgeMs: Long): Int {
        if (!Files.isDirectory(tempDir)) return 0
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var removed = 0
        Files.list(tempDir).use { stream ->
            for (path in stream) {
                val name = path.fileName?.toString() ?: continue
                if (!name.startsWith(FAMILY_PREFIX)) continue
                if (path.normalize() in protectedRoots) continue
                if (OpenVfsRegistry.hasLiveContentUnder(path)) continue
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
        protectedRoots.clear()
    }
}
