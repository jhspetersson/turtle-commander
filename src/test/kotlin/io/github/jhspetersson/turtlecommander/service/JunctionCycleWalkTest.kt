package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.util.SystemInfo
import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import io.github.jhspetersson.turtlecommander.dialog.NamePatternMode
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

class JunctionCycleWalkTest {

    private lateinit var holder: Path
    private lateinit var root: Path
    private lateinit var loop: Path

    @Before
    fun setUp() {
        assumeTrue(SystemInfo.isWindows)
        holder = Files.createTempDirectory("junction-cycle-walk-")
        root = Files.createDirectories(holder.resolve("root"))
        Files.writeString(root.resolve("a.txt"), "a")
        Files.writeString(Files.createDirectories(root.resolve("sub")).resolve("b.txt"), "b")
        loop = root.resolve("sub").resolve("loop")
        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", loop.toString(), root.toString())
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        assumeTrue(process.waitFor() == 0)
        val attrs = Files.readAttributes(loop, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        assumeTrue(attrs.isDirectory && attrs.isOther)
    }

    @After
    fun tearDown() {
        if (!::holder.isInitialized) return
        if (::loop.isInitialized) Files.deleteIfExists(loop)
        holder.toFile().deleteRecursively()
    }

    @Test
    fun `search finds each file once`() {
        val criteria = FileSearchCriteria(
            rootPath = root,
            namePattern = "*.txt",
            namePatternMode = NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )
        val found = mutableListOf<Path>()

        FileSearchService(criteria).search({ found.add(it.path) }, { false }, { _, _ -> })

        assertEquals(listOf(root.resolve("a.txt"), root.resolve("sub").resolve("b.txt")), found.sorted())
    }

    @Test
    fun `zip packs each file once`() {
        val archive = holder.resolve("out.zip")

        runBlocking { ArchiveService().packZip(archive, listOf(root), false, { _, _ -> }, { _, _ -> }, { false }) }

        val names = ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        }
        assertEquals(listOf("root/a.txt", "root/sub/b.txt"), names.sorted())
    }

    @Test
    fun `tar gz packs each file once`() {
        val archive = holder.resolve("out.tar.gz")

        runBlocking { ArchiveService().packTarGz(archive, listOf(root), { _, _ -> }, { _, _ -> }, { false }) }

        val names = TarArchiveInputStream(GZIPInputStream(Files.newInputStream(archive))).use { tar ->
            generateSequence { tar.nextEntry }.filter { !it.isDirectory }.map { it.name }.toList()
        }
        assertEquals(listOf("root/a.txt", "root/sub/b.txt"), names.sorted())
    }
}
