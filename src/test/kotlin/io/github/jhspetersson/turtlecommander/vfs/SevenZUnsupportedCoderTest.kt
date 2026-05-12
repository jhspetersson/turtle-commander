package io.github.jhspetersson.turtlecommander.vfs

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression: looksLikeUnsupportedCoder used to match `"Unsupported Codec"` and `"BCJ2"`,
 * which are dead strings in commons-compress 1.28. The actual messages a 7z archive can
 * fail with — `"Unsupported compression method …"`, `"BCJ filter used in … needs XZ for
 * Java > 1.4"`, `"Alternative methods are unsupported"`, `"Additional streams
 * unsupported"` — all need to route through the system-7z fallback or the user just sees
 * a hard failure on archives the CLI tool could have decoded.
 */
class SevenZUnsupportedCoderTest {

    private val tempPaths = mutableListOf<Path>()

    @After
    fun cleanup() {
        for (p in tempPaths.reversed()) {
            try { Files.deleteIfExists(p) } catch (_: Exception) {}
        }
    }

    private fun newVfs(): SevenZipVirtualFileSystem {
        // SevenZipVirtualFileSystem extracts at construction, so it needs a real (even if
        // minimal) 7z archive to open against. We only call looksLikeUnsupportedCoder
        // afterwards — the archive itself is irrelevant to what's under test.
        val archive = Files.createTempFile("seven-z-coder-", ".7z")
        Files.deleteIfExists(archive)
        tempPaths.add(archive)
        SevenZOutputFile(archive.toFile()).use { out ->
            val entry = SevenZArchiveEntry().apply {
                name = "placeholder.txt"
                size = 1
            }
            out.putArchiveEntry(entry)
            out.write(byteArrayOf(0))
            out.closeArchiveEntry()
        }
        return SevenZipVirtualFileSystem(archive).also { tempPaths.add(it.root) }
    }

    @Test
    fun `multi-stream coder message matches`() {
        val vfs = newVfs()
        vfs.use { vfs ->
            assertTrue(vfs.looksLikeUnsupportedCoder(IOException("Multi input/output stream coders are not yet supported")))
        }
    }

    @Test
    fun `unsupported compression method message matches`() {
        val vfs = newVfs()
        vfs.use { vfs ->
            assertTrue(vfs.looksLikeUnsupportedCoder(IOException("Unsupported compression method [33] used in foo.7z")))
            assertTrue(vfs.looksLikeUnsupportedCoder(IOException("Unsupported compression method LZMA2")))
        }
    }

    @Test
    fun `BCJ filter without XZ-for-Java message matches`() {
        val vfs = newVfs()
        vfs.use { vfs ->
            assertTrue(
                vfs.looksLikeUnsupportedCoder(
                    IOException("BCJ filter used in foo.7z needs XZ for Java > 1.4 - see https://commons.apache.org/proper/commons-compress/limitations.html"),
                ),
            )
        }
    }

    @Test
    fun `library-level feature gaps match`() {
        val vfs = newVfs()
        vfs.use { vfs ->
            assertTrue(vfs.looksLikeUnsupportedCoder(IOException("Alternative methods are unsupported, please report.")))
            assertTrue(vfs.looksLikeUnsupportedCoder(IOException("Additional streams unsupported")))
        }
    }

    @Test
    fun `unrelated IO error does not match`() {
        val vfs = newVfs()
        vfs.use { vfs ->
            assertFalse(vfs.looksLikeUnsupportedCoder(IOException("Disk full")))
            assertFalse(vfs.looksLikeUnsupportedCoder(IOException("CRC mismatch")))
        }
    }
}
