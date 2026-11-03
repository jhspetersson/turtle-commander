package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

class RepackOutputBufferingTest {

    private val entryCount = 300
    private val payload = ByteArray(100) { it.toByte() }
    private val maxWrites = 20
    private var writes = 0
    private val archives = mutableListOf<Path>()

    @After
    fun cleanup() {
        archives.forEach { Files.deleteIfExists(it) }
    }

    private fun counting(path: Path): OutputStream = object : FilterOutputStream(Files.newOutputStream(path)) {
        override fun write(b: Int) {
            writes++
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            writes++
            out.write(b, off, len)
        }
    }

    private fun archive(suffix: String): Path = Files.createTempFile("repack-buffering-", suffix).also { archives.add(it) }

    @Test
    fun `tar repack writes in large chunks`() = runBlocking {
        val tarPath = archive(".tar")
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            for (i in 0 until entryCount) {
                val entry = TarArchiveEntry("f$i.bin")
                entry.size = payload.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(payload)
                tar.closeArchiveEntry()
            }
        }
        TarVirtualFileSystem(tarPath, inputStreamFactory = { Files.newInputStream(it) }, outputStreamFactory = { counting(it) }).use { vfs ->
            vfs.renameFile(vfs.root.resolve("f0.bin"), "renamed.bin")
        }
        assertTrue("tar repack issued $writes writes", writes in 1..maxWrites)
    }

    @Test
    fun `zip repack writes in large chunks`() = runBlocking {
        val zipPath = archive(".zip")
        ZipArchiveOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            for (i in 0 until entryCount) {
                zip.putArchiveEntry(ZipArchiveEntry("f$i.bin"))
                zip.write(payload)
                zip.closeArchiveEntry()
            }
        }
        ZipExtractVirtualFileSystem(zipPath, repackOutput = { counting(it) }).use { vfs ->
            vfs.renameFile(vfs.root.resolve("f0.bin"), "renamed.bin")
        }
        assertTrue("zip repack issued $writes writes", writes in 1..maxWrites)
    }

    @Test
    fun `ar repack writes in large chunks`() = runBlocking {
        val arPath = archive(".ar")
        ArArchiveOutputStream(Files.newOutputStream(arPath)).use { ar ->
            for (i in 0 until entryCount) {
                ar.putArchiveEntry(ArArchiveEntry("f$i.bin", payload.size.toLong()))
                ar.write(payload)
                ar.closeArchiveEntry()
            }
        }
        ArVirtualFileSystem(arPath, repackOutput = { counting(it) }).use { vfs ->
            vfs.renameFile(vfs.root.resolve("f0.bin"), "renamed.bin")
        }
        assertTrue("ar repack issued $writes writes", writes in 1..maxWrites)
    }
}
