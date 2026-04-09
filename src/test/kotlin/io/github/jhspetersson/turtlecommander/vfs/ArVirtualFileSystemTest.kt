package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ArVirtualFileSystemTest {

    private lateinit var arPath: Path
    private lateinit var vfs: ArVirtualFileSystem

    @Before
    fun setUp() {
        arPath = Files.createTempFile("test-archive-", ".ar")
        ArArchiveOutputStream(Files.newOutputStream(arPath)).use { ar ->
            val content = "Hello from ar".toByteArray()
            // Non-default metadata so we can verify it round-trips.
            val entry = ArArchiveEntry(
                "hello.txt",
                content.size.toLong(),
                1234,
                5678,
                0x81ED, // 0100755 rwxr-xr-x
                1_600_000_000L,
            )
            ar.putArchiveEntry(entry)
            ar.write(content)
            ar.closeArchiveEntry()

            val second = "second".toByteArray()
            val entry2 = ArArchiveEntry(
                "second.bin",
                second.size.toLong(),
                4242,
                2424,
                0x81A0, // 0100640 rw-r-----
                1_700_000_000L,
            )
            ar.putArchiveEntry(entry2)
            ar.write(second)
            ar.closeArchiveEntry()
        }
        vfs = ArVirtualFileSystem(arPath)
    }

    @After
    fun tearDown() {
        vfs.close()
        Files.deleteIfExists(arPath)
    }

    @Test
    fun `rename preserves original mode uid gid and mtime of untouched entries`() = runBlocking {
        vfs.renameFile(vfs.root.resolve("hello.txt"), "renamed.txt")

        Files.newInputStream(arPath).use { raw ->
            ArArchiveInputStream(raw).use { ar ->
                val entries = generateSequence { ar.nextEntry }.toList()
                val second = entries.find { it.name == "second.bin" }
                assertNotNull("second.bin should still exist", second)
                assertEquals("mode must survive repack", 0x81A0, second!!.mode)
                assertEquals("uid must survive repack", 4242, second.userId)
                assertEquals("gid must survive repack", 2424, second.groupId)
                assertEquals("mtime must survive repack", 1_700_000_000L, second.lastModified)
            }
        }
    }

    @Test
    fun `rename carries metadata over to new name`() = runBlocking {
        vfs.renameFile(vfs.root.resolve("hello.txt"), "renamed.txt")

        Files.newInputStream(arPath).use { raw ->
            ArArchiveInputStream(raw).use { ar ->
                val entries = generateSequence { ar.nextEntry }.toList()
                val renamed = entries.find { it.name == "renamed.txt" }
                assertNotNull("renamed entry should exist", renamed)
                assertEquals("renamed entry must keep original mode", 0x81ED, renamed!!.mode)
                assertEquals("renamed entry must keep original uid", 1234, renamed.userId)
                assertEquals("renamed entry must keep original gid", 5678, renamed.groupId)
                assertEquals("renamed entry must keep original mtime", 1_600_000_000L, renamed.lastModified)
            }
        }
    }
}
