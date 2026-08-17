package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import io.github.jhspetersson.turtlecommander.util.formatPosixMode
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioConstants
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

class CpioFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("cpio")
    }

    override fun supportsExtension(ext: String): Boolean = ext in ARCHIVE_EXTENSIONS

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem =
        CpioVirtualFileSystem(archivePath, openProgress)
}

class CpioVirtualFileSystem(
    override val archivePath: Path,
    openProgress: VfsOpenProgress? = null,
) : AbstractTempDirVirtualFileSystem("turtle-cpio-", openProgress) {

    private data class CpioEntryMetadata(
        val mode: Long,
        val uid: Long,
        val gid: Long,
    )

    private val entryMetadata = mutableMapOf<String, CpioEntryMetadata>()
    private val unresolvedSymlinks = linkedMapOf<String, String>()

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        if (!sniffIsCpio()) throw SilentVfsOpenException()
        entryMetadata.clear()
        unresolvedSymlinks.clear()
        Files.newInputStream(archivePath).buffered().use { raw ->
            CpioArchiveInputStream(raw).use { cpio ->
                var entry = cpio.nextEntry
                while (entry != null) {
                    if (progress.isCancelled) break
                    val name = entry.name.removePrefix("./").removePrefix("/")
                    progress.onEntry(0, 0, name)
                    if (name.isEmpty() || name == CpioConstants.CPIO_TRAILER) {
                        entry = cpio.nextEntry
                        continue
                    }
                    val entryPath = resolveEntryPath(into, name)
                    if (entryPath == null) {
                        entry = cpio.nextEntry
                        continue
                    }
                    val relativeName = normalizeRelativeName(into, entryPath)
                    entryMetadata[relativeName] = CpioEntryMetadata(entry.mode, entry.getUID(), entry.getGID())
                    try {
                        when {
                            entry.isDirectory -> Files.createDirectories(entryPath)
                            entry.isSymbolicLink -> {
                                val target = cpio.readBytes().toString(Charsets.UTF_8)
                                if (symlinkTargetEscapes(into, entryPath, target)) {
                                    thisLogger().warn("Skipping cpio symlink escaping the archive root: $name -> $target")
                                } else {
                                    entryPath.parent?.let { Files.createDirectories(it) }
                                    try {
                                        Files.createSymbolicLink(entryPath, Path.of(target))
                                    } catch (_: Exception) {
                                        unresolvedSymlinks[relativeName] = target
                                    }
                                }
                            }
                            else -> {
                                Files.createDirectories(entryPath.parent)
                                Files.copy(cpio, entryPath)
                            }
                        }
                        val mtime = entry.lastModifiedDate?.time
                        if (mtime != null && mtime > 0 && Files.exists(entryPath)) {
                            Files.setLastModifiedTime(entryPath, FileTime.fromMillis(mtime))
                        }
                    } catch (e: Exception) {
                        thisLogger().debug("Failed to extract cpio entry: $name", e)
                    }
                    entry = cpio.nextEntry
                }
            }
        }
    }

    override fun onRenamed(source: Path, target: Path) {
        val oldName = normalizeRelativeName(tempDir, source)
        val newName = normalizeRelativeName(tempDir, target)
        entryMetadata.remove(oldName)?.let { entryMetadata[newName] = it }
        unresolvedSymlinks.remove(oldName)?.let { unresolvedSymlinks[newName] = it }
    }

    override fun repack(from: Path) {
        repackAtomically(archivePath) { target ->
            Files.newOutputStream(target).buffered().use { raw ->
                CpioArchiveOutputStream(raw).use { cpio ->
                    forEachArchiveEntry(from) { path, relativeName ->
                        val metadata = entryMetadata[relativeName]
                        if (Files.isSymbolicLink(path)) {
                            val linkTarget = Files.readSymbolicLink(path).toString().replace('\\', '/')
                            writeSymlinkEntry(cpio, relativeName, linkTarget)
                        } else {
                            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                            val entry = CpioArchiveEntry(relativeName)
                            if (attrs.isDirectory) {
                                entry.size = 0
                                entry.mode = metadata?.mode?.takeIf { it.isType(CpioConstants.C_ISDIR) }
                                    ?: DEFAULT_DIR_MODE
                            } else {
                                entry.size = attrs.size()
                                entry.mode = metadata?.mode?.takeIf { it.isType(CpioConstants.C_ISREG) }
                                    ?: DEFAULT_FILE_MODE
                            }
                            entry.setUID(metadata?.uid ?: 0)
                            entry.setGID(metadata?.gid ?: 0)
                            entry.time = attrs.lastModifiedTime().toMillis() / 1000
                            cpio.putArchiveEntry(entry)
                            if (!attrs.isDirectory) {
                                Files.copy(path, cpio)
                            }
                            cpio.closeArchiveEntry()
                        }
                    }
                    for ((relativeName, linkTarget) in unresolvedSymlinks) {
                        writeSymlinkEntry(cpio, relativeName, linkTarget)
                    }
                }
            }
        }
    }

    private fun writeSymlinkEntry(cpio: CpioArchiveOutputStream, relativeName: String, linkTarget: String) {
        val body = linkTarget.toByteArray(Charsets.UTF_8)
        val entry = CpioArchiveEntry(relativeName)
        entry.mode = entryMetadata[relativeName]?.mode?.takeIf { it.isType(CpioConstants.C_ISLNK) }
            ?: DEFAULT_SYMLINK_MODE
        entry.size = body.size.toLong()
        entry.setUID(entryMetadata[relativeName]?.uid ?: 0)
        entry.setGID(entryMetadata[relativeName]?.gid ?: 0)
        cpio.putArchiveEntry(entry)
        cpio.write(body)
        cpio.closeArchiveEntry()
    }

    override fun entryMetadata(path: Path): ArchiveEntryMetadata? {
        val metadata = entryMetadata[normalizeRelativeName(tempDir, path)] ?: return null
        return ArchiveEntryMetadata(
            owner = metadata.uid.toString(),
            group = metadata.gid.toString(),
            permissions = formatPosixMode(metadata.mode.toInt()),
        )
    }

    private fun Long.isType(type: Int): Boolean = (this and TYPE_MASK) == type.toLong()

    private fun normalizeRelativeName(base: Path, path: Path): String =
        base.relativize(path).toString().replace('\\', '/')

    private fun sniffIsCpio(): Boolean {
        val header = ByteArray(6)
        val read = try {
            Files.newInputStream(archivePath).use { it.readNBytes(header, 0, header.size) }
        } catch (_: Exception) {
            return false
        }
        if (read >= 2) {
            val leMagic = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8)
            val beMagic = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
            if (leMagic == CpioConstants.MAGIC_OLD_BINARY || beMagic == CpioConstants.MAGIC_OLD_BINARY) {
                return true
            }
        }
        if (read < header.size) return false
        return when (String(header, Charsets.US_ASCII)) {
            CpioConstants.MAGIC_NEW, CpioConstants.MAGIC_NEW_CRC, CpioConstants.MAGIC_OLD_ASCII -> true
            else -> false
        }
    }

    companion object {
        private const val TYPE_MASK = 0xF000L
        private val DEFAULT_FILE_MODE = (CpioConstants.C_ISREG or 0x1A4).toLong()
        private val DEFAULT_DIR_MODE = (CpioConstants.C_ISDIR or 0x1ED).toLong()
        private val DEFAULT_SYMLINK_MODE = (CpioConstants.C_ISLNK or 0x1FF).toLong()
    }
}
