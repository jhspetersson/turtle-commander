package io.github.jhspetersson.turtlecommander.service

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

class ArchiveServicePackFromZipFsTest {

    private lateinit var dir: Path
    private lateinit var zipFs: FileSystem

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pack-zipfs-")
        val source = dir.resolve("source.zip")
        zipFs = FileSystems.newFileSystem(URI.create("jar:" + source.toUri()), mapOf("create" to "true"))
        Files.createDirectories(zipFs.getPath("/tree/nested"))
        Files.writeString(zipFs.getPath("/tree/a.txt"), "a")
        Files.writeString(zipFs.getPath("/tree/nested/b.txt"), "b")
        Files.writeString(zipFs.getPath("/single.txt"), "single")
    }

    @After
    fun tearDown() {
        zipFs.close()
        dir.toFile().deleteRecursively()
    }

    private fun sources() = listOf(zipFs.getPath("/tree"), zipFs.getPath("/single.txt"))

    @Test
    fun `zip pack accepts sources inside a zip file system`() {
        val archive = dir.resolve("out.zip")
        val errors = mutableListOf<Exception>()
        val packed = runBlocking {
            ArchiveService().packZip(archive, sources(), false, { _, _ -> }, { _, e -> errors += e }, { false })
        }
        assertEquals(emptyList<Exception>(), errors)
        assertEquals(3, packed)
        val names = ZipFile.builder().setPath(archive).get().use { zip -> zip.entries.asSequence().map { it.name.trimEnd('/') }.toSet() }
        assertEquals(setOf("tree", "tree/nested", "tree/a.txt", "tree/nested/b.txt", "single.txt"), names)
    }

    @Test
    fun `tar gz pack accepts sources inside a zip file system`() {
        val archive = dir.resolve("out.tar.gz")
        val errors = mutableListOf<Exception>()
        val packed = runBlocking {
            ArchiveService().packTarGz(archive, sources(), { _, _ -> }, { _, e -> errors += e }, { false })
        }
        assertEquals(emptyList<Exception>(), errors)
        assertEquals(3, packed)
        val names = TarArchiveInputStream(GzipCompressorInputStream(Files.newInputStream(archive))).use { tar ->
            generateSequence { tar.nextEntry }.map { it.name.trimEnd('/') }.toSet()
        }
        assertEquals(setOf("tree", "tree/nested", "tree/a.txt", "tree/nested/b.txt", "single.txt"), names)
    }
}
