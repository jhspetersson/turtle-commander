package io.github.jhspetersson.turtlecommander.dialog

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DriveInfosTest {

    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("drive-infos-test-")
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `two paths on the same volume are listed once under the first`() {
        val first = Files.createDirectory(root.resolve("first"))
        val second = Files.createDirectory(root.resolve("second"))

        val drives = driveInfos(listOf(first, second))

        assertEquals(1, drives.size)
        assertTrue(drives.single().label.startsWith(first.toString()))
    }

    @Test
    fun `a path whose store cannot be read is skipped`() {
        val drives = driveInfos(listOf(root.resolve("missing"), root))

        assertEquals(1, drives.size)
    }

    @Test
    fun `the filesystem root keeps a visible label`() {
        val fsRoot = root.root

        val drives = driveInfos(listOf(fsRoot), storeOf = { Files.getFileStore(root) })

        assertTrue(drives.single().label.isNotBlank())
        assertTrue(drives.single().label.first() == fsRoot.toString().first())
    }
}
