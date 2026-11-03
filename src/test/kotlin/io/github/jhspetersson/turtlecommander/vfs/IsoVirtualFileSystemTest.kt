package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
    private fun writeIso(files: Map<String, ByteArray>, minSectors: Int = 0, emptyDirs: List<String> = emptyList()): Path {
        val iso = Files.createTempFile("test-iso-", ".iso")
        Files.write(iso, IsoImageFixture.build(files, minSectors, emptyDirs))
        return iso
    }

    private fun readEntry(vfs: IsoVirtualFileSystem, name: String): ByteArray {
        val entry = runBlocking { vfs.listFiles(vfs.root) }.first { !it.isParentLink && it.name.equals(name, ignoreCase = true) }
        vfs.materialize(entry.path)
        return Files.readAllBytes(entry.path)
    }

    @Test
    fun `image smaller than the UDF anchor area opens`() {
        val iso = writeIso(mapOf("HELLO.TXT" to "hello iso".toByteArray()))
        try {
            IsoVirtualFileSystem(iso).use { vfs ->
                assertEquals("hello iso", String(readEntry(vfs, "HELLO.TXT")))
            }
        } finally {
            Files.deleteIfExists(iso)
        }
    }

    @Test
    fun `image can be renamed and deleted while browsed`() {
        val iso = writeIso(mapOf("HELLO.TXT" to "hello iso".toByteArray()), minSectors = 300)
        val moved = iso.resolveSibling(iso.fileName.toString() + ".moved")
        try {
            IsoVirtualFileSystem(iso).use { vfs ->
                assertEquals("hello iso", String(readEntry(vfs, "HELLO.TXT")))
                Files.move(iso, moved, StandardCopyOption.ATOMIC_MOVE)
                Files.delete(moved)
                assertFalse(Files.exists(moved))
            }
        } finally {
            Files.deleteIfExists(iso)
            Files.deleteIfExists(moved)
        }
    }

    @Test
    fun `empty directory is listed as a directory`() {
        val iso = writeIso(mapOf("HELLO.TXT" to "hello iso".toByteArray()), emptyDirs = listOf("EMPTY"))
        try {
            IsoVirtualFileSystem(iso).use { vfs ->
                val entries = runBlocking { vfs.listFiles(vfs.root) }.filter { !it.isParentLink }
                val empty = entries.firstOrNull { it.name.equals("EMPTY", ignoreCase = true) }
                assertTrue("EMPTY missing from ${entries.map { it.name }}", empty != null)
                assertTrue(empty!!.isDirectory)
                assertTrue(Files.isDirectory(empty.path))
                assertEquals("hello iso", String(readEntry(vfs, "HELLO.TXT")))
            }
        } finally {
            Files.deleteIfExists(iso)
        }
    }
}
