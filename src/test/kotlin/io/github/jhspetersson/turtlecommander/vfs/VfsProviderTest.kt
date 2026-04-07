package io.github.jhspetersson.turtlecommander.vfs

import org.junit.Assert.*
import org.junit.Test

class VfsProviderTest {

    // --- ZipFileSystemProvider ---

    private val zipProvider = ZipFileSystemProvider()

    @Test
    fun `zip provider supports zip extension`() {
        assertTrue(zipProvider.supportsExtension("zip"))
    }

    @Test
    fun `zip provider supports jar extension`() {
        assertTrue(zipProvider.supportsExtension("jar"))
    }

    @Test
    fun `zip provider supports war extension`() {
        assertTrue(zipProvider.supportsExtension("war"))
    }

    @Test
    fun `zip provider supports ear extension`() {
        assertTrue(zipProvider.supportsExtension("ear"))
    }

    @Test
    fun `zip provider does not support tar`() {
        assertFalse(zipProvider.supportsExtension("tar"))
    }

    @Test
    fun `zip provider does not support gz`() {
        assertFalse(zipProvider.supportsExtension("gz"))
    }

    @Test
    fun `zip provider is case sensitive on extension`() {
        assertFalse(zipProvider.supportsExtension("ZIP"))
    }

    // --- TarFileSystemProvider ---

    private val tarProvider = TarFileSystemProvider()

    @Test
    fun `tar provider supports tar extension`() {
        assertTrue(tarProvider.supportsExtension("tar"))
    }

    @Test
    fun `tar provider does not support gz`() {
        assertFalse(tarProvider.supportsExtension("gz"))
    }

    @Test
    fun `tar provider does not support tgz`() {
        assertFalse(tarProvider.supportsExtension("tgz"))
    }

    @Test
    fun `tar provider does not support zip`() {
        assertFalse(tarProvider.supportsExtension("zip"))
    }

    // --- GzFileSystemProvider ---

    private val gzProvider = GzFileSystemProvider()

    @Test
    fun `gz provider supports gz extension`() {
        assertTrue(gzProvider.supportsExtension("gz"))
    }

    @Test
    fun `gz provider supports tgz extension`() {
        assertTrue(gzProvider.supportsExtension("tgz"))
    }

    @Test
    fun `gz provider does not support tar`() {
        assertFalse(gzProvider.supportsExtension("tar"))
    }

    @Test
    fun `gz provider does not support zip`() {
        assertFalse(gzProvider.supportsExtension("zip"))
    }

    @Test
    fun `gz provider does not support bz2`() {
        assertFalse(gzProvider.supportsExtension("bz2"))
    }

    // --- Bz2FileSystemProvider ---

    private val bz2Provider = Bz2FileSystemProvider()

    @Test
    fun `bz2 provider supports bz2 extension`() {
        assertTrue(bz2Provider.supportsExtension("bz2"))
    }

    @Test
    fun `bz2 provider supports tbz2 extension`() {
        assertTrue(bz2Provider.supportsExtension("tbz2"))
    }

    @Test
    fun `bz2 provider supports tbz extension`() {
        assertTrue(bz2Provider.supportsExtension("tbz"))
    }

    @Test
    fun `bz2 provider does not support gz`() {
        assertFalse(bz2Provider.supportsExtension("gz"))
    }

    @Test
    fun `bz2 provider does not support tar`() {
        assertFalse(bz2Provider.supportsExtension("tar"))
    }

    // --- SevenZipFileSystemProvider ---

    private val sevenZipProvider = SevenZipFileSystemProvider()

    @Test
    fun `7zip provider supports 7z extension`() {
        assertTrue(sevenZipProvider.supportsExtension("7z"))
    }

    @Test
    fun `7zip provider does not support zip`() {
        assertFalse(sevenZipProvider.supportsExtension("zip"))
    }

    @Test
    fun `7zip provider does not support tar`() {
        assertFalse(sevenZipProvider.supportsExtension("tar"))
    }

    @Test
    fun `7zip provider does not support gz`() {
        assertFalse(sevenZipProvider.supportsExtension("gz"))
    }

    // --- ArFileSystemProvider ---

    private val arProvider = ArFileSystemProvider()

