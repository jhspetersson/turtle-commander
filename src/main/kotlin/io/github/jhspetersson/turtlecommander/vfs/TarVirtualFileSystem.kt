package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class TarFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("tar")
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem {
        return TarVirtualFileSystem(
            archivePath,
            inputStreamFactory = { Files.newInputStream(it) },
            outputStreamFactory = { Files.newOutputStream(it) },
            openProgress = openProgress,
        )
    }
}

/**
 * VFS for tar archives (plain and, through the [inputStreamFactory]/[outputStreamFactory]
 * supplied by the Gz/Bz2/Xz providers, the compressed `.tar.gz` / `.tar.bz2` / `.tar.xz`
 * variants).
 *
 * Like [ZipExtractVirtualFileSystem], content materialises lazily: opening streams the
 * entry headers once and writes only sparse size-correct stubs; real bytes land on a stub
 * when something reads it. Tar has no random-access index, so a single [materialize]
 * re-streams the archive up to the entry (identified by its ordinal, which is immune to
 * duplicate names), and [materializeAllForRepack] fills every remaining stub in one pass
 * before [repack] overwrites the archive. Symlinks and hard links are resolved eagerly at
 * open: they carry no body, and a hard link created against a stub inode would keep
 * pointing at the zeros after the target's atomic replacement.
 */
