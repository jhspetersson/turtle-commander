package io.github.jhspetersson.turtlecommander.service
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.ui.FileTableModel
import io.github.jhspetersson.turtlecommander.vfs.ZipVirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.TarVirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.SevenZipVirtualFileSystem

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ─── FileOperationService.renameFile ────────────────────────────────────────

class FileOperationServiceRenameTest {

    private val tempFiles = mutableListOf<Path>()

    @After
    fun tearDown() {
        tempFiles.forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `rename regular file`() = runBlocking {
        val dir = Files.createTempDirectory("rename-test-")
        val file = Files.writeString(dir.resolve("old.txt"), "content")
        tempFiles.add(dir)

        val target = dir.resolve("new.txt")
        Files.move(file, target)

        assertTrue(Files.exists(target))
        assertFalse(Files.exists(file))
        assertEquals("content", Files.readString(target))

        Files.delete(target)
    }

    @Test
    fun `rename file preserves content`() = runBlocking {
        val dir = Files.createTempDirectory("rename-test-")
        val file = Files.writeString(dir.resolve("data.bin"), "binary-like-content")
        tempFiles.add(dir)

        val target = dir.resolve("renamed.bin")
        Files.move(file, target)

        assertEquals("binary-like-content", Files.readString(target))
        Files.delete(target)
    }

    @Test
    fun `rename file with spaces in name`() = runBlocking {
        val dir = Files.createTempDirectory("rename-test-")
        val file = Files.writeString(dir.resolve("old name.txt"), "content")
        tempFiles.add(dir)

        val target = dir.resolve("new name.txt")
        Files.move(file, target)

        assertTrue(Files.exists(target))
        assertFalse(Files.exists(dir.resolve("old name.txt")))
        Files.delete(target)
    }

    @Test
    fun `rename file changes extension`() = runBlocking {
        val dir = Files.createTempDirectory("rename-test-")
        val file = Files.writeString(dir.resolve("file.txt"), "content")
        tempFiles.add(dir)

        val target = dir.resolve("file.md")
        Files.move(file, target)

        assertTrue(Files.exists(target))
        assertFalse(Files.exists(dir.resolve("file.txt")))
        Files.delete(target)
    }

    @Test
    fun `rename directory`() = runBlocking {
        val dir = Files.createTempDirectory("rename-test-")
        val subdir = Files.createDirectory(dir.resolve("olddir"))
        Files.writeString(subdir.resolve("child.txt"), "child")
        tempFiles.add(dir)

        val target = dir.resolve("newdir")
        Files.move(subdir, target)

        assertTrue(Files.isDirectory(target))
        assertTrue(Files.exists(target.resolve("child.txt")))
        assertFalse(Files.exists(subdir))

        Files.walk(target).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
    }
}

// ─── ZIP rename ─────────────────────────────────────────────────────────────

class ZipRenameTest {

    private lateinit var zipPath: Path
    private lateinit var vfs: ZipVirtualFileSystem

