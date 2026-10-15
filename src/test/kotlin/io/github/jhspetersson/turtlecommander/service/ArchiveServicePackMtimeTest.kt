package io.github.jhspetersson.turtlecommander.service

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.math.abs

class ArchiveServicePackMtimeTest {

    private val stamp = 1_500_000_000_000L
    private val dirStamp = 1_400_000_000_000L
    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pack-mtime-")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun stamped(path: Path, millis: Long): Path {
        Files.setLastModifiedTime(path, FileTime.fromMillis(millis))
        return path
    }

    private fun sourceFile(name: String): Path = stamped(Files.writeString(dir.resolve(name), "content of $name"), stamp)

    private fun sourceTree(): Path {
        val root = Files.createDirectories(dir.resolve("tree"))
        val nested = Files.createDirectories(root.resolve("nested"))
        stamped(Files.writeString(root.resolve("a.txt"), "a"), stamp)
        stamped(Files.writeString(nested.resolve("b.txt"), "b"), stamp)
        stamped(nested, dirStamp)
        stamped(root, dirStamp)
        return root
    }

    private fun packZip(archive: Path, sources: List<Path>, append: Boolean = false): Int = runBlocking {
        ArchiveService().packZip(archive, sources, append, onProgress = { _, _ -> }, onError = { _, _ -> }, isCancelled = { false })
    }

    private fun packTarGz(archive: Path, sources: List<Path>): Int = runBlocking {
        ArchiveService().packTarGz(archive, sources, onProgress = { _, _ -> }, onError = { _, _ -> }, isCancelled = { false })
    }

    private fun zipMtimes(archive: Path): Map<String, Long> = ZipFile.builder().setPath(archive).get().use { zip ->
        zip.entries.asSequence().associate { it.name to it.lastModifiedTime.toMillis() }
    }

    private fun tarMtimes(archive: Path): Map<String, Long> =
        TarArchiveInputStream(GzipCompressorInputStream(Files.newInputStream(archive))).use { tar ->
            generateSequence { tar.nextEntry }.associate { it.name to it.lastModifiedTime.toMillis() }
        }

    private fun assertStamp(expected: Long, actual: Long?, name: String) {
        assertTrue("$name has no mtime", actual != null)
        assertTrue("$name mtime ${FileTime.fromMillis(actual!!)} differs from ${FileTime.fromMillis(expected)}", abs(actual - expected) <= 2_000)
    }

    @Test
    fun `packZip keeps the source mtime of a single file`() {
        val archive = dir.resolve("single.zip")

        assertEquals(1, packZip(archive, listOf(sourceFile("one.txt"))))

        assertStamp(stamp, zipMtimes(archive)["one.txt"], "one.txt")
    }

    @Test
    fun `packZip keeps file and directory mtimes of a tree`() {
        val archive = dir.resolve("tree.zip")

        assertEquals(2, packZip(archive, listOf(sourceTree())))

        val mtimes = zipMtimes(archive)
        assertStamp(stamp, mtimes["tree/a.txt"], "tree/a.txt")
        assertStamp(stamp, mtimes["tree/nested/b.txt"], "tree/nested/b.txt")
        assertStamp(dirStamp, mtimes["tree/"], "tree/")
        assertStamp(dirStamp, mtimes["tree/nested/"], "tree/nested/")
    }

    @Test
    fun `packZip append keeps the source mtime of the added file`() {
        val archive = dir.resolve("append.zip")
        packZip(archive, listOf(sourceFile("first.txt")))

        assertEquals(1, packZip(archive, listOf(sourceFile("second.txt")), append = true))

        val mtimes = zipMtimes(archive)
        assertStamp(stamp, mtimes["first.txt"], "first.txt")
        assertStamp(stamp, mtimes["second.txt"], "second.txt")
    }

    @Test
    fun `packTarGz keeps file and directory mtimes`() {
        val archive = dir.resolve("tree.tar.gz")

        assertEquals(2, packTarGz(archive, listOf(sourceTree())))

        val mtimes = tarMtimes(archive)
        assertStamp(stamp, mtimes["tree/a.txt"], "tree/a.txt")
        assertStamp(stamp, mtimes["tree/nested/b.txt"], "tree/nested/b.txt")
        assertStamp(dirStamp, mtimes["tree/"], "tree/")
        assertStamp(dirStamp, mtimes["tree/nested/"], "tree/nested/")
    }
}
