package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioConstants
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class CpioHardLinks {

    private data class Key(val deviceMaj: Long, val deviceMin: Long, val inode: Long)

    private val extracted = mutableMapOf<Key, Path>()
    private val pending = mutableMapOf<Key, MutableList<Path>>()

    fun extractRegularFile(entry: CpioArchiveEntry, path: Path, write: () -> Unit) {
        val key = keyOf(entry)
        if (key == null) {
            write()
            return
        }
        if (entry.size > 0) {
            write()
            extracted[key] = path
            pending.remove(key)?.forEach { link(it, path) }
            return
        }
        val source = extracted[key]
        if (source != null) {
            link(path, source)
        } else {
            write()
            pending.getOrPut(key) { mutableListOf() }.add(path)
        }
    }

    private fun keyOf(entry: CpioArchiveEntry): Key? {
        if (!entry.isRegularFile || entry.numberOfLinks <= 1) return null
        return when (entry.format) {
            CpioConstants.FORMAT_NEW, CpioConstants.FORMAT_NEW_CRC -> Key(entry.deviceMaj, entry.deviceMin, entry.inode)
            else -> Key(entry.device, 0, entry.inode)
        }
    }

    private fun link(path: Path, source: Path) {
        Files.deleteIfExists(path)
        try {
            Files.createLink(path, source)
        } catch (_: Exception) {
            Files.copy(source, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }
}