class TarVirtualFileSystem(
    override val archivePath: Path,
    private val inputStreamFactory: (Path) -> InputStream,
    private val outputStreamFactory: ((Path) -> OutputStream)? = null,
    openProgress: VfsOpenProgress? = null,
) : AbstractTempDirVirtualFileSystem("turtle-tar-", openProgress) {

    /**
     * Symlink entries that could not be materialized on the local filesystem (typically because
     * Windows requires SeCreateSymbolicLinkPrivilege). Keyed by the entry name inside the archive
     * so repack can re-emit them even though the extracted temp dir has nothing to walk.
     * Value is the stored linkName.
     */
    private val unresolvedSymlinks = linkedMapOf<String, String>()

    /**
     * A still-unmaterialised stub: the ordinal of its entry in the archive stream plus the
     * stub's size and mtime as created. The metadata keeps materialisation invisible to the
     * base class's dirty-tracking fingerprint and detects stubs overwritten or deleted by
     * external writes — in that case the user's content wins and the record is dropped.
     */
    private class PendingEntry(
        val ordinal: Int,
        val stubSize: Long,
        val stubMtime: FileTime,
    )

    private val pendingEntries = ConcurrentHashMap<Path, PendingEntry>()

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        unresolvedSymlinks.clear()
        pendingEntries.clear()
        inputStreamFactory(archivePath).use { raw ->
            TarArchiveInputStream(raw).use { tar ->
                var ordinal = -1
                var entry = tar.nextEntry
                while (entry != null) {
                    ordinal++
                    if (progress.isCancelled) break
                    progress.onEntry(0, 0, entry.name)
                    val entryPath = resolveEntryPath(into, entry.name)
                    if (entryPath == null) {
                        entry = tar.nextEntry
                        continue
                    }
                    try {
                        if (entry.isSymbolicLink) {
                            val linkTarget = entry.linkName
                            Files.createDirectories(entryPath.parent)
                            try {
                                Files.createSymbolicLink(entryPath, Path.of(linkTarget))
                            } catch (_: Exception) {
                                // Symlink creation may fail (e.g. Windows without privileges).
                                // Record it so repack can still re-emit the original entry and
                                // users don't silently lose symlinks when they edit a tar.
                                unresolvedSymlinks[normalizeRelativeName(into, entryPath)] = linkTarget
                            }
                            setEntryMtime(entryPath, entry)
                        } else if (entry.isDirectory) {
                            Files.createDirectories(entryPath)
                            setEntryMtime(entryPath, entry)
                        } else if (entry.isLink) {
                            // Hard link: the target precedes the link in any well-formed tar,
                            // so it is already recorded — materialise it now and link against
                            // the real inode.
                            val linkTarget = into.resolve(entry.linkName)
                            Files.createDirectories(entryPath.parent)
                            materialize(linkTarget)
                            try {
                                Files.createLink(entryPath, linkTarget)
                            } catch (_: Exception) {
                                // Hard link may fail, try copying the target instead
                                if (Files.exists(linkTarget)) {
                                    Files.copy(linkTarget, entryPath)
                                }
                            }
                            setEntryMtime(entryPath, entry)
                        } else {
                            Files.createDirectories(entryPath.parent)
                            createSparseStub(entryPath, entry.size)
                            setEntryMtime(entryPath, entry)
                            pendingEntries[entryPath.normalize()] = PendingEntry(
                                ordinal,
                                stubSize = Files.size(entryPath),
                                stubMtime = Files.getLastModifiedTime(entryPath),
                            )
                        }
                    } catch (e: Exception) {
                        thisLogger().debug("Failed to stub tar entry: ${entry.name}", e)
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }

    private fun setEntryMtime(path: Path, entry: TarArchiveEntry) {
        if (entry.lastModifiedDate != null) {
            Files.setLastModifiedTime(path, FileTime.fromMillis(entry.lastModifiedDate.time))
        }
    }

    /**
     * Stream the bytes for [path] by walking the archive up to the recorded ordinal and
     * replacing the sparse stub. A no-op if [path] isn't pending — and a deliberate no-op
     * (record dropped) when the stub was overwritten or deleted by an external write.
     * Tar has no index, so this costs one pass over the archive (decompressing it for the
     * `.tar.gz` family); the open-time saving still wins unless every entry is read.
     */
    @Synchronized
    override fun materialize(path: Path) {
        val normalized = path.normalize()
        val pending = pendingEntries[normalized] ?: return
        try {
            inputStreamFactory(archivePath).use { raw ->
                TarArchiveInputStream(raw).use { tar ->
                    var ordinal = -1
                    var entry = tar.nextEntry
                    while (entry != null) {
                        ordinal++
                        if (ordinal == pending.ordinal) {
                            materializeFromCurrentEntry(normalized, tar)
                            return
                        }
                        entry = tar.nextEntry
                    }
                }
            }
            // Stream ended before the recorded ordinal: the archive shrank underneath us.
            // Drop the record so a repack omits the stub instead of writing zeros.
            thisLogger().warn("Tar entry vanished from $archivePath, dropping stub: $normalized")
            pendingEntries.remove(normalized)
        } catch (e: Exception) {
            thisLogger().warn("Failed to materialize tar entry $normalized: ${e.message}")
            throw e
        }
    }

    /**
     * Copies the stream's current entry body onto [path]'s stub, with the same guards as
     * the zip implementation: skip (and drop) externally-modified stubs, write via a
     * sibling temp file + atomic move, and stamp the stub's exact mtime so materialisation
     * never flips the dirty-tracking fingerprint.
     */
    private fun materializeFromCurrentEntry(path: Path, tar: TarArchiveInputStream) {
        val pending = pendingEntries[path] ?: return
        val current = runCatching {
            Files.size(path) to Files.getLastModifiedTime(path)
        }.getOrNull()
        if (current == null || current.first != pending.stubSize || current.second != pending.stubMtime) {
            pendingEntries.remove(path)
            return
        }
        val tmp = Files.createTempFile(path.parent, ".tc-materializing-", ".tmp")
        try {
            Files.copy(tar, tmp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
        Files.setLastModifiedTime(path, pending.stubMtime)
        pendingEntries.remove(path)
    }

    /**
     * [renameFile] in the base class moves the stub on disk; keep the pending map keyed
     * by the stub's new location so [materialize] (and the repack below) stream the bytes
     * to where the stub actually lives now.
     */
    @Synchronized
    override fun onRenamed(source: Path, target: Path) {
        pendingEntries.remove(source.normalize())?.let { pending ->
            pendingEntries[target.normalize()] = pending
        }
    }

    /**
     * Fill every still-pending stub in a single pass over the archive before [repack]
     * overwrites it. Entries whose bytes can't be read (or that the stream no longer
     * reaches) are dropped from the temp dir — and thus from the repacked archive — the
     * same outcome the eager extractor produced for unextractable entries.
     */
    @Synchronized
    private fun materializeAllForRepack() {
        if (pendingEntries.isEmpty()) return
        val byOrdinal = HashMap<Int, Path>()
        for ((path, pending) in pendingEntries) byOrdinal[pending.ordinal] = path
        try {
            inputStreamFactory(archivePath).use { raw ->
                TarArchiveInputStream(raw).use { tar ->
                    var ordinal = -1
                    var entry = tar.nextEntry
                    while (entry != null && byOrdinal.isNotEmpty()) {
                        ordinal++
                        val path = byOrdinal.remove(ordinal)
                        if (path != null) {
                            try {
                                materializeFromCurrentEntry(path, tar)
                            } catch (e: Exception) {
                                thisLogger().warn("Dropping unreadable tar entry from repack: $path (${e.message})")
                                runCatching { Files.deleteIfExists(path) }
                                pendingEntries.remove(path)
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Bulk tar materialisation failed for $archivePath: ${e.message}")
        }
        for (path in byOrdinal.values) {
            thisLogger().warn("Dropping unreachable tar entry from repack: $path")
            runCatching { Files.deleteIfExists(path) }
            pendingEntries.remove(path)
        }
    }

    override fun repack(from: Path) {
        materializeAllForRepack()
        val outFactory = outputStreamFactory ?: { path -> Files.newOutputStream(path) }
        outFactory(archivePath).use { raw ->
            TarArchiveOutputStream(raw).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                forEachArchiveEntry(from) { path, relativeName ->
                    if (Files.isSymbolicLink(path)) {
                        val target = Files.readSymbolicLink(path)
                        val entry = TarArchiveEntry(relativeName, TarArchiveEntry.LF_SYMLINK)
                        entry.linkName = target.toString().replace('\\', '/')
                        tar.putArchiveEntry(entry)
                        tar.closeArchiveEntry()
                    } else {
                        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                        val entry = TarArchiveEntry(path, relativeName)
                        entry.size = if (attrs.isDirectory) 0 else attrs.size()
                        entry.modTime = Date(attrs.lastModifiedTime().toMillis())
                        tar.putArchiveEntry(entry)
                        if (!attrs.isDirectory) {
                            Files.copy(path, tar)
                        }
                        tar.closeArchiveEntry()
                    }
                }
                // Re-emit symlink entries that we could not materialize locally. Without this,
                // editing any file inside a tar on Windows silently strips every symlink.
                for ((relativeName, linkTarget) in unresolvedSymlinks) {
                    val entry = TarArchiveEntry(relativeName, TarArchiveEntry.LF_SYMLINK)
                    entry.linkName = linkTarget
                    tar.putArchiveEntry(entry)
                    tar.closeArchiveEntry()
                }
            }
        }
    }

    private fun normalizeRelativeName(base: Path, path: Path): String =
        base.relativize(path).toString().replace('\\', '/')

    /** Test-only: records a synthetic unresolved symlink so repack emits it. */
    internal fun markUnresolvedSymlinkForTest(relativeName: String, linkTarget: String) {
        unresolvedSymlinks[relativeName] = linkTarget
    }
}
