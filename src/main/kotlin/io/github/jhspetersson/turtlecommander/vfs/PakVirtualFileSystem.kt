package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class PakFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("pak")
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    /**
     * Open a `.pak` file as the first format that parses:
     *
     *  1. a Quake / Quake 2 PAK archive (`PACK` magic);
     *  2. a ZIP archive — some games and mods ship `.pak` files that are really ZIPs.
     *
     * If neither parses, throw [SilentVfsOpenException] so the caller leaves the file
     * as it is, without surfacing an error to the user.
     */
    override fun create(archivePath: Path): VirtualFileSystem {
        try {
            return PakVirtualFileSystem(archivePath)
        } catch (e: Exception) {
            thisLogger().debug("Not a Quake PAK, trying ZIP fallback: $archivePath", e)
        }
        return try {
            ZipFileSystemProvider().create(archivePath)
        } catch (e: Exception) {
            thisLogger().debug("Not a ZIP either, leaving file as-is: $archivePath", e)
            throw SilentVfsOpenException()
        }
    }
}

/**
 * VFS for Quake / Quake 2 `.pak` files. The PAK format is a flat archive with a fixed
 * 12-byte header followed by raw file data and, at an arbitrary offset, a directory:
 *
 *  - Header: `PACK` magic (4 bytes), directory offset (int32 LE), directory length
 *    (int32 LE, a multiple of 64).
 *  - Each directory entry is 64 bytes: a 56-byte null-padded ASCII name (forward-slash
 *    separated), the file's offset (int32 LE) and its size (int32 LE).
 *
 * We parse the directory, extract every entry into a temp directory like the other
 * extract-based VFS implementations, and serve the browser from there. The temp directory
 * is repacked into a fresh PAK on flush, so adding, editing, renaming and deleting entries
 * are all supported.
 */
