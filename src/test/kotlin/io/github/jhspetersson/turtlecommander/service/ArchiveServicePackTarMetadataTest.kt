package io.github.jhspetersson.turtlecommander.service

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ArchiveServicePackTarMetadataTest {

    private lateinit var dir: Path
    private val errors = mutableListOf<Path>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pack-tar-meta-")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun packTarGz(archive: Path, sources: List<Path>): Int = runBlocking {
        ArchiveService().packTarGz(archive, sources, onProgress = { _, _ -> }, onError = { path, _ -> errors.add(path) }, isCancelled = { false })
    }

    private fun entries(archive: Path): Map<String, TarArchiveEntry> =
        TarArchiveInputStream(GzipCompressorInputStream(Files.newInputStream(archive))).use { tar ->
            generateSequence { tar.nextEntry }.associateBy { it.name }
        }

    private fun symlink(link: Path, target: Path): Path {
        val created = runCatching { Files.createSymbolicLink(link, target) }
        assumeTrue("symlinks not creatable here: ${created.exceptionOrNull()?.message}", created.isSuccess)
        return link
    }

    private fun tree(): Path {
        val root = Files.createDirectories(dir.resolve("tree"))
        Files.writeString(root.resolve("target.txt"), "target")
        Files.createDirectories(root.resolve("sub"))
        Files.writeString(root.resolve("sub/inner.txt"), "inner")
        return root
    }

    @Test
    fun `packTarGz stores file and directory symlinks as link entries`() {
        val root = tree()
        symlink(root.resolve("file-link"), Path.of("target.txt"))
        symlink(root.resolve("dir-link"), Path.of("sub"))
        val archive = dir.resolve("links.tar.gz")

        assertEquals(4, packTarGz(archive, listOf(root)))

        val entries = entries(archive)
        assertEquals(emptyList<Path>(), errors)
        assertTrue(entries.getValue("tree/file-link").isSymbolicLink)
        assertEquals("target.txt", entries.getValue("tree/file-link").linkName)
        assertTrue(entries.getValue("tree/dir-link").isSymbolicLink)
        assertEquals("sub", entries.getValue("tree/dir-link").linkName)
        assertTrue(entries.getValue("tree/target.txt").isFile)
        assertEquals(6L, entries.getValue("tree/target.txt").size)
        assertFalse(entries.keys.any { it.startsWith("tree/dir-link/") })
    }

    @Test
    fun `packTarGz stores a dangling symlink given as a top-level source`() {
        val link = symlink(dir.resolve("dangling"), Path.of("missing.txt"))
        val archive = dir.resolve("dangling.tar.gz")

        assertEquals(1, packTarGz(archive, listOf(link)))

        val entry = entries(archive).getValue("dangling")
        assertEquals(emptyList<Path>(), errors)
        assertTrue(entry.isSymbolicLink)
        assertEquals("missing.txt", entry.linkName)
    }

    @Test
    fun `packTarGz stores posix modes of files and directories`() {
        val root = tree()
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(root.resolve("target.txt"), PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.setPosixFilePermissions(root.resolve("sub/inner.txt"), PosixFilePermissions.fromString("rw-------"))
        Files.setPosixFilePermissions(root.resolve("sub"), PosixFilePermissions.fromString("rwx------"))
        val archive = dir.resolve("modes.tar.gz")

        assertEquals(2, packTarGz(archive, listOf(root)))

        val entries = entries(archive)
        assertEquals(493, entries.getValue("tree/target.txt").mode)
        assertEquals(384, entries.getValue("tree/sub/inner.txt").mode)
        assertEquals(448, entries.getValue("tree/sub/").mode)
    }
}
