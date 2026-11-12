package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date

class SevenZipMetadataTest {

    private val tempPaths = mutableListOf<Path>()
    private val created = Date(1_600_000_000_000L)
    private val accessed = Date(1_600_000_500_000L)
    private val modified = Date(1_600_001_000_000L)
    private val unixMode = 0b111_101_101
    private val attributes = 0x8000 or (unixMode shl 16) or 0x20

    @After
    fun cleanup() {
        for (p in tempPaths.reversed()) {
            runCatching { p.toFile().deleteRecursively() }
        }
    }

    private fun archiveWithMetadata(): Path {
        val archive = Files.createTempFile("7z-meta-", ".7z")
        tempPaths.add(archive)
        SevenZOutputFile(archive.toFile()).use { out ->
            for (name in listOf("script.sh", "data.txt")) {
                val entry = SevenZArchiveEntry().apply {
                    this.name = name
                    size = 3
                    hasWindowsAttributes = true
                    windowsAttributes = attributes
                    hasCreationDate = true
                    creationDate = created
                    hasAccessDate = true
                    accessDate = accessed
                    hasLastModifiedDate = true
                    lastModifiedDate = modified
                }
                out.putArchiveEntry(entry)
                out.write("abc".toByteArray())
                out.closeArchiveEntry()
            }
        }
        return archive
    }

    private fun entriesOf(archive: Path): Map<String, SevenZArchiveEntry> =
        SevenZFile.builder().setPath(archive).get().use { z -> generateSequence { z.nextEntry }.associateBy { it.name } }

    @Test
    fun `repack keeps attributes and creation and access times of untouched entries`() {
        val archive = archiveWithMetadata()
        val vfs = SevenZipVirtualFileSystem(archive)
        tempPaths.add(vfs.root)
        try {
            Files.writeString(vfs.root.resolve("data.txt"), "changed")
            vfs.flush()

            val script = entriesOf(archive).getValue("script.sh")
            assertTrue(script.hasWindowsAttributes)
            assertEquals(attributes, script.windowsAttributes)
            assertTrue(script.hasCreationDate)
            assertEquals(created, script.creationDate)
            assertTrue(script.hasAccessDate)
            assertEquals(accessed, script.accessDate)
            assertEquals("abc", String(Files.readAllBytes(vfs.root.resolve("script.sh"))))
        } finally {
            vfs.close()
        }
    }

    @Test
    fun `renamed entry keeps its recorded attributes`() = kotlinx.coroutines.runBlocking {
        val archive = archiveWithMetadata()
        val vfs = SevenZipVirtualFileSystem(archive)
        tempPaths.add(vfs.root)
        try {
            vfs.renameFile(vfs.root.resolve("script.sh"), "run.sh")
            vfs.flush()

            val run = entriesOf(archive).getValue("run.sh")
            assertTrue(run.hasWindowsAttributes)
            assertEquals(attributes, run.windowsAttributes)
            assertEquals(created, run.creationDate)
        } finally {
            vfs.close()
        }
    }

    @Test
    fun `unix mode stored in the attributes is shown as permissions`() {
        val archive = archiveWithMetadata()
        val vfs = SevenZipVirtualFileSystem(archive)
        tempPaths.add(vfs.root)
        try {
            assertEquals("rwxr-xr-x", vfs.entryMetadata(vfs.root.resolve("script.sh"))?.permissions)
        } finally {
            vfs.close()
        }
    }
}