    private fun createZip(vararg entries: Pair<String, String?>) {
        zipPath = Files.createTempFile("rename-test-", ".zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                if (content != null) zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        vfs = ZipVirtualFileSystem(zipPath)
    }

    @After
    fun tearDown() {
        vfs.close()
        Files.deleteIfExists(zipPath)
    }

    @Test
    fun `rename file at root`() = runBlocking {
        createZip("hello.txt" to "world")

        val source = vfs.getPath("/hello.txt")
        vfs.renameFile(source, "renamed.txt")

        val entries = vfs.listFiles(vfs.root)
        assertNotNull(entries.find { it.name == "renamed.txt" })
        assertNull(entries.find { it.name == "hello.txt" })
    }

    @Test
    fun `rename persists to zip file`() = runBlocking {
        createZip("hello.txt" to "world")

        val source = vfs.getPath("/hello.txt")
        vfs.renameFile(source, "renamed.txt")
        vfs.close()

        // Reopen and verify
        vfs = ZipVirtualFileSystem(zipPath)
        val entries = vfs.listFiles(vfs.root)
        assertNotNull(entries.find { it.name == "renamed.txt" })
        assertNull(entries.find { it.name == "hello.txt" })
    }

    @Test
    fun `rename file in subdirectory`() = runBlocking {
        createZip("subdir/" to null, "subdir/nested.txt" to "data")

        val source = vfs.getPath("/subdir/nested.txt")
        vfs.renameFile(source, "moved.txt")

        val entries = vfs.listFiles(vfs.getPath("/subdir"))
        assertNotNull(entries.find { it.name == "moved.txt" })
        assertNull(entries.find { it.name == "nested.txt" })
    }

    @Test
    fun `rename preserves other files`() = runBlocking {
        createZip("a.txt" to "aaa", "b.txt" to "bbb")

        val source = vfs.getPath("/a.txt")
        vfs.renameFile(source, "c.txt")

        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        assertEquals(2, entries.size)
        assertNotNull(entries.find { it.name == "c.txt" })
        assertNotNull(entries.find { it.name == "b.txt" })
    }

    @Test
    fun `rename after flush`() = runBlocking {
        createZip("hello.txt" to "world")

        vfs.flush()

        val source = vfs.getPath("/hello.txt")
        vfs.renameFile(source, "renamed.txt")

        val entries = vfs.listFiles(vfs.root)
        assertNotNull(entries.find { it.name == "renamed.txt" })
    }

    @Test
    fun `multiple renames`() = runBlocking {
        createZip("file.txt" to "content")

        val source1 = vfs.getPath("/file.txt")
        vfs.renameFile(source1, "step1.txt")

        val source2 = vfs.getPath("/step1.txt")
        vfs.renameFile(source2, "step2.txt")

        val entries = vfs.listFiles(vfs.root)
        assertNotNull(entries.find { it.name == "step2.txt" })
        assertNull(entries.find { it.name == "file.txt" })
        assertNull(entries.find { it.name == "step1.txt" })
    }

    @Test
    fun `rename then flush then rename`() = runBlocking {
        createZip("a.txt" to "aaa", "b.txt" to "bbb")

        vfs.renameFile(vfs.getPath("/a.txt"), "a2.txt")
        vfs.flush()
        vfs.renameFile(vfs.getPath("/b.txt"), "b2.txt")

        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        assertNotNull(entries.find { it.name == "a2.txt" })
        assertNotNull(entries.find { it.name == "b2.txt" })
    }

    @Test
    fun `rename file with null parent at root`() = runBlocking {
        createZip("rootfile.txt" to "data")

        // Files at root of zip have null parent
        val source = vfs.getPath("/rootfile.txt")
        vfs.renameFile(source, "newroot.txt")

        val entries = vfs.listFiles(vfs.root)
        assertNotNull(entries.find { it.name == "newroot.txt" })
    }

    @Test
    fun `rename changes extension`() = runBlocking {
        createZip("doc.txt" to "content")

        vfs.renameFile(vfs.getPath("/doc.txt"), "doc.md")

        val entries = vfs.listFiles(vfs.root)
        assertNotNull(entries.find { it.name == "doc.md" })
        assertNull(entries.find { it.name == "doc.txt" })
    }

    @Test
    fun `rename to existing name throws descriptive error`() = runBlocking {
        createZip("a.txt" to "aaa", "b.txt" to "bbb")

        val source = vfs.getPath("/a.txt")
        val ex = try {
            vfs.renameFile(source, "b.txt")
            null
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            e
        }
        assertNotNull("expected FileAlreadyExistsException", ex)
        assertTrue(
            "message should mention the conflicting name",
            ex!!.message?.contains("b.txt") == true,
        )
        // Both entries should still be present — rename must not have damaged the archive.
        val entries = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        assertEquals(2, entries.size)
        assertNotNull(entries.find { it.name == "a.txt" })
        assertNotNull(entries.find { it.name == "b.txt" })
    }
}

// ─── TAR rename ─────────────────────────────────────────────────────────────

class TarRenameTest {

    private lateinit var tarPath: Path
    private lateinit var vfs: TarVirtualFileSystem

