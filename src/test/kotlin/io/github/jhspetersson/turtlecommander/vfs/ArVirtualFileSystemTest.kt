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
import java.nio.file.attribute.FileTime

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

    private fun gnuArWithSymbolTable(): Path {
        fun header(name: String, size: Int): ByteArray =
            (name.padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) + "100644".padEnd(8) +
                size.toString().padEnd(10) + "`\n").toByteArray(Charsets.US_ASCII)
        fun member(name: String, data: ByteArray): ByteArray =
            header(name, data.size) + data + (if (data.size % 2 == 1) byteArrayOf('\n'.code.toByte()) else ByteArray(0))

        val symbolTable = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + "main".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val objectFile = "object bytes".toByteArray()
        val path = Files.createTempFile("gnu-symtab-", ".a")
        Files.write(path, "!<arch>\n".toByteArray(Charsets.US_ASCII) + member("/", symbolTable) + member("main.o/", objectFile))
        return path
    }

    @Test
    fun `opens a GNU archive whose first member is the slash symbol table`() = runBlocking {
        val path = gnuArWithSymbolTable()
        try {
            ArVirtualFileSystem(path).use { fs ->
                val names = fs.listFiles(fs.root).map { it.name }.filter { it != ".." }
                assertEquals(listOf("main.o"), names)
                assertArrayEquals("object bytes".toByteArray(), Files.readAllBytes(fs.root.resolve("main.o")))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `member names longer than 16 characters survive repack`() = runBlocking {
        val longName = "a-much-longer-member-name.txt"
        vfs.renameFile(vfs.root.resolve("hello.txt"), longName)

        Files.newInputStream(arPath).use { raw ->
            ArArchiveInputStream(raw).use { ar ->
                val entries = generateSequence { ar.nextEntry }.map { it.name to ar.readAllBytes().decodeToString() }.toList()
                assertEquals(setOf(longName, "second.bin"), entries.map { it.first }.toSet())
                assertEquals("Hello from ar", entries.first { it.first == longName }.second)
            }
        }
    }

    private fun archivedEntries(): List<ArArchiveEntry> =
        Files.newInputStream(arPath).use { raw ->
            ArArchiveInputStream(raw).use { ar -> generateSequence { ar.nextEntry }.toList() }
        }

    @Test
    fun `edited member gets its new timestamp on repack`() = runBlocking {
        val edited = vfs.root.resolve("hello.txt")
        Files.writeString(edited, "changed")
        Files.setLastModifiedTime(edited, FileTime.fromMillis(1_650_000_000_000L))
        vfs.flush()

        val entries = archivedEntries().associateBy { it.name }
        assertEquals(1_650_000_000L, entries.getValue("hello.txt").lastModified)
        assertEquals(1_700_000_000L, entries.getValue("second.bin").lastModified)
        assertEquals(0x81ED, entries.getValue("hello.txt").mode)
    }

    @Test
    fun `duplicate member names open read-only keeping the first member`() = runBlocking {
        val path = Files.createTempFile("duplicate-members-", ".a")
        try {
            ArArchiveOutputStream(Files.newOutputStream(path)).use { ar ->
                for (content in listOf("first", "second")) {
                    val bytes = content.toByteArray()
                    ar.putArchiveEntry(ArArchiveEntry("same.o", bytes.size.toLong()))
                    ar.write(bytes)
                    ar.closeArchiveEntry()
                }
            }
            ArVirtualFileSystem(path).use { fs ->
                assertEquals("first", Files.readString(fs.root.resolve("same.o")))
                assertTrue(fs.isReadOnly)
                assertEquals(listOf("same.o"), fs.unextractableEntries)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
