package io.github.jhspetersson.turtlecommander.service

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class TempPrefixConventionTest {

    private val tempRootCreationPatterns = listOf(
        Regex("""createTempDirectory\(\s*"([^"]*)""""),
        Regex("""createTempFile\(\s*"([^"]*)""""),
        Regex("""AbstractTempDirVirtualFileSystem\(\s*"([^"]*)""""),
    )

    private fun mainSourceRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        repeat(4) {
            val candidate = dir.resolve("src/main/kotlin")
            if (Files.isDirectory(candidate)) return candidate
            dir = dir.parent ?: return@repeat
        }
        fail("Could not locate src/main/kotlin from ${Path.of("").toAbsolutePath()}")
        error("unreachable")
    }

    @Test
    fun `every temp artifact created in the tmp root uses the swept turtle- prefix`() {
        val offenders = mutableListOf<String>()
        val found = mutableListOf<String>()
        Files.walk(mainSourceRoot()).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }.forEach { file ->
                val text = file.readText()
                for (pattern in tempRootCreationPatterns) {
                    for (match in pattern.findAll(text)) {
                        val prefix = match.groupValues[1]
                        found += prefix
                        if (!prefix.startsWith(VfsTempCleanup.FAMILY_PREFIX)) {
                            offenders += "${file.fileName}: \"$prefix\""
                        }
                    }
                }
            }
        }
        assertTrue("Expected to find temp prefix literals; the scan patterns may be stale", found.size >= 10)
        assertTrue(
            "Temp artifacts created in the tmp root must use the \"${VfsTempCleanup.FAMILY_PREFIX}\" prefix " +
                "so VfsTempCleanup can sweep them after a crash. Offenders: $offenders",
            offenders.isEmpty(),
        )
    }
}
