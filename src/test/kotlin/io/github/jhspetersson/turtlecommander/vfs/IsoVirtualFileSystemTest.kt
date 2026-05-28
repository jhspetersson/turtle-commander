package io.github.jhspetersson.turtlecommander.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class IsoVirtualFileSystemTest {

    @Test
    fun `iso provider recognises iso extension`() {
        val provider = IsoFileSystemProvider()
        assertTrue(provider.supportsExtension("iso"))
    }

    @Test
    fun `iso provider rejects other extensions`() {
        val provider = IsoFileSystemProvider()
        assertFalse(provider.supportsExtension("img"))
        assertFalse(provider.supportsExtension("zip"))
        assertFalse(provider.supportsExtension(""))
    }

    @Test
    fun `iso provider exposes extension set`() {
        assertEquals(setOf("iso"), IsoFileSystemProvider.ARCHIVE_EXTENSIONS)
    }

    @Test
    fun `registry recognises iso via provider`() {
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("disc.iso"))
        assertTrue(VirtualFileSystemRegistry.supportsByExtension("DISC.ISO"))
    }

    @Test
    fun `non-iso file fails to open`() {
        // Opening a regular text file as an ISO must fail at construction so callers
        // can fall back / report an error rather than serve garbage.
        val notIso: Path = Files.createTempFile("not-an-iso-", ".iso")
        try {
            Files.write(notIso, "this is plainly not an ISO 9660 image".toByteArray())
            try {
                IsoVirtualFileSystem(notIso).close()
                fail("Expected construction to fail for a non-ISO file")
            } catch (_: Exception) {
                // expected — bad volume descriptor / EOF / etc.
            }
        } finally {
            Files.deleteIfExists(notIso)
        }
    }
}