    private fun createTar(vararg entries: Triple<String, Boolean, String?>) {
        tarPath = Files.createTempFile("rename-test-", ".tar")
        TarArchiveOutputStream(Files.newOutputStream(tarPath)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, isDir, content) in entries) {
                val entry = TarArchiveEntry(name)
                if (isDir) {
                    tar.putArchiveEntry(entry)
                } else {
                    val bytes = content?.toByteArray() ?: ByteArray(0)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                }
                tar.closeArchiveEntry()
            }
        }
        vfs = TarVirtualFileSystem(tarPath, inputStreamFactory = { Files.newInputStream(it) }, outputStreamFactory = { Files.newOutputStream(it) })
    }

    @After
    fun tearDown() {
        vfs.close()
        Files.deleteIfExists(tarPath)
    }

    @Test
    fun `rename file at root`() = runBlocking {
        createTar(Triple("hello.txt", false, "world"))

        val entries = vfs.listFiles(vfs.root)
        val source = entries.find { it.name == "hello.txt" }!!.path
        vfs.renameFile(source, "renamed.txt")

        val after = vfs.listFiles(vfs.root)
        assertNotNull(after.find { it.name == "renamed.txt" })
        assertNull(after.find { it.name == "hello.txt" })
    }

    @Test
    fun `rename persists to tar archive`() = runBlocking {
        createTar(Triple("hello.txt", false, "world"))

        val entries = vfs.listFiles(vfs.root)
        vfs.renameFile(entries.find { it.name == "hello.txt" }!!.path, "renamed.txt")
        vfs.close()

        // Reopen and verify
        vfs = TarVirtualFileSystem(tarPath, inputStreamFactory = { Files.newInputStream(it) }, outputStreamFactory = { Files.newOutputStream(it) })
        val after = vfs.listFiles(vfs.root)
        assertNotNull(after.find { it.name == "renamed.txt" })
        assertNull(after.find { it.name == "hello.txt" })
    }

    @Test
    fun `rename file in subdirectory`() = runBlocking {
        createTar(
            Triple("sub/", true, null),
            Triple("sub/nested.txt", false, "data"),
        )

        val entries = vfs.listFiles(vfs.root.resolve("sub"))
        val source = entries.find { it.name == "nested.txt" }!!.path
        vfs.renameFile(source, "moved.txt")

        val after = vfs.listFiles(vfs.root.resolve("sub"))
        assertNotNull(after.find { it.name == "moved.txt" })
        assertNull(after.find { it.name == "nested.txt" })
    }

    @Test
    fun `rename preserves other files`() = runBlocking {
        createTar(
            Triple("a.txt", false, "aaa"),
            Triple("b.txt", false, "bbb"),
        )

        val entries = vfs.listFiles(vfs.root)
        vfs.renameFile(entries.find { it.name == "a.txt" }!!.path, "c.txt")

        val after = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        assertEquals(2, after.size)
        assertNotNull(after.find { it.name == "c.txt" })
        assertNotNull(after.find { it.name == "b.txt" })
    }

    @Test
    fun `rename after flush`() = runBlocking {
        createTar(Triple("hello.txt", false, "world"))

        vfs.flush()

        val entries = vfs.listFiles(vfs.root)
        vfs.renameFile(entries.find { it.name == "hello.txt" }!!.path, "renamed.txt")

        val after = vfs.listFiles(vfs.root)
        assertNotNull(after.find { it.name == "renamed.txt" })
    }

    @Test
    fun `multiple renames`() = runBlocking {
        createTar(Triple("file.txt", false, "content"))

        val e1 = vfs.listFiles(vfs.root)
        vfs.renameFile(e1.find { it.name == "file.txt" }!!.path, "step1.txt")

        val e2 = vfs.listFiles(vfs.root)
        vfs.renameFile(e2.find { it.name == "step1.txt" }!!.path, "step2.txt")

        val after = vfs.listFiles(vfs.root)
        assertNotNull(after.find { it.name == "step2.txt" })
        assertNull(after.find { it.name == "file.txt" })
    }