    @Test
    fun `ar provider supports ar extension`() {
        assertTrue(arProvider.supportsExtension("ar"))
    }

    @Test
    fun `ar provider supports a extension`() {
        assertTrue(arProvider.supportsExtension("a"))
    }

    @Test
    fun `ar provider supports deb extension`() {
        assertTrue(arProvider.supportsExtension("deb"))
    }

    @Test
    fun `ar provider does not support zip`() {
        assertFalse(arProvider.supportsExtension("zip"))
    }

    @Test
    fun `ar provider does not support tar`() {
        assertFalse(arProvider.supportsExtension("tar"))
    }

    // --- XzFileSystemProvider ---

    private val xzProvider = XzFileSystemProvider()

    @Test
    fun `xz provider supports xz extension`() {
        assertTrue(xzProvider.supportsExtension("xz"))
    }

    @Test
    fun `xz provider supports txz extension`() {
        assertTrue(xzProvider.supportsExtension("txz"))
    }

    @Test
    fun `xz provider does not support gz`() {
        assertFalse(xzProvider.supportsExtension("gz"))
    }

    @Test
    fun `xz provider does not support tar`() {
        assertFalse(xzProvider.supportsExtension("tar"))
    }

    @Test
    fun `xz provider does not support zip`() {
        assertFalse(xzProvider.supportsExtension("zip"))
    }

    // --- forEachArchiveEntry ---

    @Test
    fun `forEachArchiveEntry visits all entries with relative names`() {
        val dir = java.nio.file.Files.createTempDirectory("vfs-test-")
        try {
            java.nio.file.Files.createDirectories(dir.resolve("sub"))
            java.nio.file.Files.createFile(dir.resolve("file.txt"))
            java.nio.file.Files.createFile(dir.resolve("sub/nested.txt"))

            val visited = mutableListOf<String>()
            forEachArchiveEntry(dir) { _, relativeName ->
                visited.add(relativeName)
            }
            assertTrue("should visit sub", visited.contains("sub"))
            assertTrue("should visit file.txt", visited.contains("file.txt"))
            assertTrue("should visit sub/nested.txt", visited.contains("sub/nested.txt"))
            assertEquals(3, visited.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `forEachArchiveEntry skips root directory itself`() {
        val dir = java.nio.file.Files.createTempDirectory("vfs-test-")
        try {
            val visited = mutableListOf<String>()
            forEachArchiveEntry(dir) { _, relativeName ->
                visited.add(relativeName)
            }
            assertTrue(visited.isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // --- readDirectoryEntries ---

    @Test
    fun `readDirectoryEntries returns dirs before files sorted by name`() {
        val dir = java.nio.file.Files.createTempDirectory("vfs-test-")
        try {
            java.nio.file.Files.createFile(dir.resolve("banana.txt"))
            java.nio.file.Files.createFile(dir.resolve("apple.txt"))
            java.nio.file.Files.createDirectories(dir.resolve("zebra"))
            java.nio.file.Files.createDirectories(dir.resolve("alpha"))

            val entries = readDirectoryEntries(dir)
            assertEquals(4, entries.size)
            // dirs first, sorted
            assertEquals("alpha", entries[0].name)
            assertTrue(entries[0].isDirectory)
            assertEquals("zebra", entries[1].name)
            assertTrue(entries[1].isDirectory)
            // files next, sorted
            assertEquals("apple.txt", entries[2].name)
            assertFalse(entries[2].isDirectory)
            assertEquals("banana.txt", entries[3].name)
            assertFalse(entries[3].isDirectory)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `readDirectoryEntries returns empty list for empty directory`() {
        val dir = java.nio.file.Files.createTempDirectory("vfs-test-")
        try {
            val entries = readDirectoryEntries(dir)
            assertTrue(entries.isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // --- parentEntry ---

    @Test
    fun `parentEntry creates correct entry`() {
        val path = java.nio.file.Path.of("/some/dir")
        val entry = parentEntry(path)
        assertEquals("..", entry.name)
        assertEquals(path, entry.path)
        assertTrue(entry.isDirectory)
        assertEquals(0L, entry.size)
        assertNull(entry.lastModified)
        assertEquals("", entry.permissions)
        assertTrue(entry.isParentLink)
    }

}
