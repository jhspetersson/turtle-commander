package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.sync.Mutex
import java.util.WeakHashMap

internal object VfsWriteLocks {
    private val locks = WeakHashMap<VirtualFileSystem, Mutex>()

    fun of(vfs: VirtualFileSystem): Mutex = synchronized(locks) { locks.getOrPut(vfs) { Mutex() } }
}
