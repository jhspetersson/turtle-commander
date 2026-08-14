package io.github.jhspetersson.turtlecommander.vfs

import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.UnsupportedRarVersionException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class RarFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("rar", "cbr")
    }

    override fun supportsExtension(ext: String): Boolean = ext in ARCHIVE_EXTENSIONS

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem =
        RarVirtualFileSystem(archivePath, openProgress)
}

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
        if (!sniffIsRar()) throw SilentVfsOpenException()

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

    private fun reset(dir: Path) {
        dir.toFile().deleteRecursively()
        Files.createDirectories(dir)
    }

    companion object {
        private val RAR4_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        private val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    }
}
