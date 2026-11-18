package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class ArMemberOrderTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ar-member-order-")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun deb(): Path {
        val path = dir.resolve("package.deb")
        ArArchiveOutputStream(Files.newOutputStream(path)).use { ar ->
            for (name in listOf("debian-binary", "control.tar.gz", "data.tar.xz")) {
                val bytes = "content of $name".toByteArray()
                ar.putArchiveEntry(ArArchiveEntry(name, bytes.size.toLong(), 0, 0, 0x81A4, 1_600_000_000L))
                ar.write(bytes)
                ar.closeArchiveEntry()
            }
        }
        return path
    }

    private fun memberNames(path: Path): List<String> =
        ArArchiveInputStream(Files.newInputStream(path)).use { ar ->
            generateSequence { ar.nextEntry }.map { it.name }.toList()
        }

    private fun header(name: String, size: Int): ByteArray =
        (name.padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) + "100644".padEnd(8) +
            size.toString().padEnd(10) + "`\n").toByteArray(Charsets.US_ASCII)

    private fun member(name: String, data: ByteArray): ByteArray =
        header(name, data.size) + data + (if (data.size % 2 == 1) byteArrayOf('\n'.code.toByte()) else ByteArray(0))

    private fun symbolTable(symbols: List<Pair<String, Int>>): ByteArray {
        val names = symbols.flatMap { it.first.toByteArray(Charsets.US_ASCII).toList() + 0.toByte() }
        val buffer = ByteBuffer.allocate(4 + 4 * symbols.size + names.size)
        buffer.putInt(symbols.size)
        symbols.forEach { buffer.putInt(it.second) }
        buffer.put(names.toByteArray())
        return buffer.array()
    }

    private fun library(): Path {
        val util = "util object".toByteArray()
        val main = "main object bytes".toByteArray()
        val extra = "extra".toByteArray()
        val placeholder = member("/", symbolTable(listOf("util_fn" to 0, "main" to 0, "extra_fn" to 0, "util_other" to 0)))
        val utilOffset = 8 + placeholder.size
        val mainOffset = utilOffset + member("util.o/", util).size
        val extraOffset = mainOffset + member("main.o/", main).size
        val table = symbolTable(
            listOf("util_fn" to utilOffset, "main" to mainOffset, "extra_fn" to extraOffset, "util_other" to utilOffset),
        )
        val path = dir.resolve("libdemo.a")
        Files.write(
            path,
            "!<arch>\n".toByteArray(Charsets.US_ASCII) + member("/", table) + member("util.o/", util) +
                member("main.o/", main) + member("extra.o/", extra),
        )
        return path
    }

    private class RawMember(val offset: Int, val name: String, val data: ByteArray)

    private fun rawMembers(path: Path): List<RawMember> {
        val bytes = Files.readAllBytes(path)
        val members = mutableListOf<RawMember>()
        var position = 8
        while (position + 60 <= bytes.size) {
            val text = String(bytes, position, 60, Charsets.US_ASCII)
            val size = text.substring(48, 58).trim().toInt()
            var name = text.substring(0, 16).trim()
            var dataStart = position + 60
            var dataLength = size
            if (name.startsWith("#1/")) {
                val nameLength = name.removePrefix("#1/").toInt()
                name = String(bytes, dataStart, nameLength, Charsets.US_ASCII)
                dataStart += nameLength
                dataLength -= nameLength
            }
            members += RawMember(position, name, bytes.copyOfRange(dataStart, dataStart + dataLength))
            position += 60 + size
            position += position % 2
        }
        return members
    }

    private fun symbolOwners(path: Path): Map<String, String> {
        val members = rawMembers(path)
        val table = ByteBuffer.wrap(members.first { it.name == "/" }.data)
        val count = table.int
        val offsets = List(count) { table.int }
        val names = String(table.array(), table.position(), table.remaining(), Charsets.US_ASCII).split('\u0000')
        return offsets.indices.associate { index -> names[index] to members.first { it.offset == offsets[index] }.name }
    }

    @Test
    fun `members keep their order when one is edited`() {
        val archive = deb()

        ArVirtualFileSystem(archive).use { vfs ->
            Files.writeString(vfs.root.resolve("control.tar.gz"), "edited")
            vfs.flush()
        }

        assertEquals(listOf("debian-binary", "control.tar.gz", "data.tar.xz"), memberNames(archive))
    }

    @Test
    fun `a new member goes after the original ones`() {
        val archive = deb()

        ArVirtualFileSystem(archive).use { vfs ->
            Files.writeString(vfs.root.resolve("aaa-first-by-name"), "new")
            vfs.flush()
        }

        assertEquals(listOf("debian-binary", "control.tar.gz", "data.tar.xz", "aaa-first-by-name"), memberNames(archive))
    }

    @Test
    fun `a renamed member keeps its position`() = runBlocking {
        val archive = deb()

        ArVirtualFileSystem(archive).use { vfs ->
            vfs.renameFile(vfs.root.resolve("debian-binary"), "zz-renamed")
        }

        assertEquals(listOf("zz-renamed", "control.tar.gz", "data.tar.xz"), memberNames(archive))
    }

    @Test
    fun `the symbol table follows its members through a rename`() = runBlocking {
        val archive = library()

        ArVirtualFileSystem(archive).use { vfs ->
            vfs.renameFile(vfs.root.resolve("util.o"), "a much longer utility name.o")
        }

        assertEquals(listOf("/", "a much longer utility name.o", "main.o", "extra.o"), rawMembers(archive).map { it.name })
        assertEquals(
            mapOf(
                "util_fn" to "a much longer utility name.o",
                "main" to "main.o",
                "extra_fn" to "extra.o",
                "util_other" to "a much longer utility name.o",
            ),
            symbolOwners(archive),
        )
        assertArrayEquals("main object bytes".toByteArray(), rawMembers(archive).first { it.name == "main.o" }.data)
    }

    @Test
    fun `the symbol table survives a second repack`() = runBlocking {
        val archive = library()

        ArVirtualFileSystem(archive).use { vfs ->
            vfs.renameFile(vfs.root.resolve("util.o"), "a much longer utility name.o")
            vfs.renameFile(vfs.root.resolve("extra.o"), "e.o")
        }

        assertEquals(
            mapOf(
                "util_fn" to "a much longer utility name.o",
                "main" to "main.o",
                "extra_fn" to "e.o",
                "util_other" to "a much longer utility name.o",
            ),
            symbolOwners(archive),
        )
    }

    @Test
    fun `deleting a member drops only its symbols`() {
        val archive = library()

        ArVirtualFileSystem(archive).use { vfs ->
            Files.delete(vfs.root.resolve("main.o"))
            vfs.flush()
        }

        assertEquals(
            mapOf("util_fn" to "util.o", "extra_fn" to "extra.o", "util_other" to "util.o"),
            symbolOwners(archive),
        )
    }

    @Test
    fun `changing a member drops the symbol table that no longer describes it`() {
        val archive = library()

        ArVirtualFileSystem(archive).use { vfs ->
            val edited = vfs.root.resolve("main.o")
            Files.writeString(edited, "rebuilt object")
            Files.setLastModifiedTime(edited, FileTime.fromMillis(1_800_000_000_000L))
            vfs.flush()
        }

        assertFalse(rawMembers(archive).any { it.name == "/" })
        assertEquals(listOf("util.o", "main.o", "extra.o"), rawMembers(archive).map { it.name })
    }
}
