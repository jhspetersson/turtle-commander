package io.github.jhspetersson.turtlecommander.ui

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class FindLocalVirtualFilesTest : BasePlatformTestCase() {

    private lateinit var dir: Path

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("turtle-test-find-local", null).toPath()
    }

    override fun tearDown() {
        try {
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test resolves files created after the directory was loaded into the VFS`() {
        val a = Files.createFile(dir.resolve("a.txt"))
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!.children
        val b = Files.createFile(dir.resolve("b.txt"))
        val sub = Files.createDirectories(dir.resolve("sub"))

        val found = findLocalVirtualFiles(listOf(a, b, sub))!!

        assertEquals(listOf("a.txt", "b.txt", "sub"), found.map { it.name })
        assertTrue(found[2].isDirectory)
    }

    fun `test returns null when any path is missing`() {
        val a = Files.createFile(dir.resolve("a.txt"))
        assertNull(findLocalVirtualFiles(listOf(a, dir.resolve("missing.txt"))))
    }
}
