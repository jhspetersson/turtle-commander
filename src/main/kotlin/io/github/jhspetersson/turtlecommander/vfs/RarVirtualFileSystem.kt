package io.github.jhspetersson.turtlecommander.vfs

import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.UnsupportedRarV5Exception
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class RarFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        // ".cbr" is a comic-book archive that is just a RAR.
        val ARCHIVE_EXTENSIONS = setOf("rar", "cbr")
    }

    override fun supportsExtension(ext: String): Boolean = ext in ARCHIVE_EXTENSIONS

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem =
        RarVirtualFileSystem(archivePath, openProgress)
}

/**
 * Read-only VFS for RAR archives. RAR has no open compressor (RARLAB's unrar license permits
 * decompression only), so the archive is never written back — [isReadOnly] is `true` and the
 * base class skips repack on flush/close.
 *
 * Decoder dispatch is driven by the archive signature, sniffed up front:
 *
 *  - **RAR4** (classic) is unpacked in-process with the pure-Java **junrar** library — no
 *    external tooling needed.
 *  - **RAR5** has no pure-Java reader, so it's delegated to a system **7-Zip** binary. When
 *    none is installed the user gets an actionable error pointing at 7-Zip/p7zip.
 *
 * Encryption is handled lazily: if junrar reports the entry list or a file is password
 * protected (or 7-Zip reports an encryption failure), the user is prompted for a password and
 * the extraction is retried. If junrar can't decrypt it (e.g. RAR4 header encryption or a
 * cipher it doesn't implement), extraction falls back to the system 7-Zip binary with the same
 * password.
 */
class RarVirtualFileSystem(
    override val archivePath: Path,
    openProgress: VfsOpenProgress? = null,
) : AbstractTempDirVirtualFileSystem("turtle-rar-", openProgress) {

    override val isReadOnly: Boolean get() = true

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        when (sniffVersion()) {
            RarVersion.RAR4 -> extractRar4(into, progress)
            RarVersion.RAR5 -> extractRar5(into, progress, password = null)
            RarVersion.NOT_RAR -> throw SilentVfsOpenException()
        }
    }

    // ---- RAR4: junrar in-process, with a 7-Zip fallback for encryption it can't handle ----

    private fun extractRar4(into: Path, progress: VfsOpenProgress) {
        try {
            junrarExtract(into, password = null, progress)
            return
        } catch (_: PasswordRequired) {
            // fall through to the password flow below
        } catch (_: UnsupportedRarV5Exception) {
            // Mis-sniffed as RAR4 (shouldn't normally happen) — let 7-Zip take it.
            reset(into)
            extractRar5(into, progress, password = null)
            return
        } catch (e: RarException) {
            if (!isPasswordIssue(e)) throw IOException("Failed to read RAR archive: ${e.message}", e)
            // encryption-related — fall through to the password flow
        }

        val password = promptPassword()
            ?: throw IOException("Password required to open ${archivePath.fileName}.")
        try {
            reset(into)
            junrarExtract(into, password, progress)
        } catch (e: Exception) {
            // junrar couldn't decrypt this (header encryption or an unsupported cipher) —
            // delegate to the system 7-Zip binary with the same password.
            reset(into)
            sevenZipWithPassword(into, progress, password)
        }
    }

    private fun junrarExtract(into: Path, password: String?, progress: VfsOpenProgress) {
        val archive = if (password == null) {
            Archive(archivePath.toFile())
        } else {
            Archive(archivePath.toFile(), password)
        }
        archive.use { a ->
            if (password == null && a.isPasswordProtected) throw PasswordRequired()
            for (header in a.fileHeaders) {
                if (progress.isCancelled) break
                if (password == null && header.isEncrypted) throw PasswordRequired()
                val rawName = header.fileName ?: continue
                progress.onEntry(0, 0, rawName)
                val target = resolveEntryPath(into, rawName.replace('\\', '/')) ?: continue
                if (header.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    target.parent?.let { Files.createDirectories(it) }
                    Files.newOutputStream(target).use { out -> a.extractFile(header, out) }
                    runCatching { header.lastModifiedTime?.let { Files.setLastModifiedTime(target, it) } }
                }
            }
        }
    }

    // ---- RAR5 (and the encrypted-RAR4 fallback): system 7-Zip ----

    private fun extractRar5(into: Path, progress: VfsOpenProgress, password: String?) {
        if (System7z.findBinary() == null) {
            throw IOException(
                "This is a RAR5 archive. Reading RAR5 needs 7-Zip / p7zip: install it and ensure " +
                    "7z, 7zz, or 7za is on PATH (or in the standard 7-Zip install location on Windows).",
            )
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

    private fun sevenZipWithPassword(into: Path, progress: VfsOpenProgress, password: String) {
        if (System7z.findBinary() == null) {
            throw IOException(
                "This encrypted RAR couldn't be read in-process. Install 7-Zip / p7zip (7z, 7zz, or " +
                    "7za on PATH, or the standard 7-Zip install on Windows) to open it.",
            )
        }
        System7z.extract(archivePath, into, progress, password)
    }

    // ---- helpers ----

    /** Internal signal that the no-password attempt hit encrypted content. */
    private class PasswordRequired : RuntimeException()

    private enum class RarVersion { RAR4, RAR5, NOT_RAR }

    private fun sniffVersion(): RarVersion {
        val header = ByteArray(8)
        val read = try {
            Files.newInputStream(archivePath).use { it.readNBytes(header, 0, header.size) }
        } catch (_: Exception) {
            return RarVersion.NOT_RAR
        }
        return when {
            read >= RAR5_SIGNATURE.size && header.startsWith(RAR5_SIGNATURE) -> RarVersion.RAR5
            read >= RAR4_SIGNATURE.size && header.startsWith(RAR4_SIGNATURE) -> RarVersion.RAR4
            else -> RarVersion.NOT_RAR
        }
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

    /**
     * Shows a modal password prompt on the EDT and returns the entered password, or `null`
     * when the user cancels (or leaves it empty). [extract] always runs on a background
     * thread (see `enterVfs` / `ArchiveService.extractArchiveWithProgress`), so blocking here
     * with `invokeAndWait` is safe.
     */
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

    private fun reset(dir: Path) {
        dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
    }

    companion object {
        // RAR archive signatures: "Rar!" + 0x1A 0x07 then 0x00 (RAR 1.5-4, 7 bytes)
        // or 0x01 0x00 (RAR5, 8 bytes).
        private val RAR4_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        private val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    }
}
