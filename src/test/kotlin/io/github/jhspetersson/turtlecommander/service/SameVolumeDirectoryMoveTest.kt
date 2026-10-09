package io.github.jhspetersson.turtlecommander.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class SameVolumeDirectoryMoveTest {

    private val tempPaths = mutableListOf<Path>()

    @After
    fun cleanup() {
        for (p in tempPaths.reversed()) {
            try {
                if (Files.isDirectory(p)) {
                    Files.walk(p).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                } else {
                    Files.deleteIfExists(p)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun newService() = FileOperationService(CoroutineScope(Dispatchers.Unconfined))

    private fun makeTree(prefix: String): Path {
        val src = Files.createTempDirectory(prefix)
        tempPaths.add(src)
        val sub = Files.createDirectory(src.resolve("sub"))
        Files.writeString(src.resolve("a.txt"), "a")
        Files.writeString(sub.resolve("b.txt"), "b")
        Files.writeString(sub.resolve("c.txt"), "c")
        return src
    }

    private suspend fun move(service: FileOperationService, src: Path, destParent: Path, progress: MutableList<Pair<Int, String>>, errors: MutableList<Path>) {
        service.moveFilesWithProgress(
            sources = listOf(src),
            destination = destParent,
            initialPolicy = OverwritePolicy.OVERWRITE_ALL,
            onProgress = { count, name -> progress.add(count to name) },
            onOverwriteConfirm = { OverwriteResponse.OVERWRITE_ALL },
            onError = { path, _ -> errors.add(path) },
            isCancelled = { false },
        )
    }

    @Test
    fun `moving a directory to a fresh target on the same volume renames it in one step`() = runBlocking {
        val service = newService()
        val src = makeTree("same-vol-fresh-src-")
        val stamp = FileTime.from(Instant.parse("2001-02-03T04:05:06Z"))
        Files.setLastModifiedTime(src, stamp)
        val destParent = Files.createTempDirectory("same-vol-fresh-dst-")
        tempPaths.add(destParent)

        val progress = mutableListOf<Pair<Int, String>>()
        val errors = mutableListOf<Path>()
        move(service, src, destParent, progress, errors)

        val target = destParent.resolve(src.fileName.toString())
        assertTrue("no errors expected, got: $errors", errors.isEmpty())
        assertFalse("source directory must be gone", Files.exists(src))
        assertEquals("a", Files.readString(target.resolve("a.txt")))
        assertEquals("b", Files.readString(target.resolve("sub").resolve("b.txt")))
        assertEquals("c", Files.readString(target.resolve("sub").resolve("c.txt")))
        assertEquals("a single rename reports one step, got: $progress", listOf(1 to src.fileName.toString()), progress)
        assertEquals("a rename keeps the directory's own mtime", stamp.toMillis(), Files.getLastModifiedTime(target).toMillis())
    }

    @Test
    fun `merging into an existing tree renames the sub-directories the target lacks`() = runBlocking {
        val service = newService()
        val src = makeTree("same-vol-merge-src-")
        val stamp = FileTime.from(Instant.parse("2001-02-03T04:05:06Z"))
        Files.setLastModifiedTime(src.resolve("sub"), stamp)
        val destParent = Files.createTempDirectory("same-vol-merge-dst-")
        tempPaths.add(destParent)
        val destTop = Files.createDirectory(destParent.resolve(src.fileName.toString()))
        Files.writeString(destTop.resolve("old.txt"), "old")

        val progress = mutableListOf<Pair<Int, String>>()
        val errors = mutableListOf<Path>()
        move(service, src, destParent, progress, errors)

        assertTrue("no errors expected, got: $errors", errors.isEmpty())
        assertFalse("source directory must be gone", Files.exists(src))
        assertEquals("old", Files.readString(destTop.resolve("old.txt")))
        assertEquals("a", Files.readString(destTop.resolve("a.txt")))
        assertEquals("b", Files.readString(destTop.resolve("sub").resolve("b.txt")))
        assertEquals("c", Files.readString(destTop.resolve("sub").resolve("c.txt")))
        assertEquals("one step per moved file plus one for the renamed sub-directory, got: $progress", setOf("a.txt", "sub"), progress.map { it.second }.toSet())
        assertEquals(2, progress.last().first)
        assertEquals("a renamed sub-directory keeps its mtime", stamp.toMillis(), Files.getLastModifiedTime(destTop.resolve("sub")).toMillis())
    }

    @Test
    fun `an existing file in the way of a directory falls back to the per-entry walk and reports it`() = runBlocking {
        val service = newService()
        val src = makeTree("same-vol-blocked-src-")
        val destParent = Files.createTempDirectory("same-vol-blocked-dst-")
        tempPaths.add(destParent)
        val destTop = Files.createDirectory(destParent.resolve(src.fileName.toString()))
        Files.writeString(destTop.resolve("sub"), "a file where a directory should go")

        val progress = mutableListOf<Pair<Int, String>>()
        val errors = mutableListOf<Path>()
        move(service, src, destParent, progress, errors)

        assertEquals("a", Files.readString(destTop.resolve("a.txt")))
        assertTrue("the blocked sub-directory must be reported", errors.contains(src.resolve("sub")))
        assertTrue("the blocked sub-directory stays in the source", Files.exists(src.resolve("sub").resolve("b.txt")))
        assertTrue("the source directory stays because something was left behind", Files.isDirectory(src))
    }
}
