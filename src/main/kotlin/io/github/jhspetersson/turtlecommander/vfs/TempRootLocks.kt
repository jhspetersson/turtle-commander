package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object TempRootLocks {
    const val LOCK_SUFFIX = ".lock"

    private val claims = HashMap<Path, Holder>()

    class Holder private constructor(
        private val lockFile: Path,
        private val channel: FileChannel,
        private val lock: FileLock,
    ) : AutoCloseable {
        override fun close() {
            runCatching { lock.release() }
            runCatching { channel.close() }
            runCatching { Files.deleteIfExists(lockFile) }
        }

        companion object {
            fun acquire(lockFile: Path): Holder? {
                val channel = try {
                    FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                } catch (e: Exception) {
                    thisLogger().debug("Cannot open temp root lock $lockFile", e)
                    return null
                }
                val lock = try {
                    channel.tryLock()
                } catch (e: Exception) {
                    thisLogger().debug("Cannot lock temp root lock $lockFile", e)
                    null
                }
                if (lock == null) {
                    runCatching { channel.close() }
                    return null
                }
                return Holder(lockFile, channel, lock)
            }
        }
    }

    fun lockFileFor(dir: Path): Path =
        dir.resolveSibling(dir.fileName.toString() + LOCK_SUFFIX)

    fun dirFor(lockFile: Path): Path =
        lockFile.resolveSibling(lockFile.fileName.toString().removeSuffix(LOCK_SUFFIX))

    @Synchronized
    fun claim(dir: Path) {
        val key = dir.normalize()
        if (key in claims) return
        val lockFile = lockFileFor(key)
        val holder = Holder.acquire(lockFile)
        if (holder == null) {
            runCatching { Files.deleteIfExists(lockFile) }
            return
        }
        claims[key] = holder
    }

    @Synchronized
    fun release(dir: Path) {
        claims.remove(dir.normalize())?.close()
    }

    @Synchronized
    fun isClaimed(dir: Path): Boolean = dir.normalize() in claims

    fun discard(dir: Path): Boolean {
        release(dir)
        return deleteTree(dir)
    }

    fun deleteTree(dir: Path): Boolean {
        return Files.exists(dir) && runCatching {
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
            !Files.exists(dir)
        }.getOrDefault(false)
    }

    @Synchronized
    fun releaseAll() {
        claims.values.forEach { it.close() }
        claims.clear()
    }
}