    @Test
    fun `rename preserves file content in archive`() = runBlocking {
        createTar(Triple("data.txt", false, "important content"))

        val entries = vfs.listFiles(vfs.root)
        val source = entries.find { it.name == "data.txt" }!!.path
        vfs.renameFile(source, "renamed.txt")
        vfs.close()

        // Reopen and verify content
        vfs = TarVirtualFileSystem(tarPath, inputStreamFactory = { Files.newInputStream(it) }, outputStreamFactory = { Files.newOutputStream(it) })
        val after = vfs.listFiles(vfs.root)
        val renamed = after.find { it.name == "renamed.txt" }!!
        assertEquals("important content", Files.readString(renamed.path))
    }
}

// ─── TAR.GZ rename ──────────────────────────────────────────────────────────

class TarGzRenameTest {

    private lateinit var tarGzPath: Path
    private lateinit var vfs: TarVirtualFileSystem

    private fun createTarGz(vararg entries: Triple<String, Boolean, String?>) {
        tarGzPath = Files.createTempFile("rename-test-", ".tar.gz")
        GzipCompressorOutputStream(Files.newOutputStream(tarGzPath)).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for ((name, isDir, content) in entries) {
                    val entry = TarArchiveEntry(name)
                    if (isDir) {
                        tar.putArchiveEntry(entry)
                    } else {
                        val bytes = content?.toByteArray() ?: ByteArray(0)
                        entry.size = bytes.size.toLong()
                        tar.putArchiveEntry(entry)
                        tar.write(bytes)
                    }
                    tar.closeArchiveEntry()
                }
            }
        }
        vfs = TarVirtualFileSystem(
            tarGzPath,
            inputStreamFactory = { org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(Files.newInputStream(it)) },
            outputStreamFactory = { GzipCompressorOutputStream(Files.newOutputStream(it)) },
        )
    }

    @After
    fun tearDown() {
        vfs.close()
        Files.deleteIfExists(tarGzPath)
    }

    @Test
    fun `rename file at root`() = runBlocking {
        createTarGz(Triple("hello.txt", false, "world"))

        val entries = vfs.listFiles(vfs.root)
        vfs.renameFile(entries.find { it.name == "hello.txt" }!!.path, "renamed.txt")

        val after = vfs.listFiles(vfs.root)
        assertNotNull(after.find { it.name == "renamed.txt" })
        assertNull(after.find { it.name == "hello.txt" })
    }

    @Test
    fun `rename persists to tar gz archive`() = runBlocking {
        createTarGz(Triple("hello.txt", false, "world"))

        val entries = vfs.listFiles(vfs.root)
        vfs.renameFile(entries.find { it.name == "hello.txt" }!!.path, "renamed.txt")
        vfs.close()

        // Reopen and verify
        vfs = TarVirtualFileSystem(
            tarGzPath,
            inputStreamFactory = { org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(Files.newInputStream(it)) },
            outputStreamFactory = { GzipCompressorOutputStream(Files.newOutputStream(it)) },
        )
        val after = vfs.listFiles(vfs.root)
        assertNotNull(after.find { it.name == "renamed.txt" })
        assertNull(after.find { it.name == "hello.txt" })
    }

    @Test
    fun `rename after flush`() = runBlocking {
        createTarGz(Triple("hello.txt", false, "world"))

        vfs.flush()

        val entries = vfs.listFiles(vfs.root)
        vfs.renameFile(entries.find { it.name == "hello.txt" }!!.path, "renamed.txt")

        val after = vfs.listFiles(vfs.root)
        assertNotNull(after.find { it.name == "renamed.txt" })
    }

    @Test
    fun `rename then flush preserves rename`() = runBlocking {
        createTarGz(Triple("hello.txt", false, "world"))

        val entries = vfs.listFiles(vfs.root)
        vfs.renameFile(entries.find { it.name == "hello.txt" }!!.path, "renamed.txt")

        // flush re-extracts from archive — rename should have been saved
        vfs.flush()

        val after = vfs.listFiles(vfs.root)
        assertNotNull(after.find { it.name == "renamed.txt" })
        assertNull(after.find { it.name == "hello.txt" })
    }

    @Test
    fun `rename in subdirectory`() = runBlocking {
        createTarGz(
            Triple("dir/", true, null),
            Triple("dir/file.txt", false, "content"),
        )

        val entries = vfs.listFiles(vfs.root.resolve("dir"))
        vfs.renameFile(entries.find { it.name == "file.txt" }!!.path, "new.txt")

        val after = vfs.listFiles(vfs.root.resolve("dir"))
        assertNotNull(after.find { it.name == "new.txt" })
        assertNull(after.find { it.name == "file.txt" })
    }
}

