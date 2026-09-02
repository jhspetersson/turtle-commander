package io.github.jhspetersson.turtlecommander.vfs

import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.UnsupportedRarVersionException
import com.github.junrar.rarfile.FileHeader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.Messages
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

class RarFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("rar", "cbr")
    }

    override fun supportsExtension(ext: String): Boolean = ext in ARCHIVE_EXTENSIONS

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem =
        RarVirtualFileSystem(archivePath, openProgress)
}

/**
 * Read-only VFS for RAR archives (`.rar`, `.cbr`), backed by junrar with a `7z` fallback
 * for archives it can't read in-process (newer format revisions, some encrypted archives).
 *
 * Like [IsoVirtualFileSystem], content is **materialised lazily** wherever the format
 * allows it. junrar parses the entire header block when the archive is opened, so
 * [extract] only writes the directory tree plus one sparse stub per file, sized and dated
 * from its header. Real bytes are streamed on demand via [OpenVfsRegistry.materializeIfNeeded],
 * which the file-open, copy, hash, and content-search paths all call. Browsing a 4 GB RAR
 * therefore costs a header scan, not 4 GB of temp space.
 *
 * **Solid archives are extracted eagerly instead.** A solid RAR compresses its members as
 * one continuous stream, so an entry can only be decompressed by replaying everything
 * before it — junrar's `extractFile` does exactly that, running `skipFile` over each
 * preceding header on every call. Materialising on demand would turn one linear unpack
 * into a quadratic pile of them as soon as the user reads more than a file or two, so for
 * solid archives the original single-pass extract is the better deal. The `7z` fallback
 * stays eager for the same practical reason: it can only extract wholesale.
 */
