package io.github.jhspetersson.turtlecommander.vfs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NestedWriteBackSkipTest {

    private class FlushCountingVfs(private val delegate: VirtualFileSystem) : VirtualFileSystem by delegate {
        var flushes = 0

        override fun flush() {
            flushes++
            delegate.flush()
        }
    }

    private val tempDirs = mutableListOf<Path>()
    private val vfsList = mutableListOf<VirtualFileSystem>()

    @After
    fun tearDown() {
        vfsList.asReversed().forEach { runCatching { it.close() } }
        tempDirs.asReversed().forEach { dir ->
            runCatching { Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
        }
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun newTempDir(): Path = Files.createTempDirectory("nested-skip-").also { tempDirs.add(it) }

    private fun openOuter(bytes: ByteArray): FlushCountingVfs {
        val path = newTempDir().resolve("outer.zip")
        Files.write(path, bytes)
        return FlushCountingVfs(ZipVirtualFileSystem(path)).also { vfsList.add(it) }
    }

    private fun openNested(parent: VirtualFileSystem, name: String): Pair<FlushCountingVfs, VfsStackEntry> {
        val parentPath = parent.root.resolve(name)
        val tempPath = newTempDir().resolve(name)
        Files.copy(parentPath, tempPath)
        val vfs = FlushCountingVfs(ZipVirtualFileSystem(tempPath)).also { vfsList.add(it) }
        return vfs to VfsStackEntry(vfs, parentPath, tempPath.toFile())
    }

    private fun twoLevels(): Triple<FlushCountingVfs, FlushCountingVfs, List<VfsStackEntry>> {
        val outer = openOuter(zipBytes("inner.zip" to zipBytes("a.txt" to "a".toByteArray()), "other.txt" to "o".toByteArray()))
        val (inner, innerEntry) = openNested(outer, "inner.zip")
        return Triple(outer, inner, listOf(VfsStackEntry(outer, outer.archivePath), innerEntry))
    }

    private fun readNested(parent: VirtualFileSystem, name: String, file: String): String {
        val copy = newTempDir().resolve(name)
        Files.copy(parent.root.resolve(name), copy)
        return ZipVirtualFileSystem(copy).use { Files.readString(it.root.resolve(file)) }
    }

    @Test
    fun `unchanged inner archive does not flush the parent`() {
        val (outer, inner, stack) = twoLevels()

        inner.flush()
        writeBackNestedStack(stack)

        assertEquals(0, outer.flushes)
    }

    @Test
    fun `repeated refreshes of an unchanged inner archive never flush the parent`() {
        val (outer, inner, stack) = twoLevels()

        repeat(3) {
            inner.flush()
            writeBackNestedStack(stack)
        }

        assertEquals(0, outer.flushes)
    }

    @Test
    fun `changed inner archive is written back to the parent once`() {
        val (outer, inner, stack) = twoLevels()

        Files.writeString(inner.root.resolve("b.txt"), "b")
        inner.flush()
        writeBackNestedStack(stack)
        inner.flush()
        writeBackNestedStack(stack)

        assertEquals(1, outer.flushes)
        assertEquals("b", readNested(outer, "inner.zip", "b.txt"))
    }

    @Test
    fun `change after an earlier write-back is written back again`() {
        val (outer, inner, stack) = twoLevels()

        Files.writeString(inner.root.resolve("b.txt"), "b1")
        inner.flush()
        writeBackNestedStack(stack)
        Files.writeString(inner.root.resolve("b.txt"), "b2")
        inner.flush()
        writeBackNestedStack(stack)

        assertEquals(2, outer.flushes)
        assertEquals("b2", readNested(outer, "inner.zip", "b.txt"))
    }

    @Test
    fun `three levels unchanged flush nothing and a change cascades to the outermost`() {
        val innerBytes = zipBytes("a.txt" to "a".toByteArray())
        val midBytes = zipBytes("inner.zip" to innerBytes)
        val outer = openOuter(zipBytes("mid.zip" to midBytes))
        val (mid, midEntry) = openNested(outer, "mid.zip")
        val (inner, innerEntry) = openNested(mid, "inner.zip")
        val stack = listOf(VfsStackEntry(outer, outer.archivePath), midEntry, innerEntry)

        inner.flush()
        writeBackNestedStack(stack)
        assertEquals(0, mid.flushes)
        assertEquals(0, outer.flushes)

        Files.writeString(inner.root.resolve("a.txt"), "changed")
        inner.flush()
        writeBackNestedStack(stack)
        assertEquals(1, mid.flushes)
        assertEquals(1, outer.flushes)

        val midCopy = newTempDir().resolve("mid.zip")
        Files.copy(outer.root.resolve("mid.zip"), midCopy)
        ZipVirtualFileSystem(midCopy).use { assertEquals("changed", readNested(it, "inner.zip", "a.txt")) }
    }
}
