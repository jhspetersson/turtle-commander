package io.github.jhspetersson.turtlecommander.service

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

class ArchiveServicePackSameNameTest {

    private lateinit var dir: Path
    private val errors = mutableListOf<Path>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pack-same-name-")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun file(relative: String, content: String): Path {
        val path = dir.resolve(relative)
        Files.createDirectories(path.parent)
        return Files.writeString(path, content)
    }

    private fun packZip(sources: List<Path>): Pair<Int, Map<String, String>> {
        val archive = dir.resolve("out.zip")
        val packed = runBlocking {
            ArchiveService().packZip(archive, sources, false, { _, _ -> }, { path, _ -> errors.add(path) }, { false })
        }
        val contents = ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }
                .associate { it.name to zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
        }
        return packed to contents
    }

    private fun packTarGz(sources: List<Path>): Pair<Int, List<Pair<String, String>>> {
        val archive = dir.resolve("out.tar.gz")
        val packed = runBlocking {
            ArchiveService().packTarGz(archive, sources, { _, _ -> }, { path, _ -> errors.add(path) }, { false })
        }
        val contents = TarArchiveInputStream(GZIPInputStream(Files.newInputStream(archive))).use { tar ->
            generateSequence { tar.nextEntry }.filter { !it.isDirectory }
                .map { it.name to tar.readBytes().toString(Charsets.UTF_8) }.toList()
        }
        return packed to contents
    }

    @Test
    fun `zip keeps same-named files from different folders apart`() {
        val sources = listOf(file("a/x.txt", "from a"), file("b/x.txt", "from b"))

        val (packed, contents) = packZip(sources)

        assertEquals(2, packed)
        assertEquals(mapOf("a/x.txt" to "from a", "b/x.txt" to "from b"), contents)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `zip keeps same-named folders from different parents apart`() {
        val one = file("a/docs/readme.txt", "from a").parent
        val two = file("b/docs/readme.txt", "from b").parent

        val (packed, contents) = packZip(listOf(one, two))

        assertEquals(2, packed)
        assertEquals(mapOf("a/docs/readme.txt" to "from a", "b/docs/readme.txt" to "from b"), contents)
    }

    @Test
    fun `zip still stores bare names when they are all different`() {
        val sources = listOf(file("a/x.txt", "from a"), file("b/y.txt", "from b"))

        val (packed, contents) = packZip(sources)

        assertEquals(2, packed)
        assertEquals(mapOf("x.txt" to "from a", "y.txt" to "from b"), contents)
    }

    @Test
    fun `tar gz keeps same-named files from different folders apart`() {
        val sources = listOf(file("a/x.txt", "from a"), file("b/x.txt", "from b"), file("a/deep/x.txt", "from deep"))

        val (packed, contents) = packTarGz(sources)

        assertEquals(3, packed)
        assertEquals(
            listOf("a/x.txt" to "from a", "b/x.txt" to "from b", "a/deep/x.txt" to "from deep"),
            contents,
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `a source listed twice is packed once without an error`() {
        val source = file("a/x.txt", "from a")

        val (packed, contents) = packZip(listOf(source, source))

        assertEquals(2, packed)
        assertEquals(mapOf("x.txt" to "from a"), contents)
        assertTrue(errors.isEmpty())
    }
}
