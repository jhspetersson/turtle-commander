package io.github.jhspetersson.turtlecommander.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class RenameBatchTest {

    private val dir: Path = Files.createTempDirectory("rename-batch-")

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun file(name: String): Path = Files.writeString(dir.resolve(name), name)

    private fun contents(): Map<String, String> =
        Files.list(dir).use { stream -> stream.toList().associate { it.fileName.toString() to Files.readString(it) } }

    @Test
    fun `a batch is renamed and reported`() {
        val a = file("a.txt")
        val b = file("b.txt")

        val completed = renameBatch(listOf(a to "b.txt", b to "a.txt"))

        assertEquals(mapOf("a.txt" to "b.txt", "b.txt" to "a.txt"), contents())
        assertEquals(listOf(a to dir.resolve("b.txt"), b to dir.resolve("a.txt")), completed)
    }

    @Test
    fun `a failure in the second phase puts every file back`() {
        val a = file("a.txt")
        val b = file("b.txt")
        val c = file("c.txt")

        assertThrows(IOException::class.java) {
            renameBatch(listOf(a to "x.txt", b to "y.txt", c to "z.txt")) { from, to ->
                if (to.fileName.toString() == "y.txt") throw IOException("disk says no")
                Files.move(from, to)
            }
        }

        assertEquals(mapOf("a.txt" to "a.txt", "b.txt" to "b.txt", "c.txt" to "c.txt"), contents())
    }

    @Test
    fun `a failure in the first phase puts every file back`() {
        val a = file("a.txt")
        val b = file("b.txt")

        assertThrows(IOException::class.java) {
            renameBatch(listOf(a to "x.txt", b to "y.txt")) { from, to ->
                if (from == b) throw IOException("locked")
                Files.move(from, to)
            }
        }

        assertEquals(mapOf("a.txt" to "a.txt", "b.txt" to "b.txt"), contents())
    }

    @Test
    fun `an invalid name is rejected before anything moves`() {
        val a = file("a.txt")

        assertThrows(IOException::class.java) { renameBatch(listOf(a to "sub/x.txt")) }

        assertEquals(mapOf("a.txt" to "a.txt"), contents())
    }
}
