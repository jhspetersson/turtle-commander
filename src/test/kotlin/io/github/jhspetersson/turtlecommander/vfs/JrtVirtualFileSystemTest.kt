package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class JrtVirtualFileSystemTest {

    private val javaHome: Path = Path.of(System.getProperty("java.home"))
    private val modulesImage: Path = JrtFileSystemProvider.modulesImageFor(javaHome)

    private suspend fun <T> withVfs(block: suspend (JrtVirtualFileSystem) -> T): T {
        val vfs = JrtVirtualFileSystem(modulesImage)
        try {
            return block(vfs)
        } finally {
            vfs.close()
        }
    }

    @Test
    fun `provider recognises the running JDK modules image`() {
        assertTrue(JrtFileSystemProvider().supports(modulesImage))
        assertTrue(VirtualFileSystemRegistry.supports(modulesImage))
    }

    @Test
    fun `provider rejects a file named modules that is not a jimage`() {
        val tempHome = Files.createTempDirectory("turtle-test-jrt-")
        try {
            val fake = Files.createDirectories(tempHome.resolve("lib")).resolve("modules")
            Files.writeString(fake, "not a runtime image")
            assertFalse(JrtFileSystemProvider().supports(fake))
            assertFalse(VirtualFileSystemRegistry.supports(fake))
        } finally {
            tempHome.toFile().deleteRecursively()
        }
    }

    @Test
    fun `provider is not keyed by extension`() {
        assertFalse(VirtualFileSystemRegistry.supportsByExtension("modules"))
    }

    @Test
    fun `root lists modules and its parent link points at the lib directory`() = runBlocking {
        withVfs { vfs ->
            assertTrue(vfs.isReadOnly)
            assertTrue(vfs.isRoot(vfs.root))
            val entries = vfs.listFiles(vfs.root)
            val parent = entries.first()
            assertTrue(parent.isParentLink)
            assertEquals(modulesImage.parent, parent.path)
            val javaBase = entries.first { it.name == "java.base" }
            assertTrue(javaBase.isDirectory)
            assertFalse(vfs.isRoot(javaBase.path))
        }
    }

    @Test
    fun `navigates into a package and reads a class file`() = runBlocking {
        withVfs { vfs ->
            val lang = vfs.getPath("java.base/java/lang")
            val entries = vfs.listFiles(lang)
            assertEquals(vfs.getPath("java.base/java"), entries.first().path)
            val string = entries.first { it.name == "String.class" }
            assertFalse(string.isDirectory)
            assertTrue(string.size > 0)
            assertTrue(vfs.owns(string.path))
            assertFalse(vfs.owns(modulesImage))

            val copy = Files.createTempFile("turtle-test-jrt-", ".class")
            try {
                Files.copy(string.path, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                val magic = Files.newInputStream(copy).use { it.readNBytes(4) }
                assertArrayEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()), magic)
            } finally {
                Files.deleteIfExists(copy)
            }
        }
    }

    @Test
    fun `rename is refused`() = runBlocking {
        withVfs { vfs ->
            val result = runCatching { vfs.renameFile(vfs.getPath("java.base/java/lang/String.class"), "Renamed.class") }
            assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
        }
    }
}
