package io.github.jhspetersson.turtlecommander.vfs

import com.intellij.openapi.diagnostic.thisLogger
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

class ArFileSystemProvider : VirtualFileSystemProvider {
    companion object {
        val ARCHIVE_EXTENSIONS = setOf("ar", "a", "deb")
    }

    override fun supportsExtension(ext: String): Boolean {
        return ext in ARCHIVE_EXTENSIONS
    }

    override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)

    override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem {
        return ArVirtualFileSystem(archivePath, openProgress)
    }
}

class ArVirtualFileSystem(
    override val archivePath: Path,
    openProgress: VfsOpenProgress? = null,
    private val repackOutput: (Path) -> OutputStream = { Files.newOutputStream(it) },
) : AbstractTempDirVirtualFileSystem("turtle-ar-", openProgress) {

    private data class ArEntryMetadata(
        val mode: Int,
        val userId: Int,
        val groupId: Int,
        val lastModifiedSeconds: Long,
        val extractedModifiedSeconds: Long,
        val extractedSize: Long,
        val ordinal: Int,
        val headerOffset: Long,
    )

    private class ArSymbol(val name: ByteArray, val memberOffset: Long)

    private class ArMember(
        val path: Path,
        val name: String,
        val attrs: BasicFileAttributes,
        val metadata: ArEntryMetadata?,
    ) {
        val modifiedSeconds: Long get() = attrs.lastModifiedTime().toMillis() / 1000

        val unchanged: Boolean
            get() = metadata != null && metadata.extractedModifiedSeconds == modifiedSeconds && metadata.extractedSize == attrs.size()

        val storedLength: Long
            get() {
                val appendedName = if (name.length > NAME_FIELD_LENGTH || name.contains(' ')) name.length else 0
                val length = HEADER_LENGTH + appendedName + attrs.size()
                return length + length % 2
            }
    }

    private var symbols: List<ArSymbol>? = null

    /**
     * Metadata captured at extract time keyed by the original AR entry name. Repack uses this
     * to preserve mode/uid/gid/mtime rather than resetting every entry to 0644/root/root.
     * Entries that were created after extraction (not present here) fall back to sensible defaults.
     */
    private val entryMetadata = mutableMapOf<String, ArEntryMetadata>()

    init {
        openTempDir()
    }

    override fun extract(into: Path) {
        val progress = takeOpenProgress()
        entryMetadata.clear()
        symbols = null
        val headerOffsets = memberHeaderOffsets()
        Files.newInputStream(archivePath).use { raw ->
            ArArchiveInputStream(raw).use { ar ->
                var ordinal = -1
                var entry = ar.nextEntry
                while (entry != null) {
                    ordinal++
                    if (progress.isCancelled) break
                    progress.onEntry(0, 0, entry.name)
                    if (ordinal == 0 && entry.name.isEmpty()) {
                        symbols = parseSymbolTable(ar.readBytes())
                        entry = ar.nextEntry
                        continue
                    }
                    val entryPath = resolveEntryPath(into, entry.name)
                    if (entryPath == null) {
                        entry = ar.nextEntry
                        continue
                    }
                    if (Files.exists(entryPath)) {
                        reportUnextractable(entry.name, IOException("duplicate member name"))
                        entry = ar.nextEntry
                        continue
                    }
                    Files.createDirectories(entryPath.parent)
                    Files.copy(ar, entryPath)
                    try {
                        if (entry.lastModifiedDate != null) {
                            Files.setLastModifiedTime(entryPath, FileTime.fromMillis(entry.lastModifiedDate.time))
                        }
                    } catch (e: Exception) {
                        thisLogger().debug("Failed to set modified time for ar entry: ${entry.name}", e)
                    }
                    entryMetadata[entry.name] = ArEntryMetadata(
                        mode = entry.mode,
                        userId = entry.userId,
                        groupId = entry.groupId,
                        lastModifiedSeconds = entry.lastModified,
                        extractedModifiedSeconds = Files.getLastModifiedTime(entryPath).toMillis() / 1000,
                        extractedSize = Files.size(entryPath),
                        ordinal = ordinal,
                        headerOffset = headerOffsets.getOrElse(ordinal) { -1L },
                    )
                    entry = ar.nextEntry
                }
            }
        }
    }

    override fun onRenamed(source: Path, target: Path) {
        // Carry metadata over to the new name so repack keeps the original mode/uid/gid/mtime.
        val oldName = tempDir.relativize(source).toString().replace('\\', '/')
        val newRelativeName = tempDir.relativize(target).toString().replace('\\', '/')
        entryMetadata.remove(oldName)?.let { entryMetadata[newRelativeName] = it }
    }

    override fun repack(from: Path) {
        repackAtomically(archivePath) { target ->
            CompressedStreams.bufferedOutput(repackOutput(target)).use { raw ->
                ArArchiveOutputStream(raw).use { ar ->
                    ar.setLongFileMode(ArArchiveOutputStream.LONGFILE_BSD)
                    val members = mutableListOf<ArMember>()
                    forEachArchiveEntry(from) { path, relativeName ->
                        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                        if (!attrs.isDirectory) members += ArMember(path, relativeName, attrs, entryMetadata[relativeName])
                    }
                    members.sortWith(compareBy({ it.metadata?.ordinal ?: Int.MAX_VALUE }, { it.name }))
                    symbolTableFor(members)?.let { table ->
                        ar.putArchiveEntry(ArArchiveEntry("/", table.size.toLong(), 0, 0, 0, System.currentTimeMillis() / 1000))
                        ar.write(table)
                        ar.closeArchiveEntry()
                    }
                    for (member in members) {
                        try {
                            val metadata = member.metadata
                            val entry = ArArchiveEntry(
                                member.name,
                                member.attrs.size(),
                                metadata?.userId ?: 0,
                                metadata?.groupId ?: 0,
                                metadata?.mode ?: DEFAULT_FILE_MODE,
                                metadata?.takeIf { it.extractedModifiedSeconds == member.modifiedSeconds }?.lastModifiedSeconds
                                    ?: member.modifiedSeconds,
                            )
                            ar.putArchiveEntry(entry)
                            Files.copy(member.path, ar)
                            ar.closeArchiveEntry()
                        } catch (e: Exception) {
                            throw IOException("Failed to repack entry '${member.name}': ${e.message ?: e.javaClass.simpleName}", e)
                        }
                    }
                }
            }
        }
    }

    private fun memberHeaderOffsets(): List<Long> = runCatching {
        val offsets = mutableListOf<Long>()
        FileChannel.open(archivePath, StandardOpenOption.READ).use { channel ->
            val header = ByteBuffer.allocate(HEADER_LENGTH)
            var position = MAGIC_LENGTH
            while (position + HEADER_LENGTH <= channel.size()) {
                header.clear()
                while (header.hasRemaining() && channel.read(header, position + header.position()) > 0) Unit
                if (header.hasRemaining()) break
                val text = String(header.array(), Charsets.US_ASCII)
                val size = text.substring(SIZE_FIELD_OFFSET, SIZE_FIELD_OFFSET + SIZE_FIELD_LENGTH).trim().toLong()
                if (text.substring(0, NAME_FIELD_LENGTH).trim() != GNU_NAME_TABLE) offsets += position
                position += HEADER_LENGTH + size
                position += position % 2
            }
        }
        offsets
    }.getOrDefault(emptyList())

    private fun parseSymbolTable(bytes: ByteArray): List<ArSymbol>? = runCatching {
        val buffer = ByteBuffer.wrap(bytes)
        val count = buffer.int
        if (count < 0 || count.toLong() * Int.SIZE_BYTES > buffer.remaining()) return null
        val offsets = LongArray(count) { buffer.int.toLong() and 0xFFFFFFFFL }
        val result = ArrayList<ArSymbol>(count)
        var start = buffer.position()
        for (offset in offsets) {
            var end = start
            while (bytes[end] != 0.toByte()) end++
            result += ArSymbol(bytes.copyOfRange(start, end), offset)
            start = end + 1
        }
        result
    }.getOrNull()

    private fun symbolTableFor(members: List<ArMember>): ByteArray? {
        val recorded = symbols ?: return null
        if (members.any { !it.unchanged }) return null
        val present = members.mapNotNull { it.metadata?.headerOffset }.toSet()
        val kept = recorded.filter { it.memberOffset in present }
        val tableSize = Int.SIZE_BYTES + kept.size * Int.SIZE_BYTES + kept.sumOf { it.name.size + 1 }
        var position = MAGIC_LENGTH + HEADER_LENGTH + tableSize + tableSize % 2
        val moved = HashMap<Long, Long>()
        for (member in members) {
            member.metadata?.let { moved[it.headerOffset] = position }
            position += member.storedLength
        }
        if (position > 0xFFFFFFFFL) return null
        val table = ByteBuffer.allocate(tableSize)
        table.putInt(kept.size)
        for (symbol in kept) table.putInt(moved.getValue(symbol.memberOffset).toInt())
        for (symbol in kept) {
            table.put(symbol.name)
            table.put(0)
        }
        return table.array()
    }

    companion object {
        // Default mode for newly added entries that have no recorded metadata: 0100644 (rw-r--r--).
        private const val DEFAULT_FILE_MODE = 0x81A4
        private const val MAGIC_LENGTH = 8L
        private const val HEADER_LENGTH = 60
        private const val NAME_FIELD_LENGTH = 16
        private const val SIZE_FIELD_OFFSET = 48
        private const val SIZE_FIELD_LENGTH = 10
        private const val GNU_NAME_TABLE = "//"
    }
}
