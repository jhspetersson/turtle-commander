package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.cpio.CpioConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class EscapingSymlinkRepackTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("escaping-symlink-repack-")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun tar(name: String, links: Map<String, String>, files: List<String>): Path {
        val path = dir.resolve(name)
        TarArchiveOutputStream(Files.newOutputStream(path)).use { out ->
            for ((linkName, target) in links) {
                val link = TarArchiveEntry(linkName, TarArchiveEntry.LF_SYMLINK)
                link.linkName = target
                out.putArchiveEntry(link)
                out.closeArchiveEntry()
            }
            for (file in files) {
                val bytes = "payload".toByteArray()
                val entry = TarArchiveEntry(file)
                entry.size = bytes.size.toLong()
                out.putArchiveEntry(entry)
                out.write(bytes)
                out.closeArchiveEntry()
            }
        }
        return path
    }

    private fun tarEntries(path: Path): List<Triple<String, Boolean, String>> =
        TarArchiveInputStream(Files.newInputStream(path)).use { tar ->
            generateSequence { tar.nextEntry }
                .map { Triple(it.name.trimEnd('/'), it.isSymbolicLink, it.linkName.orEmpty()) }
                .toList()
        }

    private fun cpio(name: String, links: Map<String, String>, files: List<String>): Path {
        val path = dir.resolve(name)
        CpioArchiveOutputStream(Files.newOutputStream(path)).use { out ->
            for ((linkName, target) in links) {
                val body = target.toByteArray()
                val entry = CpioArchiveEntry(linkName)
                entry.mode = (CpioConstants.C_ISLNK or 0x1FF).toLong()
                entry.size = body.size.toLong()
                out.putArchiveEntry(entry)
                out.write(body)
                out.closeArchiveEntry()
            }
            for (file in files) {
                val body = "payload".toByteArray()
                val entry = CpioArchiveEntry(file)
                entry.mode = (CpioConstants.C_ISREG or 0x1A4).toLong()
                entry.size = body.size.toLong()
                out.putArchiveEntry(entry)
                out.write(body)
                out.closeArchiveEntry()
            }
        }
        return path
    }

    private fun cpioLinks(path: Path): Map<String, String> {
        val links = linkedMapOf<String, String>()
        CpioArchiveInputStream(Files.newInputStream(path)).use { cpio ->
            var entry = cpio.nextEntry
            while (entry != null) {
                if (entry.isSymbolicLink) links[entry.name] = cpio.readBytes().toString(Charsets.UTF_8)
                entry = cpio.nextEntry
            }
        }
        return links
    }

    @Test
    fun `tar symlinks with absolute and upward targets survive a repack`() {
        val archive = tar("links.tar", mapOf("etc/mtab" to "/proc/mounts", "up" to "../outside"), listOf("a.txt"))

        TarVirtualFileSystem(archive, Files::newInputStream, Files::newOutputStream).use { vfs ->
            assertFalse(vfs.isReadOnly)
            assertTrue(Files.notExists(vfs.root.resolve("etc/mtab")))
            Files.writeString(vfs.root.resolve("new.txt"), "x")
            vfs.flush()
        }

        val entries = tarEntries(archive)
        assertTrue(entries.toString(), Triple("etc/mtab", true, "/proc/mounts") in entries)
        assertTrue(entries.toString(), Triple("up", true, "../outside") in entries)
        assertTrue(entries.toString(), entries.any { it.first == "new.txt" })
        assertTrue(entries.toString(), entries.any { it.first == "a.txt" })
    }

    @Test
    fun `tar escaping symlink shadowed by a real entry is not written twice`() {
        val archive = tar("shadowed.tar", mapOf("escape" to "/somewhere/else"), listOf("escape/child.txt"))

        TarVirtualFileSystem(archive, Files::newInputStream, Files::newOutputStream).use { vfs ->
            Files.writeString(vfs.root.resolve("new.txt"), "x")
            vfs.flush()
        }

        assertEquals(1, tarEntries(archive).count { it.first == "escape" })
    }

    @Test
    fun `cpio symlinks with absolute and upward targets survive a repack`() {
        val archive = cpio("links.cpio", mapOf("etc/mtab" to "/proc/mounts", "up" to "../outside"), listOf("a.txt"))

        CpioVirtualFileSystem(archive).use { vfs ->
            assertFalse(vfs.isReadOnly)
            Files.writeString(vfs.root.resolve("new.txt"), "x")
            vfs.flush()
        }

        assertEquals(mapOf("etc/mtab" to "/proc/mounts", "up" to "../outside"), cpioLinks(archive))
    }
}
