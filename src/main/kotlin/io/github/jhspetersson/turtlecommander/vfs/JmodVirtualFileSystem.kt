package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class JmodFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("jmod")
    }

    override fun supportsExtension(ext: String): Boolean = ext in ARCHIVE_EXTENSIONS

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem =
        JmodVirtualFileSystem(archivePath, openProgress)
}

class JmodVirtualFileSystem(
    override val archivePath: Path,
    openProgress: VfsOpenProgress? = null,
) : AbstractTempDirVirtualFileSystem("turtle-jmod-", openProgress) {

    override val isReadOnly: Boolean get() = true

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        val innerZip = Files.createTempFile("turtle-jmod-inner-", ".zip")
        try {
            Files.newInputStream(archivePath).use { input ->
                skipJmodHeader(input)
                Files.newOutputStream(innerZip).use { output ->
                    input.copyTo(output)
                }
            }

            ZipFile.builder().setPath(innerZip).get().use { zip ->
                var total = 0
                val counter = zip.entries
                while (counter.hasMoreElements()) {
                    counter.nextElement()
                    total++
                }
                val iter = zip.entries
                var index = 0
                while (iter.hasMoreElements()) {
                    val entry = iter.nextElement()
                    if (progress.isCancelled) break
                    index++
                    progress.onEntry(index, total, entry.name)
                    val entryPath = resolveEntryPath(into, entry.name) ?: continue
                    try {
                        if (entry.isDirectory) {
                            Files.createDirectories(entryPath)
                        } else {
                            Files.createDirectories(entryPath.parent)
                            zip.getInputStream(entry).use { stream ->
                                Files.copy(stream, entryPath)
                            }
                        }
                        if (entry.lastModifiedDate != null) {
                            Files.setLastModifiedTime(
                                entryPath,
                                FileTime.fromMillis(entry.lastModifiedDate.time),
                            )
                        }
                    } catch (e: Exception) {
                        thisLogger().debug("Failed to extract jmod entry: ${entry.name}", e)
                    }
                }
            }
        } finally {
            try {
                Files.deleteIfExists(innerZip)
            } catch (e: Exception) {
                thisLogger().debug("Failed to delete inner jmod zip $innerZip: ${e.message}")
            }
        }
    }

    private fun skipJmodHeader(input: InputStream) {
        val data = DataInputStream(input)
        val header = ByteArray(4)
        data.readFully(header)
        if (header[0] != 'J'.code.toByte() || header[1] != 'M'.code.toByte()) {
            throw IOException("Not a JMOD file: missing 'JM' magic header")
        }
    }
}
