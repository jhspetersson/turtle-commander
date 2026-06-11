package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.*

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

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        unresolvedSymlinks.clear()
        inputStreamFactory(archivePath).use { raw ->
            TarArchiveInputStream(raw).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
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
                                Files.createSymbolicLink(entryPath, java.nio.file.Paths.get(linkTarget))
                            } catch (_: Exception) {
                                // Symlink creation may fail (e.g. Windows without privileges).
                                // Record it so repack can still re-emit the original entry and
                                // users don't silently lose symlinks when they edit a tar.
                                unresolvedSymlinks[normalizeRelativeName(into, entryPath)] = linkTarget
                            }
                        } else if (entry.isDirectory) {
                            Files.createDirectories(entryPath)
                        } else if (entry.isLink) {
                            // Hard link
                            val linkTarget = into.resolve(entry.linkName)
                            Files.createDirectories(entryPath.parent)
                            try {
                                Files.createLink(entryPath, linkTarget)
                            } catch (_: Exception) {
                                // Hard link may fail, try copying the target instead
                                if (Files.exists(linkTarget)) {
                                    Files.copy(linkTarget, entryPath)
                                }
                            }
                        } else {
                            Files.createDirectories(entryPath.parent)
                            Files.copy(tar, entryPath)
                        }
                        if (entry.lastModifiedDate != null) {
                            Files.setLastModifiedTime(entryPath, FileTime.fromMillis(entry.lastModifiedDate.time))
                        }
                    } catch (e: Exception) {
                        thisLogger().debug("Failed to extract tar entry: ${entry.name}", e)
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }

    override fun repack(from: Path) {
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
