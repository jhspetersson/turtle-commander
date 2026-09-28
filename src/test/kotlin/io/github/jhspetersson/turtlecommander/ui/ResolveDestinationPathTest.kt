package io.github.jhspetersson.turtlecommander.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

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
}
