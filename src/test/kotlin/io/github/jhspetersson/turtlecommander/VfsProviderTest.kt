package io.github.jhspetersson.turtlecommander

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

    // --- ARCHIVE_EXTENSIONS constants ---

    @Test
    fun `zip archive extensions set has correct count`() {
        assertEquals(4, ZipFileSystemProvider.ARCHIVE_EXTENSIONS.size)
    }

    @Test
    fun `tar archive extensions set has correct count`() {
        assertEquals(1, TarFileSystemProvider.ARCHIVE_EXTENSIONS.size)
    }

    @Test
    fun `gz archive extensions set has correct count`() {
        assertEquals(2, GzFileSystemProvider.ARCHIVE_EXTENSIONS.size)
    }

    @Test
    fun `bz2 archive extensions set has correct count`() {
        assertEquals(3, Bz2FileSystemProvider.ARCHIVE_EXTENSIONS.size)
    }

    @Test
    fun `7zip archive extensions set has correct count`() {
        assertEquals(1, SevenZipFileSystemProvider.ARCHIVE_EXTENSIONS.size)
    }
}
