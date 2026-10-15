package io.github.jhspetersson.turtlecommander.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ResolveDestinationPathTest {

    private val base: Path = Files.createTempDirectory("resolve-dest-").toAbsolutePath()

    @Test
    fun relativeInputResolvesAgainstBaseNotProcessCwd() {
        assertEquals(base.resolve("parts"), resolveDestinationPath("parts", base))
    }

    @Test
    fun parentRelativeInputResolvesAgainstBase() {
        assertEquals(base.resolve("../out"), resolveDestinationPath("../out", base))
    }

    @Test
    fun absoluteInputIsKeptAsIs() {
        val absolute = base.resolve("elsewhere")
        assertEquals(absolute, resolveDestinationPath(absolute.toString(), base))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(base.resolve("parts"), resolveDestinationPath("  parts  ", base))
    }

    @Test
    fun relativeInputWithArchiveInternalBaseIsRejectedAsInvalidPath() {
        val zip = base.resolve("inner.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("dir/file.txt"))
            zos.write(1)
            zos.closeEntry()
        }
        FileSystems.newFileSystem(zip).use { zipFs ->
            val inside = zipFs.getPath("/dir")
            assertThrows(InvalidPathException::class.java) { resolveDestinationPath("out.zip", inside) }
            assertEquals(base.resolve("elsewhere"), resolveDestinationPath(base.resolve("elsewhere").toString(), inside))
        }
    }
}
