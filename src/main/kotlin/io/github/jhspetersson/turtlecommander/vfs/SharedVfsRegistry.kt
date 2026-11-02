package io.github.jhspetersson.turtlecommander.vfs

import java.nio.file.Path
import java.util.concurrent.CountDownLatch

object SharedVfsRegistry {
    private class Entry(val vfs: VirtualFileSystem) {
        var refCount = 1
        val listeners = LinkedHashSet<() -> Unit>()
    }

    private val lock = Any()
    private val entries = HashMap<Path, Entry>()
    private val closing = HashMap<Path, CountDownLatch>()

    private fun canonicalKey(archivePath: Path): Path =
        try {
            archivePath.toRealPath()
        } catch (_: Exception) {
            archivePath.toAbsolutePath().normalize()
        }

    fun acquire(archivePath: Path, openProgress: VfsOpenProgress? = null): VirtualFileSystem {
        val key = canonicalKey(archivePath)
        while (true) {
            shareOrAwaitClose(key)?.let { return it }
            val created = VirtualFileSystemRegistry.create(archivePath, openProgress)
            val winner = synchronized(lock) {
                val existing = entries[key]
                when {
                    existing != null -> existing.also { it.refCount++ }.vfs
                    key !in closing -> {
                        entries[key] = Entry(created)
                        return created
                    }
                    else -> null
                }
            }
            runCatching { created.close() }
            if (winner != null) return winner
        }
    }

    private fun shareOrAwaitClose(key: Path): VirtualFileSystem? {
        while (true) {
            val pending = synchronized(lock) {
                entries[key]?.let {
                    it.refCount++
                    return it.vfs
                }
                closing[key] ?: return null
            }
            pending.await()
        }
    }

    fun release(vfs: VirtualFileSystem): Boolean {
        var closingKey: Path? = null
        val done = CountDownLatch(1)
        synchronized(lock) {
            val entry = entries.entries.firstOrNull { it.value.vfs === vfs } ?: return false
            if (--entry.value.refCount <= 0) {
                entries.remove(entry.key)
                closing[entry.key] = done
                closingKey = entry.key
            }
        }
        val key = closingKey ?: return true
        try {
            vfs.close()
        } finally {
            synchronized(lock) { closing.remove(key) }
            done.countDown()
        }
        return true
    }

    fun addMutationListener(vfs: VirtualFileSystem, listener: () -> Unit) {
        synchronized(lock) {
            entries.values.firstOrNull { it.vfs === vfs }?.listeners?.add(listener)
        }
    }

    fun removeMutationListener(vfs: VirtualFileSystem, listener: () -> Unit) {
        synchronized(lock) {
            entries.values.firstOrNull { it.vfs === vfs }?.listeners?.remove(listener)
        }
    }

    fun notifyMutated(vfs: VirtualFileSystem, except: (() -> Unit)? = null) {
        val toNotify = synchronized(lock) {
            entries.values.firstOrNull { it.vfs === vfs }?.listeners?.toList().orEmpty()
        }
        for (listener in toNotify) {
            if (listener !== except) listener()
        }
    }
}
