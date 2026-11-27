package io.github.jhspetersson.turtlecommander.vfs

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

class ArWideSymbolTableTest {

    private lateinit var dir: Path

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ar-wide-symtab-")
    }

    @After
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun header(name: String, size: Int): ByteArray =
        (name.padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) + "100644".padEnd(8) +
            size.toString().padEnd(10) + "`\n").toByteArray(Charsets.US_ASCII)

    private fun member(name: String, data: ByteArray): ByteArray =
        header(name, data.size) + data + (if (data.size % 2 == 1) byteArrayOf('\n'.code.toByte()) else ByteArray(0))

    private fun names(symbols: List<String>): ByteArray =
        symbols.flatMap { it.toByteArray(Charsets.US_ASCII).toList() + 0.toByte() }.toByteArray()

    private fun wideTable(symbols: List<Pair<String, Int>>): ByteArray {
        val strings = names(symbols.map { it.first })
        val buffer = ByteBuffer.allocate(8 + 8 * symbols.size + strings.size)
        buffer.putLong(symbols.size.toLong())
        symbols.forEach { buffer.putLong(it.second.toLong()) }
        buffer.put(strings)
        return buffer.array()
    }

    private fun narrowTable(symbols: List<Pair<String, Int>>): ByteArray {
        val strings = names(symbols.map { it.first })
        val buffer = ByteBuffer.allocate(4 + 4 * symbols.size + strings.size)
        buffer.putInt(symbols.size)
        symbols.forEach { buffer.putInt(it.second) }
        buffer.put(strings)
        return buffer.array()
    }

    private fun secondLinkerMember(offsets: List<Int>, symbols: List<Pair<String, Int>>): ByteArray {
        val strings = names(symbols.map { it.first })
        val buffer = ByteBuffer.allocate(4 + 4 * offsets.size + 4 + 2 * symbols.size + strings.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(offsets.size)
        offsets.forEach { buffer.putInt(it) }
        buffer.putInt(symbols.size)
        symbols.forEach { buffer.putShort(it.second.toShort()) }
        buffer.put(strings)
        return buffer.array()
    }

    private val util = "util object".toByteArray()
    private val main = "main object bytes".toByteArray()

    private fun wideLibrary(): Path {
        val placeholder = member("/SYM64/", wideTable(listOf("util_fn" to 0, "main" to 0, "util_other" to 0)))
        val utilOffset = 8 + placeholder.size
        val mainOffset = utilOffset + member("util.o/", util).size
        val table = wideTable(listOf("util_fn" to utilOffset, "main" to mainOffset, "util_other" to utilOffset))
        val path = dir.resolve("libwide.a")
        Files.write(
            path,
            "!<arch>\n".toByteArray(Charsets.US_ASCII) + member("/SYM64/", table) + member("util.o/", util) +
                member("main.o/", main),
        )
        return path
    }

    private fun microsoftLibrary(): Path {
        val first = member("/", narrowTable(listOf("main" to 0, "util_fn" to 0)))
        val second = member("/", secondLinkerMember(listOf(0, 0), listOf("main" to 2, "util_fn" to 1)))
        val utilOffset = 8 + first.size + second.size
        val mainOffset = utilOffset + member("util.o/", util).size
        val path = dir.resolve("libcoff.a")
        Files.write(
            path,
            "!<arch>\n".toByteArray(Charsets.US_ASCII) +
                member("/", narrowTable(listOf("main" to mainOffset, "util_fn" to utilOffset))) +
                member("/", secondLinkerMember(listOf(utilOffset, mainOffset), listOf("main" to 2, "util_fn" to 1))) +
                member("util.o/", util) + member("main.o/", main),
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

    private fun wideSymbolOwners(path: Path): Map<String, String> {
        val members = rawMembers(path)
        val table = ByteBuffer.wrap(members.first { it.name == "/SYM64/" }.data)
        val count = table.long.toInt()
        val offsets = List(count) { table.long.toInt() }
        val symbols = String(table.array(), table.position(), table.remaining(), Charsets.US_ASCII).split('\u0000')
        return offsets.indices.associate { index -> symbols[index] to members.first { it.offset == offsets[index] }.name }
    }

    @Test
    fun `a 64-bit symbol table is not shown as a member`() = runBlocking {
        ArVirtualFileSystem(wideLibrary()).use { vfs ->
            assertFalse(vfs.isReadOnly)
            assertEquals(listOf("main.o", "util.o"), vfs.listFiles(vfs.root).map { it.name }.filter { it != ".." }.sorted())
        }
    }

    @Test
    fun `a 64-bit symbol table follows its members through a rename`() = runBlocking {
        val archive = wideLibrary()

        ArVirtualFileSystem(archive).use { vfs ->
            vfs.renameFile(vfs.root.resolve("util.o"), "a much longer utility name.o")
        }

        assertEquals(listOf("/SYM64/", "a much longer utility name.o", "main.o"), rawMembers(archive).map { it.name })
        assertEquals(
            mapOf(
                "util_fn" to "a much longer utility name.o",
                "main" to "main.o",
                "util_other" to "a much longer utility name.o",
            ),
            wideSymbolOwners(archive),
        )
        assertArrayEquals(main, rawMembers(archive).first { it.name == "main.o" }.data)
    }

    @Test
    fun `deleting a member drops only its symbols from a 64-bit table`() {
        val archive = wideLibrary()

        ArVirtualFileSystem(archive).use { vfs ->
            Files.delete(vfs.root.resolve("main.o"))
            vfs.flush()
        }

        assertEquals(mapOf("util_fn" to "util.o", "util_other" to "util.o"), wideSymbolOwners(archive))
    }

    @Test
    fun `a library with a microsoft second linker member opens read-only and is left untouched`() {
        val archive = microsoftLibrary()
        val original = Files.readAllBytes(archive)

        ArVirtualFileSystem(archive).use { vfs ->
            assertTrue(vfs.isReadOnly)
            assertTrue(Files.exists(vfs.root.resolve("util.o")))
            Files.writeString(vfs.root.resolve("added.o"), "x")
            vfs.flush()
        }

        assertArrayEquals(original, Files.readAllBytes(archive))
    }
}