class RarVirtualFileSystem(
    override val archivePath: Path,
    openProgress: VfsOpenProgress? = null,
) : AbstractTempDirVirtualFileSystem("turtle-rar-", openProgress) {

    override val isReadOnly: Boolean get() = true

    /**
     * The header holding a stub's bytes, plus the stub's mtime as created. [materialize]
     * restores that exact mtime after streaming so a listing keeps showing the archived
     * date rather than the moment the user happened to read the file.
     */
    private class PendingEntry(val header: FileHeader, val stubMtime: FileTime)

    /**
     * Map from stub path (normalised) to its pending record. Populated by [extract] and
     * drained by [materialize]; once a path is gone from the map the stub holds real
     * content and further [materialize] calls for it are no-ops. Empty for the whole
     * lifetime of an eagerly-extracted (solid, or `7z`-fallback) archive. Concurrent
     * because content search and parallel hash actions can race.
     */
    private val pendingEntries = ConcurrentHashMap<Path, PendingEntry>()

    /**
     * Kept open for the VFS lifetime so [materialize] can stream individual entries
     * without re-parsing the header block on every call. Set by [junrarExtract] only on
     * the lazy path, and closed by [reset], [extract]'s re-entry, and [close]; null
     * whenever the temp dir was populated eagerly.
     */
    private var reader: Archive? = null

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        if (!sniffIsRar()) throw SilentVfsOpenException()
        // flush() re-extracts into a fresh temp dir: drop the reader and the stub
        // bookkeeping of the previous round first.
        closeReader()
        pendingEntries.clear()

        try {
            junrarExtract(into, password = null, progress)
            return
        } catch (_: PasswordRequired) {
        } catch (_: UnsupportedRarVersionException) {
            reset(into)
            sevenZipFallback(
                into, progress, password = null,
                missingBinaryMessage = "This RAR archive uses a format revision that can't be read " +
                    "in-process. ${System7z.INSTALL_HINT}",
            )
            return
        } catch (e: RarException) {
            if (!isPasswordIssue(e)) throw IOException("Failed to read RAR archive: ${e.message}", e)
        }

        val password = promptPassword()
            ?: throw IOException("Password required to open ${archivePath.fileName}.")
        try {
            reset(into)
            junrarExtract(into, password, progress)
        } catch (e: Exception) {
            reset(into)
            sevenZipFallback(
                into, progress, password,
                missingBinaryMessage = "This encrypted RAR couldn't be read in-process. " +
                    System7z.INSTALL_HINT,
            )
        }
    }

    /**
     * Walk the header block once, writing directories eagerly and each file either as a
     * sparse stub (non-solid: [materialize] streams it later) or as fully-extracted bytes
     * (solid). The archive handle is handed over to [reader] on the lazy path and closed
     * here on every other path, including any thrown exception.
     */
    private fun junrarExtract(into: Path, password: String?, progress: VfsOpenProgress) {
        val archive = if (password == null) {
            Archive(archivePath.toFile())
        } else {
            Archive(archivePath.toFile(), password)
        }
        var handedOver = false
        try {
            if (password == null && archive.isPasswordProtected) throw PasswordRequired()
            val headers = archive.fileHeaders
            val lazily = !isSolid(archive, headers)
            for (header in headers) {
                if (progress.isCancelled) break
                if (password == null && header.isEncrypted) throw PasswordRequired()
                val rawName = header.fileName ?: continue
                progress.onEntry(0, 0, rawName)
                val target = resolveEntryPath(into, rawName.replace('\\', '/')) ?: continue
                if (header.isDirectory) {
                    Files.createDirectories(target)
                    continue
                }
                target.parent?.let { Files.createDirectories(it) }
                if (lazily) {
                    stubEntry(target, header)
                } else {
                    Files.newOutputStream(target).use { out -> archive.extractFile(header, out) }
                    runCatching { header.lastModifiedTime?.let { Files.setLastModifiedTime(target, it) } }
                }
            }
            if (lazily) {
                // Even a cancelled stub pass hands the handle over: the partial VFS is
                // closed by the caller, and close() is what releases the archive.
                reader = archive
                handedOver = true
            }
        } finally {
            if (!handedOver) runCatching { archive.close() }
        }
    }

    /**
     * True when any entry can only be decompressed by replaying its predecessors. This is
     * deliberately the same condition junrar's own `extractFile` tests before it starts
     * calling `skipFile` — the archive-wide flag in the main header, or the per-entry flag
     * — so the lazy path is taken exactly when a materialise costs a plain seek.
     */
    private fun isSolid(archive: Archive, headers: List<FileHeader>): Boolean {
        if (runCatching { archive.mainHeader?.isSolid }.getOrNull() == true) return true
        return headers.any { it.isSolid }
    }

    /** Placeholder of the recorded size and date, registered for later [materialize]. */
    private fun stubEntry(target: Path, header: FileHeader) {
        try {
            createSparseStub(target, header.fullUnpackSize)
            runCatching { header.lastModifiedTime?.let { Files.setLastModifiedTime(target, it) } }
            pendingEntries[target.normalize()] = PendingEntry(header, Files.getLastModifiedTime(target))
        } catch (e: Exception) {
            thisLogger().debug("Failed to stub rar entry: ${header.fileName}", e)
        }
    }

    /**
     * Stream the actual bytes for [path] out of the archive and replace its sparse stub.
     * A no-op if [path] isn't one of our stubs, has already been materialised, or the temp
     * dir was populated eagerly. Synchronised because junrar's [Archive] is single-threaded
     * and two consumers can ask for content concurrently (content search, parallel hashes);
     * the monitor is held only for the one entry's I/O.
     */
    @Synchronized
    override fun materialize(path: Path) {
        val normalized = path.normalize()
        val pending = pendingEntries[normalized] ?: return
        val archive = reader ?: return
        try {
            // Write into a sibling temp file then atomic-move, so a partial write never
            // leaves a half-streamed file at the canonical path. ATOMIC_MOVE within the
            // same temp dir is supported on every platform we run on.
            val tmp = Files.createTempFile(normalized.parent, ".tc-materializing-", ".tmp")
            try {
                Files.newOutputStream(tmp).use { out -> archive.extractFile(pending.header, out) }
                Files.move(tmp, normalized, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
            Files.setLastModifiedTime(normalized, pending.stubMtime)
            pendingEntries.remove(normalized)
        } catch (e: Exception) {
            thisLogger().warn("Failed to materialize RAR entry $normalized: ${e.message}")
            throw e
        }
    }

    private fun closeReader() {
        try {
            reader?.close()
        } catch (e: Exception) {
            thisLogger().debug("Failed to close RAR archive for $archivePath: ${e.message}")
        }
        reader = null
    }

    override fun close() {
        closeReader()
        pendingEntries.clear()
        super.close()
    }

    private fun sevenZipFallback(
        into: Path,
        progress: VfsOpenProgress,
        password: String?,
        missingBinaryMessage: String,
    ) {
        if (System7z.findBinary() == null) {
            throw System7zUnavailableException(missingBinaryMessage)
        }
        try {
            System7z.extract(archivePath, into, progress, password)
        } catch (e: IOException) {
            if (password == null && looksEncrypted(e)) {
                val pw = promptPassword()
                    ?: throw IOException("Password required to open ${archivePath.fileName}.")
                reset(into)
                System7z.extract(archivePath, into, progress, pw)
            } else {
                throw e
            }
        }
    }

    private class PasswordRequired : RuntimeException()

    private fun sniffIsRar(): Boolean {
        val header = ByteArray(8)
        val read = try {
            Files.newInputStream(archivePath).use { it.readNBytes(header, 0, header.size) }
        } catch (_: Exception) {
            return false
        }
        return (read >= RAR5_SIGNATURE.size && header.startsWith(RAR5_SIGNATURE)) ||
            (read >= RAR4_SIGNATURE.size && header.startsWith(RAR4_SIGNATURE))
    }

    private fun ByteArray.startsWith(signature: ByteArray): Boolean {
        for (i in signature.indices) {
            if (this[i] != signature[i]) return false
        }
        return true
    }

    private fun isPasswordIssue(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is UnsupportedRarEncryptedException) return true
            val msg = current.message?.lowercase().orEmpty()
            if ("password" in msg || "encrypt" in msg) return true
            current = current.cause
        }
        return false
    }

    private fun looksEncrypted(e: Throwable): Boolean {
        val text = generateSequence(e) { it.cause }
            .joinToString("\n") { it.message.orEmpty() }
            .lowercase()
        return "password" in text || "encrypt" in text
    }

    private fun promptPassword(): String? {
        var result: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            result = Messages.showPasswordDialog(
                "Enter password for ${archivePath.fileName}:",
                "Encrypted RAR Archive",
            )
        }
        return result?.takeIf { it.isNotEmpty() }
    }

    /**
     * Wipe the temp dir for a retry with different credentials or a different extractor.
     * The stubs being deleted are the only thing [pendingEntries] and [reader] describe,
     * so both go with them — otherwise a later [materialize] would stream bytes onto a
     * path the fallback extractor now owns.
     */
    private fun reset(dir: Path) {
        closeReader()
        pendingEntries.clear()
        dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
    }

    companion object {
        private val RAR4_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        private val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    }
}
