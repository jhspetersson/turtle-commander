package io.github.jhspetersson.turtlecommander.service

import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.dialog.NamePatternMode
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.vfs.CompressedStreams
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry
import io.github.jhspetersson.turtlecommander.vfs.TarVirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VfsOpenProgress
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemProvider
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class FileSearchServiceCompressedTarPassesTest {

    companion object {
        private const val EXT = "searchtgz"
        private const val ENTRIES = 300
        private const val NEEDLE_EVERY = 10
        private val opens = AtomicInteger()

        init {
            VirtualFileSystemRegistry.register(object : VirtualFileSystemProvider {
                override fun supportsExtension(ext: String): Boolean = ext == EXT
                override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)
                override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem =
                    TarVirtualFileSystem(
                        archivePath,
                        inputStreamFactory = { path ->
                            opens.incrementAndGet()
                            CompressedStreams.openInput(path) { raw -> CompressedStreams.gzipInput(raw) }
                        },
                        openProgress = openProgress,
                    )
            })
        }
    }

    private val tempPaths = mutableListOf<Path>()

    @After
    fun cleanup() {
        for (path in tempPaths.reversed()) {
            runCatching {
                if (Files.isDirectory(path)) {
                    Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
                } else {
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private fun buildArchive(): Path {
        val archive = Files.createTempFile("search-passes-", ".$EXT").also { tempPaths.add(it) }
        GzipCompressorOutputStream(Files.newOutputStream(archive)).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                for (i in 0 until ENTRIES) {
                    val text = if (i % NEEDLE_EVERY == 0) "entry $i has the needle" else "entry $i"
                    val body = text.toByteArray()
                    val entry = TarArchiveEntry("dir${i % 10}/file$i.txt")
                    entry.size = body.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(body)
                    tar.closeArchiveEntry()
                }
            }
        }
        return archive
    }

    private fun search(criteria: FileSearchCriteria): List<FileEntry> {
        val results = mutableListOf<FileEntry>()
        FileSearchService(criteria).search(
            onResult = { results.add(it) },
            isCancelled = { false },
            onProgress = { _, _ -> },
        )
        return results
    }

    @Test
    fun `content search inside a compressed tar streams the archive a constant number of times`() {
        val archive = buildArchive()
        opens.set(0)

        VirtualFileSystemRegistry.create(archive).use { vfs ->
            val results = search(FileSearchCriteria(
                rootPath = vfs.root,
                namePattern = null,
                namePatternMode = NamePatternMode.GLOB,
                contentPattern = "needle",
                sizeFilter = null,
                creationDateFilter = null,
                modificationDateFilter = null,
            ))

            assertEquals(ENTRIES / NEEDLE_EVERY, results.size)
            assertEquals(
                (0 until ENTRIES step NEEDLE_EVERY).map { "file$it.txt" }.toSet(),
                results.map { it.name }.toSet(),
            )
            assertEquals("entry 120 has the needle", Files.readString(vfs.root.resolve("dir0/file120.txt")))
        }

        assertEquals(2, opens.get())
    }

    @Test
    fun `name filter narrows the bulk pass to matching entries only`() {
        val archive = buildArchive()
        opens.set(0)

        VirtualFileSystemRegistry.create(archive).use { vfs ->
            val results = search(FileSearchCriteria(
                rootPath = vfs.root,
                namePattern = "file12*.txt",
                namePatternMode = NamePatternMode.GLOB,
                contentPattern = "needle",
                sizeFilter = null,
                creationDateFilter = null,
                modificationDateFilter = null,
            ))

            assertEquals(setOf("file120.txt"), results.map { it.name }.toSet())
            assertEquals(2, opens.get())

            val untouched = vfs.root.resolve("dir3/file13.txt")
            OpenVfsRegistry.materializeIfNeeded(untouched)
            assertEquals("entry 13", Files.readString(untouched))
        }

        assertEquals(3, opens.get())
    }
}
