package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TarMaterializeThroughSymlinkTest {

    private lateinit var dir: Path
    private lateinit var vfs: TarVirtualFileSystem

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tar-materialize-through-symlink-")
        val archive = dir.resolve("links.tar")
        TarArchiveOutputStream(Files.newOutputStream(archive)).use { out ->
            for (name in listOf("real/one.txt", "real/two.txt", "real/three.txt")) {
                val bytes = "content of $name".toByteArray()
                val entry = TarArchiveEntry(name)
                entry.size = bytes.size.toLong()
                out.putArchiveEntry(entry)
                out.write(bytes)
                out.closeArchiveEntry()
            }
            for ((name, target) in listOf("alias" to "real", "link.txt" to "real/two.txt")) {
                val link = TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
                link.linkName = target
                out.putArchiveEntry(link)
                out.closeArchiveEntry()
            }
        }
        vfs = TarVirtualFileSystem(archive, Files::newInputStream, Files::newOutputStream)
        assumeTrue("symbolic link creation not permitted on this host", Files.isSymbolicLink(vfs.root.resolve("alias")))
    }

    @After
    fun tearDown() {
        if (::vfs.isInitialized) vfs.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a file reached through a directory symlink is materialized`() {
        val path = vfs.root.resolve("alias").resolve("one.txt")

        vfs.materialize(path)

        assertEquals("content of real/one.txt", Files.readString(path))
    }

    @Test
    fun `a file symlink materializes its target`() {
        val path = vfs.root.resolve("link.txt")

        vfs.materialize(path)

        assertEquals("content of real/two.txt", Files.readString(path))
    }

    @Test
    fun `bulk materialization follows symlinks too`() {
        val path = vfs.root.resolve("alias").resolve("three.txt")

        vfs.materializeAll(listOf(path, vfs.root.resolve("link.txt")))

        assertEquals("content of real/three.txt", Files.readString(path))
        assertEquals("content of real/two.txt", Files.readString(vfs.root.resolve("real/two.txt")))
    }
}
