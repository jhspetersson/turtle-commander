package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.*

class SevenZipFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("7z")
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem {
        return SevenZipVirtualFileSystem(archivePath)
    }
}

class SevenZipVirtualFileSystem(
    override val archivePath: Path,
) : AbstractTempDirVirtualFileSystem("turtle-7z-") {

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val indicator = ProgressManager.getGlobalProgressIndicator()
        try {
            extractWithCommonsCompress(into, indicator)
        } catch (e: Exception) {
            // commons-compress can't decode multi-stream coders (BCJ2, certain
            // delta variants, some encrypted modes). Fall back to invoking the
            // user's installed 7-Zip CLI before giving up — it handles
            // everything 7-Zip itself can produce.
            if (looksLikeUnsupportedCoder(e)) {
                // Wipe whatever commons-compress wrote before falling back to the
                // system tool. openTempDir will clean up on a final throw, but the
                // retry happens inside extract() so we have to clear partial state
                // ourselves before the second attempt.
                into.toFile().deleteRecursively()
                Files.createDirectory(into)
                try {
                    System7z.extract(archivePath, into, indicator)
                    return
                } catch (toolError: Exception) {
                    throw IOException(
                        "This 7z archive uses a coder commons-compress doesn't support " +
                            "(e.g. BCJ2). Tried system 7-Zip but: " +
                            (toolError.message ?: toolError::class.simpleName),
                        toolError,
                    )
                }
            }
            throw e
        }
    }

    private fun extractWithCommonsCompress(dir: Path, indicator: ProgressIndicator?) {
        SevenZFile.builder().setPath(archivePath).get().use { sevenZ ->
            var entry = sevenZ.nextEntry
            while (entry != null) {
                if (indicator?.isCanceled == true) break
                indicator?.text2 = entry.name
                val entryPath = resolveEntryPath(dir, entry.name)
                if (entryPath == null) {
                    entry = sevenZ.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    Files.createDirectories(entryPath)
                } else {
                    Files.createDirectories(entryPath.parent)
                    Files.newOutputStream(entryPath).use { out ->
                        val buf = ByteArray(8192)
                        var len: Int
                        while (sevenZ.read(buf).also { len = it } != -1) {
                            out.write(buf, 0, len)
                        }
                    }
                }
                try {
                    if (entry.hasLastModifiedDate) {
                        Files.setLastModifiedTime(entryPath, FileTime.fromMillis(entry.lastModifiedDate.time))
                    }
                } catch (e: Exception) {
                    thisLogger().debug("Failed to set modified time for 7z entry: ${entry.name}", e)
                }
                entry = sevenZ.nextEntry
            }
        }
    }

    internal fun looksLikeUnsupportedCoder(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            val msg = current.message.orEmpty()
            // Substrings come from commons-compress 1.28's SevenZFile / Coders error sites
            // — every one of these signals a decoder gap the system 7-Zip CLI can typically
            // bridge (BCJ2 stacks, missing XZ-for-Java, codecs not yet implemented, archives
            // using header features the library refuses).
            if ("Multi input/output stream coders" in msg ||
                "Unsupported compression method" in msg ||
                "BCJ filter" in msg ||
                "Alternative methods are unsupported" in msg ||
                "Additional streams unsupported" in msg
            ) return true
            current = current.cause
        }
        return false
    }

    override fun repack(from: Path) {
        SevenZOutputFile(archivePath.toFile()).use { out ->
            // SevenZOutputFile duck-types as an OutputStream (write(int)/write(byte[])/
            // write(byte[],int,int)) but doesn't actually extend it, so we can't hand it
            // to Files.copy directly. A thin adapter lets us replace the hand-rolled 8KB
            // read/write loop with the JDK's tuned Files.copy → OutputStream path.
            val sink = object : OutputStream() {
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            }
            forEachArchiveEntry(from) { path, relativeName ->
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                val entry = SevenZArchiveEntry().apply {
                    name = relativeName + if (attrs.isDirectory) "/" else ""
                    isDirectory = attrs.isDirectory
                    if (!attrs.isDirectory) size = attrs.size()
                    if (attrs.lastModifiedTime() != null) {
                        lastModifiedDate = Date(attrs.lastModifiedTime().toMillis())
                    }
                }
                out.putArchiveEntry(entry)
                if (!attrs.isDirectory) {
                    Files.copy(path, sink)
                }
                out.closeArchiveEntry()
            }
        }
    }
}
