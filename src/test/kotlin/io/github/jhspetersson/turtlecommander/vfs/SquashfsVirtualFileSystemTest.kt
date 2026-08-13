package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class SquashfsVirtualFileSystemTest {

    private lateinit var imagePath: Path
    private var vfs: SquashfsVirtualFileSystem? = null

    @After
    fun tearDown() {
        vfs?.close()
        if (::imagePath.isInitialized) Files.deleteIfExists(imagePath)
    }

    private fun writeSquashfsImage(): Path {
        val prefixBase64 =
            "aHNxcwQAAACIYX1qAAACAAEAAAABABEAwAABAAQAAABgAAAAAAAAADMBAAAAAAAAKwEAAAAAAAD//////////3wAAAAAAAAA" +
            "tgAAAAAAAAAAAQAAAAAAAB0BAAAAAAAAaGVsbG8gc3F1YXNoZnNuZXN0ZWQgY29udGVudDgAeNpjYvjPyAAEHYm1WWAGEuAD" +
            "YiYkeWY0ORBmRJJngsqBaEUgZkGTZ4HKg8zRZpBjYAXSAFIBC/M2AHjaY2CAAGYgVgBiJgZOhrzU4pLUFL2SihJGqCyMZmLg" +
            "YMhIzcnJB0k6AIUZgULFpUkABCoJfBCAYAAAAAAAAAAcAAABAAAAAO4AAAAAAAAAEwB42mNggAAHKK0ApROgNAALIADBCAEA" +
            "AAAAAAAEgAAAAAAlAQ=="
        val image = Base64.getDecoder().decode(prefixBase64).copyOf(4096)
        val path = Files.createTempFile("test-", ".squashfs")
        Files.write(path, image)
        return path
    }

    @Test
    fun `squashfs extracts via system 7-Zip`() = runBlocking {
        Assume.assumeTrue("system 7-Zip not installed", System7z.findBinary() != null)
        imagePath = writeSquashfsImage()
        val squashfsVfs = SquashfsVirtualFileSystem(imagePath)
        vfs = squashfsVfs

        assertTrue(squashfsVfs.isReadOnly)

        val rootEntries = squashfsVfs.listFiles(squashfsVfs.root).filter { !it.isParentLink }
        assertNotNull(rootEntries.find { it.name == "hello.txt" })
        assertNotNull(rootEntries.find { it.name == "sub" && it.isDirectory })
        assertEquals("hello squashfs", Files.readString(squashfsVfs.getPath("/hello.txt")))
        assertEquals("nested content", Files.readString(squashfsVfs.getPath("/sub/nested.txt")))
    }

    @Test
    fun `non-squashfs file is rejected silently`() {
        imagePath = Files.createTempFile("not-a-squashfs-", ".squashfs")
        Files.write(imagePath, "not a squashfs image".toByteArray())
        try {
            SquashfsVirtualFileSystem(imagePath)
            fail("Expected construction to fail for a non-squashfs file")
        } catch (_: SilentVfsOpenException) {
        }
    }
}