class PakVirtualFileSystem(
    override val archivePath: Path,
) : AbstractTempDirVirtualFileSystem("turtle-pak-") {

    init {
        openTempDir()
    }

    private data class PakEntry(val name: String, val offset: Long, val size: Long)

    override fun extract(into: Path) {
        val indicator = ProgressManager.getGlobalProgressIndicator()
        RandomAccessFile(archivePath.toFile(), "r").use { raf ->
            val fileLength = raf.length()
            val entries = readDirectory(raf, fileLength)
            if (entries.isNotEmpty()) indicator?.isIndeterminate = false
            val buffer = ByteArray(64 * 1024)
            entries.forEachIndexed { index, entry ->
                if (indicator?.isCanceled == true) return@forEachIndexed
                indicator?.fraction = (index + 1).toDouble() / entries.size
                indicator?.text2 = entry.name
                val entryPath = resolveEntryPath(into, entry.name) ?: return@forEachIndexed
                try {
                    entryPath.parent?.let { Files.createDirectories(it) }
                    raf.seek(entry.offset)
                    Files.newOutputStream(entryPath).use { out ->
                        var remaining = entry.size
                        while (remaining > 0) {
                            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                            val read = raf.read(buffer, 0, toRead)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                } catch (e: Exception) {
                    thisLogger().debug("Failed to extract pak entry: ${entry.name}", e)
                }
            }
        }
    }

    /**
     * Rebuilds the PAK from the temp directory: a 12-byte header, the concatenated file
     * data, then the directory. PAK is a flat format, so directories are not stored as
     * entries — they are implied by the forward slashes in the file names. Offsets and
     * sizes are taken from the bytes actually written, which keeps the directory correct
     * even if a file changed size since extraction.
     */
    override fun repack(from: Path) {
        val files = mutableListOf<Pair<String, Path>>()
        forEachArchiveEntry(from) { path, relativeName ->
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (attrs.isDirectory) return@forEachArchiveEntry
            if (relativeName.toByteArray(StandardCharsets.US_ASCII).size > NAME_SIZE) {
                thisLogger().warn("Skipping pak entry whose name exceeds $NAME_SIZE bytes: $relativeName")
                return@forEachArchiveEntry
            }
            files.add(relativeName to path)
        }

        RandomAccessFile(archivePath.toFile(), "rw").use { raf ->
            raf.setLength(0)
            raf.seek(HEADER_SIZE.toLong())

            data class WrittenEntry(val name: String, val offset: Long, val size: Int)
            val directory = ArrayList<WrittenEntry>(files.size)
            val buffer = ByteArray(64 * 1024)
            for ((name, path) in files) {
                val offset = raf.filePointer
                var size = 0L
                Files.newInputStream(path).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                        size += read
                    }
                }
                directory.add(WrittenEntry(name, offset, size.toInt()))
            }

            val dirOffset = raf.filePointer
            for (entry in directory) {
                val nameField = ByteArray(NAME_SIZE)
                val nameBytes = entry.name.toByteArray(StandardCharsets.US_ASCII)
                System.arraycopy(nameBytes, 0, nameField, 0, nameBytes.size)
                raf.write(nameField)
                raf.write(leInt32(entry.offset.toInt()))
                raf.write(leInt32(entry.size))
            }

            raf.seek(0)
            raf.write("PACK".toByteArray(StandardCharsets.US_ASCII))
            raf.write(leInt32(dirOffset.toInt()))
            raf.write(leInt32(directory.size * DIRECTORY_ENTRY_SIZE.toInt()))
        }
    }

    private fun leInt32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    /**
     * Parses the PAK header and directory. Throws [IOException] for anything that is not a
     * well-formed PAK (bad magic, out-of-bounds directory) so the provider can fall back to
     * the ZIP reader. Individual entries that point outside the file are skipped rather than
     * aborting the whole archive.
     */
    private fun readDirectory(raf: RandomAccessFile, fileLength: Long): List<PakEntry> {
        if (fileLength < HEADER_SIZE) throw IOException("File too small to be a PAK")
        val header = ByteArray(HEADER_SIZE)
        raf.seek(0)
        raf.readFully(header)
        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (hb.get() != 'P'.code.toByte() ||
            hb.get() != 'A'.code.toByte() ||
            hb.get() != 'C'.code.toByte() ||
            hb.get() != 'K'.code.toByte()
        ) {
            throw IOException("Not a PAK file: missing 'PACK' magic")
        }
        val dirOffset = hb.int.toLong() and 0xFFFFFFFFL
        val dirLength = hb.int.toLong() and 0xFFFFFFFFL
        if (dirLength % DIRECTORY_ENTRY_SIZE != 0L) {
            throw IOException("Invalid PAK directory length: $dirLength")
        }
        if (dirOffset < HEADER_SIZE || dirOffset + dirLength > fileLength) {
            throw IOException("PAK directory out of bounds")
        }
        val count = (dirLength / DIRECTORY_ENTRY_SIZE).toInt()
        if (count == 0) return emptyList()

        val dirBytes = ByteArray(dirLength.toInt())
        raf.seek(dirOffset)
        raf.readFully(dirBytes)
        val db = ByteBuffer.wrap(dirBytes).order(ByteOrder.LITTLE_ENDIAN)

        val result = ArrayList<PakEntry>(count)
        for (i in 0 until count) {
            val base = i * DIRECTORY_ENTRY_SIZE.toInt()
            val nameBytes = ByteArray(NAME_SIZE)
            db.position(base)
            db.get(nameBytes)
            val offset = db.int.toLong() and 0xFFFFFFFFL
            val size = db.int.toLong() and 0xFFFFFFFFL
            val nameEnd = nameBytes.indexOf(0.toByte()).let { if (it < 0) NAME_SIZE else it }
            val name = String(nameBytes, 0, nameEnd, StandardCharsets.US_ASCII)
                .trim()
                .replace('\\', '/')
            if (name.isEmpty()) continue
            if (offset + size > fileLength) {
                thisLogger().debug("Skipping out-of-bounds pak entry: $name")
                continue
            }
            result.add(PakEntry(name, offset, size))
        }
        return result
    }

    companion object {
        private const val HEADER_SIZE = 12
        private const val DIRECTORY_ENTRY_SIZE = 64L
        private const val NAME_SIZE = 56
    }
}
