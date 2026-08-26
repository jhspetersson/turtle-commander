package io.github.jhspetersson.turtlecommander.service

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ArchiveServicePackZipTest {

    private val tempFiles = mutableListOf<Path>()

    @After
    fun cleanup() {
        for (path in tempFiles.reversed()) {
            runCatching { Files.deleteIfExists(path) }
        }
    }

    private fun tempFile(prefix: String, suffix: String, content: String? = null): Path {
        val path = Files.createTempFile(prefix, suffix)
        tempFiles.add(path)
        if (content != null) Files.write(path, content.toByteArray())
        return path
    }

    private fun zipEntryNames(zip: Path): Set<String> =
        ZipFile(zip.toFile()).use { it.entries().asSequence().map(ZipEntry::getName).toSet() }

    private fun pack(archive: Path, sources: List<Path>, append: Boolean, exists: Boolean): Int =
        runBlocking {
            ArchiveService().packZip(
                archive, sources, append, exists,
                onProgress = { _, _ -> },
                onError = { path, error -> throw AssertionError("pack failed for $path", error) },
                isCancelled = { false },
            )
        }

    @Test
    fun `append creates the archive when the target vanished after the exists check`() {
        val source = tempFile("pack-src-", ".txt", "hello")
        val archive = tempFile("pack-", ".zip")
        Files.delete(archive)

        val packed = pack(archive, listOf(source), append = true, exists = true)

        assertEquals(1, packed)
        assertTrue(Files.exists(archive))
        assertEquals(setOf(source.fileName.toString()), zipEntryNames(archive))
    }

    @Test
    fun `append preserves existing entries and adds new ones`() {
        val source = tempFile("pack-src-", ".txt", "new")
        val archive = tempFile("pack-", ".zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            zos.putNextEntry(ZipEntry("old.txt"))
            zos.write("old".toByteArray())
            zos.closeEntry()
        }

        val packed = pack(archive, listOf(source), append = true, exists = true)

        assertEquals(1, packed)
        assertEquals(setOf("old.txt", source.fileName.toString()), zipEntryNames(archive))
    }

    @Test
    fun `overwrite replaces an existing archive`() {
        val source = tempFile("pack-src-", ".txt", "new")
        val archive = tempFile("pack-", ".zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            zos.putNextEntry(ZipEntry("old.txt"))
            zos.write("old".toByteArray())
            zos.closeEntry()
        }

        val packed = pack(archive, listOf(source), append = false, exists = true)

        assertEquals(1, packed)
        assertEquals(setOf(source.fileName.toString()), zipEntryNames(archive))
    }
}
