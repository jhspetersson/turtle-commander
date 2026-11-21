package io.github.jhspetersson.turtlecommander.util

import io.github.jhspetersson.turtlecommander.service.copyPreservingAttributes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView

class ReadOnlyFilesTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        dir = Files.createTempDirectory("read-only-files-")
    }

    @After
    fun tearDown() {
        if (!::dir.isInitialized) return
        Files.walk(dir).use { stream -> stream.forEach { setReadOnly(it, false) } }
        dir.toFile().deleteRecursively()
    }

    private fun setReadOnly(path: Path, value: Boolean) {
        Files.getFileAttributeView(path, DosFileAttributeView::class.java).setReadOnly(value)
    }

    private fun isReadOnly(path: Path): Boolean =
        Files.getFileAttributeView(path, DosFileAttributeView::class.java).readAttributes().isReadOnly

    private fun readOnlyFile(name: String, content: String = "x"): Path =
        Files.writeString(dir.resolve(name), content).also { setReadOnly(it, true) }

    @Test
    fun `the platform refuses to delete a read-only file`() {
        val file = readOnlyFile("locked.txt")

        assertThrows(AccessDeniedException::class.java) { Files.delete(file) }
    }

    @Test
    fun `a read-only file is deleted`() {
        val file = readOnlyFile("locked.txt")

        deleteClearingReadOnly(file)

        assertFalse(Files.exists(file))
    }

    @Test
    fun `a read-only file is deleted if it exists`() {
        val file = readOnlyFile("locked.txt")

        assertTrue(deleteIfExistsClearingReadOnly(file))
        assertFalse(deleteIfExistsClearingReadOnly(file))
    }

    @Test
    fun `a read-only target is overwritten`() {
        val source = Files.writeString(dir.resolve("source.txt"), "new")
        val target = readOnlyFile("target.txt", "old")

        copyPreservingAttributes(source, target, replaceExisting = true)

        assertEquals("new", Files.readString(target))
    }

    @Test
    fun `the read-only flag is put back when the retry also fails`() {
        val file = readOnlyFile("locked.txt")

        assertThrows(IllegalStateException::class.java) {
            withReadOnlyCleared(file) {
                if (isReadOnly(file)) throw AccessDeniedException(file.toString())
                error("still failing")
            }
        }

        assertTrue(isReadOnly(file))
    }

    @Test
    fun `access denied for another reason is passed on`() {
        val file = Files.writeString(dir.resolve("plain.txt"), "x")

        assertThrows(AccessDeniedException::class.java) {
            withReadOnlyCleared(file) { throw AccessDeniedException(file.toString()) }
        }
    }
}
