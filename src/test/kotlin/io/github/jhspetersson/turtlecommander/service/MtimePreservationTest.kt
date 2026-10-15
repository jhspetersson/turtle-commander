package io.github.jhspetersson.turtlecommander.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

class MtimePreservationTest {

    private val stamp = 1_500_000_000_000L
    private val tempPaths = mutableListOf<Path>()

    @After
    fun cleanup() {
        for (p in tempPaths.reversed()) {
            runCatching {
                if (Files.isDirectory(p)) Files.walk(p).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                else Files.deleteIfExists(p)
            }
        }
    }

    private fun zipWith(vararg names: String): Path {
        val zip = Files.createTempFile("mtime-", ".zip")
        tempPaths.add(zip)
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            for (name in names) {
                val entry = ZipEntry(name)
                entry.time = stamp
                zos.putNextEntry(entry)
                if (!name.endsWith("/")) zos.write("payload of $name".toByteArray())
                zos.closeEntry()
            }
        }
        return zip
    }

    private fun localDir(): Path = Files.createTempDirectory("mtime-dst-").also { tempPaths.add(it) }

    private fun assertStamped(path: Path) {
        val actual = Files.getLastModifiedTime(path).toMillis()
        assertTrue("$path mtime $actual should be the archived stamp $stamp", abs(actual - stamp) < 2_000)
    }

    @Test
    fun `cross-filesystem move of a file keeps its mtime`() {
        val service = FileOperationService(CoroutineScope(Dispatchers.Unconfined))
        val zip = zipWith("a.txt", "b.txt")
        val dst = localDir()
        Files.writeString(dst.resolve("b.txt"), "old")
        FileSystems.newFileSystem(zip).use { zipFs ->
            service.crossFileSystemMove(zipFs.getPath("/a.txt"), dst.resolve("a.txt"))
            service.crossFileSystemMove(zipFs.getPath("/b.txt"), dst.resolve("b.txt"), StandardCopyOption.REPLACE_EXISTING)
        }
        assertStamped(dst.resolve("a.txt"))
        assertStamped(dst.resolve("b.txt"))
    }

    @Test
    fun `cross-filesystem move of a directory keeps the mtime of every file`() {
        val service = FileOperationService(CoroutineScope(Dispatchers.Unconfined))
        val zip = zipWith("dir/", "dir/inner.txt", "dir/sub/deep.txt")
        val dst = localDir()
        FileSystems.newFileSystem(zip).use { zipFs ->
            service.crossFileSystemMove(zipFs.getPath("/dir"), dst.resolve("dir"))
        }
        assertStamped(dst.resolve("dir/inner.txt"))
        assertStamped(dst.resolve("dir/sub/deep.txt"))
    }

    @Test
    fun `copy out of an archive keeps the mtime`() = runBlocking {
        val service = FileOperationService(CoroutineScope(Dispatchers.Unconfined))
        val zip = zipWith("a.txt", "dir/", "dir/inner.txt")
        val dst = localDir()
        FileSystems.newFileSystem(zip).use { zipFs ->
            service.copyFilesWithProgress(
                listOf(zipFs.getPath("/a.txt"), zipFs.getPath("/dir")),
                dst,
                OverwritePolicy.OVERWRITE_ALL,
                onProgress = { _, _ -> },
                onOverwriteConfirm = { OverwriteResponse.OVERWRITE },
                onError = { path, e -> throw AssertionError("copy of $path failed", e) },
                isCancelled = { false },
            )
        }
        assertEquals("payload of a.txt", Files.readString(dst.resolve("a.txt")))
        assertStamped(dst.resolve("a.txt"))
        assertStamped(dst.resolve("dir/inner.txt"))
    }

    @Test
    fun `extraction keeps the entry mtime`() = runBlocking {
        val zip = zipWith("a.txt", "dir/", "dir/inner.txt")
        val dst = localDir()
        Files.createDirectories(dst.resolve("dir"))
        Files.writeString(dst.resolve("dir/inner.txt"), "old")
        ArchiveService().extractArchiveWithProgress(
            zip,
            dst,
            OverwritePolicy.OVERWRITE_ALL,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.OVERWRITE },
            onError = { path, e -> throw AssertionError("extract of $path failed", e) },
            isCancelled = { false },
        )
        assertEquals("payload of a.txt", Files.readString(dst.resolve("a.txt")))
        assertStamped(dst.resolve("a.txt"))
        assertStamped(dst.resolve("dir/inner.txt"))
    }

    @Test
    fun `local copy keeps the mtime of a stamped source`() = runBlocking {
        val service = FileOperationService(CoroutineScope(Dispatchers.Unconfined))
        val src = localDir()
        val file = Files.writeString(src.resolve("stamped.txt"), "x")
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp))
        val dst = localDir()
        service.copyFilesWithProgress(
            listOf(file),
            dst,
            OverwritePolicy.OVERWRITE_ALL,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.OVERWRITE },
            onError = { path, e -> throw AssertionError("copy of $path failed", e) },
            isCancelled = { false },
        )
        assertStamped(dst.resolve("stamped.txt"))
    }
}