// ─── 7Z rename ──────────────────────────────────────────────────────────────

class SevenZRenameTest {

    private lateinit var szPath: Path
    private lateinit var vfs: SevenZipVirtualFileSystem

    private fun createSevenZ(vararg entries: Triple<String, Boolean, String?>) {
        szPath = Files.createTempFile("rename-test-", ".7z")
        Files.delete(szPath) // SevenZOutputFile needs non-existing or empty file
        org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(szPath.toFile()).use { out ->
            for ((name, isDir, content) in entries) {
                val entry = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry().apply {
                    this.name = name
                    this.isDirectory = isDir
                }
                out.putArchiveEntry(entry)
                if (!isDir && content != null) {
                    out.write(content.toByteArray())
                }
                out.closeArchiveEntry()
            }
        }
        vfs = SevenZipVirtualFileSystem(szPath)
    }

    @After
    fun tearDown() {
        vfs.close()
        Files.deleteIfExists(szPath)
    }

    @Test
    fun `rename file at root`() = runBlocking {
        createSevenZ(Triple("hello.txt", false, "world"))

        val entries = vfs.listFiles(vfs.root)
        vfs.renameFile(entries.find { it.name == "hello.txt" }!!.path, "renamed.txt")

        val after = vfs.listFiles(vfs.root)
        assertNotNull(after.find { it.name == "renamed.txt" })
        assertNull(after.find { it.name == "hello.txt" })
    }

    @Test
    fun `rename persists to 7z archive`() = runBlocking {
        createSevenZ(Triple("hello.txt", false, "world"))

        val entries = vfs.listFiles(vfs.root)
        vfs.renameFile(entries.find { it.name == "hello.txt" }!!.path, "renamed.txt")
        vfs.close()

        // Reopen and verify
        vfs = SevenZipVirtualFileSystem(szPath)
        val after = vfs.listFiles(vfs.root)
        assertNotNull(after.find { it.name == "renamed.txt" })
        assertNull(after.find { it.name == "hello.txt" })
    }

    @Test
    fun `rename after flush`() = runBlocking {
        createSevenZ(Triple("hello.txt", false, "world"))

        vfs.flush()

        val entries = vfs.listFiles(vfs.root)
        vfs.renameFile(entries.find { it.name == "hello.txt" }!!.path, "renamed.txt")

        val after = vfs.listFiles(vfs.root)
        assertNotNull(after.find { it.name == "renamed.txt" })
    }

    @Test
    fun `rename preserves other files`() = runBlocking {
        createSevenZ(
            Triple("a.txt", false, "aaa"),
            Triple("b.txt", false, "bbb"),
        )

        val entries = vfs.listFiles(vfs.root)
        vfs.renameFile(entries.find { it.name == "a.txt" }!!.path, "c.txt")

        val after = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        assertEquals(2, after.size)
        assertNotNull(after.find { it.name == "c.txt" })
        assertNotNull(after.find { it.name == "b.txt" })
    }

    @Test
    fun `rename preserves file content`() = runBlocking {
        createSevenZ(Triple("data.txt", false, "important"))

        val entries = vfs.listFiles(vfs.root)
        vfs.renameFile(entries.find { it.name == "data.txt" }!!.path, "renamed.txt")
        vfs.close()

        vfs = SevenZipVirtualFileSystem(szPath)
        val after = vfs.listFiles(vfs.root)
        val renamed = after.find { it.name == "renamed.txt" }!!
        assertEquals("important", Files.readString(renamed.path))
    }

