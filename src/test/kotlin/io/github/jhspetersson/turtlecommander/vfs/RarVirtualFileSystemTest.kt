package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class RarVirtualFileSystemTest {

    private val dir: Path = Files.createTempDirectory("test-rar-")

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun copyFixture(name: String): Path {
        val target = dir.resolve(name)
        javaClass.getResourceAsStream("/vfs/rar/$name")!!.use { Files.copy(it, target) }
        return target
    }

    private fun readEntry(vfs: RarVirtualFileSystem, name: String): ByteArray {
        val entry = runBlocking { vfs.listFiles(vfs.root) }.first { !it.isParentLink && it.name == name }
        vfs.materialize(entry.path)
        return Files.readAllBytes(entry.path)
    }

    @Test
    fun `non-solid entry materializes lazily`() {
        RarVirtualFileSystem(copyFixture("hello.rar")).use { vfs ->
            assertEquals("hello rar", String(readEntry(vfs, "hello.txt")))
        }
    }

    @Test
    fun `entry spanning volumes materializes`() {
        val first = copyFixture("m.part1.rar")
        copyFixture("m.part2.rar")
        copyFixture("m.part3.rar")
        val expected = String(CharArray(2500) { i -> 'A' + (i * 7) % 26 })
        RarVirtualFileSystem(first).use { vfs ->
            assertEquals(expected, String(readEntry(vfs, "big.txt")))
        }
    }

    @Test
    fun `archive can be renamed and deleted while browsed`() {
        val rar = copyFixture("hello.rar")
        val moved = dir.resolve("moved.rar")
        RarVirtualFileSystem(rar).use { vfs ->
            assertEquals("hello rar", String(readEntry(vfs, "hello.txt")))
            Files.move(rar, moved, StandardCopyOption.ATOMIC_MOVE)
            Files.delete(moved)
            assertFalse(Files.exists(moved))
        }
    }
}
