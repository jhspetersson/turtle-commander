package io.github.jhspetersson.turtlecommander.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class ExtractToSubdirDefaultTest {

    private val local = Path.of("C:/work/project").toAbsolutePath()
    private val tempRoot = Path.of("C:/tmp/turtle-tar-123/sub").toAbsolutePath()

    @Test
    fun `local tab extracts next to the archive`() {
        assertEquals(local.resolve("backup"), extractToSubdirDefault(local, insideArchive = false, destinationBasePath = local, archiveName = "backup.tar.gz"))
    }

    @Test
    fun `temp-dir archive tab extracts beside the outer archive instead of into the temp dir`() {
        assertEquals(local.resolve("inner"), extractToSubdirDefault(tempRoot, insideArchive = true, destinationBasePath = local, archiveName = "inner.zip"))
    }
}
