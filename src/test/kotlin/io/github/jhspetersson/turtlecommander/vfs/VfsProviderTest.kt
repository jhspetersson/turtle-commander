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
    fun `zip provider supports office open xml extensions`() {
        assertTrue(zipProvider.supportsExtension("docx"))
        assertTrue(zipProvider.supportsExtension("xlsx"))
        assertTrue(zipProvider.supportsExtension("pptx"))
    }

    @Test
    fun `zip provider supports opendocument extensions`() {
        assertTrue(zipProvider.supportsExtension("odt"))
        assertTrue(zipProvider.supportsExtension("ods"))
        assertTrue(zipProvider.supportsExtension("odp"))
        assertTrue(zipProvider.supportsExtension("odg"))
    }

    @Test
    fun `zip provider supports ebook extensions`() {
        assertTrue(zipProvider.supportsExtension("epub"))
        assertTrue(zipProvider.supportsExtension("cbz"))
    }

    @Test
    fun `zip provider supports nupkg extension`() {
        assertTrue(zipProvider.supportsExtension("nupkg"))
    }

    @Test
    fun `zip provider supports xpi extension`() {
        // Firefox extensions are plain ZIPs.
        assertTrue(zipProvider.supportsExtension("xpi"))
    }

    @Test
    fun `zip provider does not support crx`() {
        // Chrome .crx has a signed header in front of the ZIP — handled by CrxFileSystemProvider.
        assertFalse(zipProvider.supportsExtension("crx"))
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

    // --- CrxFileSystemProvider ---

    private val crxProvider = CrxFileSystemProvider()

    @Test
    fun `crx provider supports crx extension`() {
        assertTrue(crxProvider.supportsExtension("crx"))
    }

    @Test
    fun `crx provider does not support zip`() {
        assertFalse(crxProvider.supportsExtension("zip"))
    }

    @Test
    fun `crx provider does not support xpi`() {
        // .xpi is plain ZIP, routed through ZipFileSystemProvider.
        assertFalse(crxProvider.supportsExtension("xpi"))
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

    @Test
    fun `forEachArchiveEntry visits parents before their children`() {
        val dir = java.nio.file.Files.createTempDirectory("vfs-test-")
        try {
            java.nio.file.Files.createDirectories(dir.resolve("a/b/c"))
            java.nio.file.Files.createFile(dir.resolve("a/b/c/deep.txt"))
            java.nio.file.Files.createFile(dir.resolve("a/b/mid.txt"))
            java.nio.file.Files.createFile(dir.resolve("a/top.txt"))

            val visited = mutableListOf<String>()
            forEachArchiveEntry(dir) { _, relativeName ->
                visited.add(relativeName)
            }
            // Every directory must appear before any of its descendants.
            for ((i, name) in visited.withIndex()) {
                if (name.contains('/')) {
                    val parent = name.substringBeforeLast('/')
                    val parentIdx = visited.indexOf(parent)
                    assertTrue(
                        "$parent must precede $name (parent=$parentIdx, child=$i)",
                        parentIdx in 0 until i,
                    )
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `forEachArchiveEntry iteration order is stable across runs`() {
        val dir = java.nio.file.Files.createTempDirectory("vfs-test-")
        try {
            // Create in a deliberately scrambled order; the traversal should still be sorted.
            java.nio.file.Files.createFile(dir.resolve("zebra.txt"))
            java.nio.file.Files.createFile(dir.resolve("apple.txt"))
            java.nio.file.Files.createDirectories(dir.resolve("mango"))
            java.nio.file.Files.createFile(dir.resolve("mango/inner.txt"))
            java.nio.file.Files.createFile(dir.resolve("banana.txt"))

            val first = mutableListOf<String>()
            forEachArchiveEntry(dir) { _, relativeName -> first.add(relativeName) }
            val second = mutableListOf<String>()
            forEachArchiveEntry(dir) { _, relativeName -> second.add(relativeName) }

            assertEquals("iteration must be deterministic across runs", first, second)
            assertEquals(
                "iteration must be sorted",
                first.sorted(),
                first,
            )
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

    // --- default VirtualFileSystemProvider.supports ---

    @Test
    fun `default supports delegates to supportsExtension for regular files`() {
        // Concrete providers used to duplicate this logic; now the interface default
        // handles path→extension parsing and only supportsExtension is per-provider.
        val dir = java.nio.file.Files.createTempDirectory("supports-test-")
        try {
            val zipFile = java.nio.file.Files.createFile(dir.resolve("thing.ZIP"))
            val txtFile = java.nio.file.Files.createFile(dir.resolve("thing.txt"))
            val noExt = java.nio.file.Files.createFile(dir.resolve("plain"))
            assertTrue("extension match should accept the file", zipProvider.supports(zipFile))
            assertFalse("unrelated extension should be rejected", zipProvider.supports(txtFile))
            assertFalse("missing extension should be rejected", zipProvider.supports(noExt))
            // The path must point to a regular file — directories are not archives.
            val subDir = java.nio.file.Files.createDirectory(dir.resolve("nested.zip"))
            assertFalse("directories should never be treated as archives", zipProvider.supports(subDir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
