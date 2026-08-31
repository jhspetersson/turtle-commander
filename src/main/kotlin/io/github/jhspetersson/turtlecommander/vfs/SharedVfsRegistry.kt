package io.github.jhspetersson.turtlecommander.vfs

import java.nio.file.Path

object SharedVfsRegistry {
    private class Entry(val vfs: VirtualFileSystem) {
        var refCount = 1
        val listeners = LinkedHashSet<() -> Unit>()
    }

    private val lock = Any()
    private val entries = HashMap<Path, Entry>()

    private fun canonicalKey(archivePath: Path): Path =
        try {
            archivePath.toRealPath()
        } catch (_: Exception) {
            archivePath.toAbsolutePath().normalize()
        }

    fun acquire(archivePath: Path, openProgress: VfsOpenProgress? = null): VirtualFileSystem {
        val key = canonicalKey(archivePath)
        synchronized(lock) {
            entries[key]?.let {
                it.refCount++
                return it.vfs
            }
        }
        val created = VirtualFileSystemRegistry.create(archivePath, openProgress)
        synchronized(lock) {
            val existing = entries[key]
            if (existing == null) {
                entries[key] = Entry(created)
                return created
            }
            existing.refCount++
            runCatching { created.close() }
            return existing.vfs
        }
    }

    fun release(vfs: VirtualFileSystem): Boolean {
        var toClose: VirtualFileSystem? = null
        synchronized(lock) {
            val entry = entries.entries.firstOrNull { it.value.vfs === vfs } ?: return false
            if (--entry.value.refCount <= 0) {
                entries.remove(entry.key)
                toClose = vfs
            }
        }
        toClose?.close()
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
