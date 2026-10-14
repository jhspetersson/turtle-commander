package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

class MultiMemberCompressedTest {

    private val tempFiles = mutableListOf<Path>()
    private val opened = mutableListOf<VirtualFileSystem>()

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun compress(suffix: String, bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val wrapped: OutputStream = when (suffix) {
            "gz" -> GzipCompressorOutputStream(out)
            "bz2" -> BZip2CompressorOutputStream(out)
            "xz" -> XZCompressorOutputStream(out)
            "zst" -> ZstdCompressorOutputStream(out)
            else -> throw IllegalArgumentException(suffix)
        }
        wrapped.use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun members(suffix: String, vararg parts: ByteArray): ByteArray =
        parts.fold(ByteArray(0)) { acc, part -> acc + compress(suffix, part) }

    private fun writeTemp(name: String, bytes: ByteArray): Path {
        val dir = Files.createTempDirectory("tc-multimember-")
        val path = dir.resolve(name)
        Files.write(path, bytes)
        tempFiles.add(path)
        tempFiles.add(dir)
        return path
    }

    private fun tar(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            for ((name, content) in entries) {
                val bytes = content.toByteArray()
                val entry = TarArchiveEntry(name)
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private fun open(path: Path): VirtualFileSystem =
        VirtualFileSystemRegistry.create(path).also { opened += it }

    private fun singleFileContent(suffix: String): String = runBlocking {
        val path = writeTemp("notes.txt.$suffix", members(suffix, "first-".toByteArray(), "second".toByteArray()))
        val vfs = open(path)
        val inner = vfs.listFiles(vfs.root).single { !it.isParentLink }
        Files.readString(inner.path)
    }

    @Test
    fun `gz single file shows every member`() = assertEquals("first-second", singleFileContent("gz"))

    @Test
    fun `bz2 single file shows every member`() = assertEquals("first-second", singleFileContent("bz2"))

    @Test
    fun `xz single file shows every member`() = assertEquals("first-second", singleFileContent("xz"))

    @Test
    fun `zst single file shows every member`() = assertEquals("first-second", singleFileContent("zst"))

    private fun splitTar(suffix: String, name: String): Path {
        val whole = tar("a.txt" to "alpha", "b.txt" to "bravo")
        val cut = whole.size / 2
        return writeTemp(name, members(suffix, whole.copyOfRange(0, cut), whole.copyOfRange(cut, whole.size)))
    }

    private fun tarEntries(path: Path): Map<String, String> = runBlocking {
        val vfs = open(path)
        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        vfs.materializeAll(entries.map { it.path })
        entries.associate { it.name to Files.readString(it.path) }
    }

    @Test
    fun `tar gz lists entries from the second member`() {
        assertEquals(mapOf("a.txt" to "alpha", "b.txt" to "bravo"), tarEntries(splitTar("gz", "split.tar.gz")))
    }

    @Test
    fun `tar bz2 lists entries from the second member`() {
        assertEquals(mapOf("a.txt" to "alpha", "b.txt" to "bravo"), tarEntries(splitTar("bz2", "split.tar.bz2")))
    }

    @Test
    fun `tar xz lists entries from the second member`() {
        assertEquals(mapOf("a.txt" to "alpha", "b.txt" to "bravo"), tarEntries(splitTar("xz", "split.tar.xz")))
    }

    @Test
    fun `tar gz repack keeps entries from the second member`() = runBlocking {
        val path = splitTar("gz", "repack.tar.gz")
        val vfs = open(path)
        vfs.renameFile(vfs.getPath("a.txt"), "renamed.txt")
        vfs.flush()

        val names = mutableListOf<String>()
        CompressedStreams.openInput(path) { CompressedStreams.gzipInput(it) }.use { raw ->
            TarArchiveInputStream(raw).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    names += entry.name
                }
            }
        }
        assertEquals(listOf("b.txt", "renamed.txt"), names.sorted())
        assertNotNull(vfs.listFiles(vfs.root).find { it.name == "b.txt" })
        assertNull(vfs.listFiles(vfs.root).find { it.name == "a.txt" })
    }
}