    @Test
    fun `repack preserves content larger than internal buffer`() = runBlocking {
        // Regression: the 7z repack used to read files with a hand-rolled 8 KiB loop.
        // After switching to Files.copy(Path, OutputStream) via a SevenZOutputFile
        // adapter, make sure the content is still preserved byte-for-byte across
        // buffer-boundary sizes (smaller than, equal to, and larger than 8 KiB).
        val small = "a".repeat(100)
        val exact = "b".repeat(8192)
        val large = buildString(50_000) { repeat(50_000) { append(('a' + (it % 26))) } }

        createSevenZ(
            Triple("small.txt", false, small),
            Triple("exact.txt", false, exact),
            Triple("large.txt", false, large),
        )

        val entries = vfs.listFiles(vfs.root)
        vfs.renameFile(entries.find { it.name == "small.txt" }!!.path, "renamed.txt")
        vfs.close()

        vfs = SevenZipVirtualFileSystem(szPath)
        val after = vfs.listFiles(vfs.root).filter { !it.isParentLink }
        assertEquals(3, after.size)
        assertEquals(small, Files.readString(after.find { it.name == "renamed.txt" }!!.path))
        assertEquals(exact, Files.readString(after.find { it.name == "exact.txt" }!!.path))
        assertEquals(large, Files.readString(after.find { it.name == "large.txt" }!!.path))
    }
}

// ─── FileTableModel rename-related ──────────────────────────────────────────

class FileTableModelRenameTest {

    @Test
    fun `name column is editable for regular file`() {
        val model = FileTableModel()
        model.setEntries(listOf(
            FileEntry("file.txt", Path.of("/file.txt"), false, 100, lastModified = null, permissions = ""),
        ))
        assertTrue(model.isCellEditable(0, FileTableModel.COL_NAME))
    }

    @Test
    fun `name column is editable for directory`() {
        val model = FileTableModel()
        model.setEntries(listOf(
            FileEntry("mydir", Path.of("/mydir"), true, 0, lastModified = null, permissions = ""),
        ))
        assertTrue(model.isCellEditable(0, FileTableModel.COL_NAME))
    }

    @Test
    fun `name column is not editable for parent link`() {
        val model = FileTableModel()
        model.setEntries(listOf(
            FileEntry("..", Path.of("/"), true, 0, lastModified = null, permissions = "", isParentLink = true),
        ))
        assertFalse(model.isCellEditable(0, FileTableModel.COL_NAME))
    }

    @Test
    fun `non-name columns are not editable`() {
        val model = FileTableModel()
        model.setEntries(listOf(
            FileEntry("file.txt", Path.of("/file.txt"), false, 100, lastModified = null, permissions = ""),
        ))
        assertFalse(model.isCellEditable(0, FileTableModel.COL_EXT))
        assertFalse(model.isCellEditable(0, FileTableModel.COL_SIZE))
        assertFalse(model.isCellEditable(0, FileTableModel.COL_DATE))
        assertFalse(model.isCellEditable(0, FileTableModel.COL_PERMS))
    }

    @Test
    fun `getValueAt returns name without extension for files`() {
        val model = FileTableModel()
        model.setEntries(listOf(
            FileEntry("report.pdf", Path.of("/report.pdf"), false, 100, lastModified = null, permissions = ""),
        ))
        assertEquals("report", model.getValueAt(0, FileTableModel.COL_NAME))
    }

    @Test
    fun `getValueAt returns full name for directories`() {
        val model = FileTableModel()
        model.setEntries(listOf(
            FileEntry("my.dir", Path.of("/my.dir"), true, 0, lastModified = null, permissions = ""),
        ))
        assertEquals("my.dir", model.getValueAt(0, FileTableModel.COL_NAME))
    }

    @Test
    fun `getValueAt returns full name for dotfiles`() {
        val model = FileTableModel()
        model.setEntries(listOf(
            FileEntry(".gitignore", Path.of("/.gitignore"), false, 100, lastModified = null, permissions = ""),
        ))
        assertEquals(".gitignore", model.getValueAt(0, FileTableModel.COL_NAME))
    }

    @Test
    fun `getValueAt returns full name for extensionless files`() {
        val model = FileTableModel()
        model.setEntries(listOf(
            FileEntry("Makefile", Path.of("/Makefile"), false, 100, lastModified = null, permissions = ""),
        ))
        assertEquals("Makefile", model.getValueAt(0, FileTableModel.COL_NAME))
    }
}
