package io.github.jhspetersson.turtlecommander.service

import io.github.jhspetersson.turtlecommander.vfs.CompressedStreams
import io.github.jhspetersson.turtlecommander.vfs.TarVirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VfsOpenProgress
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemProvider
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class ArchiveServiceCompressedTarExtractPassesTest {

    companion object {
        private const val EXT = "countedtgz"
        private const val ENTRIES = 300
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

    @Test
    fun `extracting a compressed tar streams the archive a constant number of times`() {
        val archive = Files.createTempFile("passes-", ".$EXT").also { tempPaths.add(it) }
        GzipCompressorOutputStream(Files.newOutputStream(archive)).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                for (i in 0 until ENTRIES) {
                    val body = "entry $i".toByteArray()
                    val entry = TarArchiveEntry("dir${i % 10}/file$i.txt")
                    entry.size = body.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(body)
                    tar.closeArchiveEntry()
                }
            }
        }
        val destination = Files.createTempDirectory("passes-dest-").also { tempPaths.add(it) }
        opens.set(0)

        val errors = mutableListOf<Pair<Path, Exception>>()
        runBlocking {
            ArchiveService().extractArchiveWithProgress(
                archivePath = archive,
                destination = destination,
                initialPolicy = OverwritePolicy.OVERWRITE_ALL,
                onProgress = { _, _ -> },
                onOverwriteConfirm = { OverwriteResponse.OVERWRITE_ALL },
                onError = { path, e -> errors.add(path to e) },
                isCancelled = { false },
            )
        }

        assertTrue("extraction reported errors: $errors", errors.isEmpty())
        assertEquals("entry 123", Files.readString(destination.resolve("dir3/file123.txt")))
        assertEquals("entry 299", Files.readString(destination.resolve("dir9/file299.txt")))
        assertEquals(2, opens.get())
    }
}
