package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.diagnostic.thisLogger
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry
import io.github.jhspetersson.turtlecommander.vfs.TempRootLocks
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

internal object VfsTempCleanup {
    internal const val FAMILY_PREFIX = "turtle-"
    private const val MAX_AGE_MS = 60L * 60 * 1000

    private val done = AtomicBoolean(false)

    fun claim(dir: Path) = TempRootLocks.claim(dir)

    fun discard(dir: Path): Boolean = TempRootLocks.discard(dir)

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
                if (name.endsWith(TempRootLocks.LOCK_SUFFIX)) {
                    if (!Files.exists(TempRootLocks.dirFor(path)) && isStale(path, cutoff)) removeOrphanLock(path)
                    continue
                }
                if (TempRootLocks.isClaimed(path)) continue
                if (OpenVfsRegistry.hasLiveContentUnder(path)) continue
                if (!isStale(path, cutoff)) continue
                if (sweepRoot(path)) removed++
            }
        }
        return removed
    }

    private fun isStale(path: Path, cutoff: Long): Boolean {
        val mtime = runCatching { Files.getLastModifiedTime(path) }.getOrNull() ?: return false
        return mtime.toMillis() <= cutoff
    }

    private fun sweepRoot(dir: Path): Boolean {
        val lockFile = TempRootLocks.lockFileFor(dir)
        if (!Files.exists(lockFile)) return TempRootLocks.deleteTree(dir)
        val holder = TempRootLocks.Holder.acquire(lockFile) ?: return false
        return holder.use { TempRootLocks.deleteTree(dir) }
    }

    private fun removeOrphanLock(lockFile: Path) {
        TempRootLocks.Holder.acquire(lockFile)?.close()
    }

    internal fun resetForTesting() {
        done.set(false)
        TempRootLocks.releaseAll()
    }
}
