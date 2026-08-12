package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ZstVirtualFileSystemTest {

    private lateinit var archivePath: Path
    private var vfs: VirtualFileSystem? = null

    @After
    fun tearDown() {
        vfs?.close()
        if (::archivePath.isInitialized) Files.deleteIfExists(archivePath)
    }

    private fun writeTarZst(suffix: String): Path {
        val path = Files.createTempFile("test-", suffix)
        TarArchiveOutputStream(ZstdCompressorOutputStream(Files.newOutputStream(path))).use { tos ->
            val data = "zstd tarball entry".toByteArray()
            val entry = TarArchiveEntry("docs/readme.txt")
            entry.size = data.size.toLong()
            tos.putArchiveEntry(entry)
            tos.write(data)
            tos.closeArchiveEntry()
        }
        return path
    }

    @Test
    fun `tar zst extracts entries`() = runBlocking {
        archivePath = writeTarZst(".tar.zst")
        val zstVfs = ZstFileSystemProvider().create(archivePath) as TarVirtualFileSystem
        vfs = zstVfs

        val rootEntries = zstVfs.listFiles(zstVfs.root).filter { !it.isParentLink }
        assertNotNull(rootEntries.find { it.name == "docs" })
        val readme = zstVfs.getPath("/docs/readme.txt")
        zstVfs.materialize(readme)
        assertEquals("zstd tarball entry", Files.readString(readme))
    }

    @Test
    fun `tzst extracts entries`() = runBlocking {
        archivePath = writeTarZst(".tzst")
        val zstVfs = ZstFileSystemProvider().create(archivePath) as TarVirtualFileSystem
        vfs = zstVfs
        val readme = zstVfs.getPath("/docs/readme.txt")
        zstVfs.materialize(readme)
        assertEquals("zstd tarball entry", Files.readString(readme))
    }

    @Test
    fun `standalone zst decompresses single file`() = runBlocking {
        archivePath = Files.createTempFile("test-notes-", ".zst")
        ZstdCompressorOutputStream(Files.newOutputStream(archivePath)).use { out ->
            out.write("plain zstd payload".toByteArray())
        }
        val zstVfs = ZstFileSystemProvider().create(archivePath)
        vfs = zstVfs

        val entries = zstVfs.listFiles(zstVfs.root).filter { !it.isParentLink }
        assertEquals(1, entries.size)
        assertFalse(entries[0].name.endsWith(".zst"))
        assertEquals("plain zstd payload", Files.readString(entries[0].path))
    }
}
