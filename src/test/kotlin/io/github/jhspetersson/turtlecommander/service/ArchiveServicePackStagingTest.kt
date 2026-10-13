package io.github.jhspetersson.turtlecommander.service

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveServicePackStagingTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pack-staging-")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun source(name: String, content: String): Path = Files.writeString(dir.resolve(name), content)

    private fun existingZip(): Path {
        val path = dir.resolve("target.zip")
        ZipOutputStream(Files.newOutputStream(path)).use { zos ->
            zos.putNextEntry(ZipEntry("old.txt"))
            zos.write("old".toByteArray())
            zos.closeEntry()
        }
        return path
    }

    private fun existingTarGz(): Path =
        Files.write(dir.resolve("target.tar.gz"), "previous archive bytes".toByteArray())

    private fun leftovers(): List<String> = Files.list(dir).use { stream ->
        stream.map { it.fileName.toString() }.filter { it.startsWith("tc-pack-") }.toList()
    }

    private fun packZip(archive: Path, sources: List<Path>, append: Boolean, cancelAfter: Int = Int.MAX_VALUE): Int = runBlocking {
        var packed = 0
        ArchiveService().packZip(
            archive, sources, append,
            onProgress = { count, _ -> packed = count },
            onError = { _, _ -> },
            isCancelled = { packed >= cancelAfter },
        )
    }

    private fun packTarGz(archive: Path, sources: List<Path>, cancelAfter: Int = Int.MAX_VALUE): Int = runBlocking {
        var packed = 0
        ArchiveService().packTarGz(
            archive, sources,
            onProgress = { count, _ -> packed = count },
            onError = { _, _ -> },
            isCancelled = { packed >= cancelAfter },
        )
    }

    @Test
    fun `zip overwrite keeps the original when nothing could be packed`() {
        val archive = existingZip()
        val original = Files.readAllBytes(archive)

        val packed = packZip(archive, listOf(dir.resolve("missing.txt")), append = false)

        assertEquals(0, packed)
        assertArrayEquals(original, Files.readAllBytes(archive))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `zip overwrite keeps the original when cancelled midway`() {
        val archive = existingZip()
        val original = Files.readAllBytes(archive)
        val sources = listOf(source("a.txt", "a"), source("b.txt", "b"))

        val packed = packZip(archive, sources, append = false, cancelAfter = 1)

        assertEquals(1, packed)
        assertArrayEquals(original, Files.readAllBytes(archive))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `zip append keeps the original when cancelled midway`() {
        val archive = existingZip()
        val original = Files.readAllBytes(archive)
        val sources = listOf(source("a.txt", "a"), source("b.txt", "b"))

        packZip(archive, sources, append = true, cancelAfter = 1)

        assertArrayEquals(original, Files.readAllBytes(archive))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `cancelled zip creation leaves no partial archive behind`() {
        val archive = dir.resolve("new.zip")
        val sources = listOf(source("a.txt", "a"), source("b.txt", "b"))

        packZip(archive, sources, append = false, cancelAfter = 1)

        assertFalse(Files.exists(archive))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `tar gz overwrite keeps the original when nothing could be packed`() {
        val archive = existingTarGz()
        val original = Files.readAllBytes(archive)

        val packed = packTarGz(archive, listOf(dir.resolve("missing.txt")))

        assertEquals(0, packed)
        assertArrayEquals(original, Files.readAllBytes(archive))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `tar gz overwrite keeps the original when cancelled midway`() {
        val archive = existingTarGz()
        val original = Files.readAllBytes(archive)
        val sources = listOf(source("a.txt", "a"), source("b.txt", "b"))

        val packed = packTarGz(archive, sources, cancelAfter = 1)

        assertEquals(1, packed)
        assertArrayEquals(original, Files.readAllBytes(archive))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `cancelled tar gz creation leaves no partial archive behind`() {
        val archive = dir.resolve("new.tar.gz")
        val sources = listOf(source("a.txt", "a"), source("b.txt", "b"))

        packTarGz(archive, sources, cancelAfter = 1)

        assertFalse(Files.exists(archive))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `tar gz overwrite replaces the original once packing completes`() {
        val archive = existingTarGz()
        val sources = listOf(source("a.txt", "alpha"), source("b.txt", "beta"))

        val packed = packTarGz(archive, sources)

        assertEquals(2, packed)
        val contents = mutableMapOf<String, String>()
        TarArchiveInputStream(GzipCompressorInputStream(Files.newInputStream(archive))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                contents[entry.name] = tar.readAllBytes().decodeToString()
                entry = tar.nextEntry
            }
        }
        assertEquals(mapOf("a.txt" to "alpha", "b.txt" to "beta"), contents)
        assertEquals(emptyList<String>(), leftovers())
    }
}
