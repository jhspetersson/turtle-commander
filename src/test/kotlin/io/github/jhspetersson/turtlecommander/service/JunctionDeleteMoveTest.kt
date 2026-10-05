package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class JunctionDeleteMoveTest {

    private val service = FileOperationService(CoroutineScope(Dispatchers.Unconfined))
    private val tempPaths = mutableListOf<Path>()

    @After
    fun cleanup() {
        for (p in tempPaths.reversed()) {
            try {
                deleteTreeWithoutFollowingJunctions(p)
            } catch (_: Exception) {
            }
        }
    }

    private fun deleteTreeWithoutFollowingJunctions(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        if (!isRealDirectory(root)) {
            Files.deleteIfExists(root)
            return
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isOther) {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun tempDir(prefix: String): Path =
        Files.createTempDirectory(prefix).also { tempPaths.add(it) }

    private fun isJunction(path: Path): Boolean {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return attrs.isDirectory && attrs.isOther
    }

    private fun isRealDirectory(path: Path): Boolean {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return attrs.isDirectory && !attrs.isOther
    }

    private fun createJunctionOrSkip(link: Path, target: Path) {
        assumeTrue("junctions exist only on Windows", SystemInfo.isWindows)
        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        val exit = process.waitFor()
        assumeTrue("mklink /J is not available on this host", exit == 0 && isJunction(link))
    }

    private fun delete(paths: List<Path>): List<Pair<Path, Exception>> = runBlocking {
        val errors = mutableListOf<Pair<Path, Exception>>()
        service.deleteFilesWithProgress(
            paths = paths,
            onProgress = { _, _ -> },
            onError = { path, e -> errors.add(path to e) },
            isCancelled = { false },
        )
        errors
    }

    private fun move(sources: List<Path>, destination: Path): List<Pair<Path, Exception>> = runBlocking {
        val errors = mutableListOf<Pair<Path, Exception>>()
        service.moveFilesWithProgress(
            sources = sources,
            destination = destination,
            initialPolicy = OverwritePolicy.OVERWRITE_ALL,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.OVERWRITE_ALL },
            onError = { path, e -> errors.add(path to e) },
            isCancelled = { false },
        )
        errors
    }

    private class Fixture(val target: Path, val targetFile: Path, val link: Path)

    private fun fixture(prefix: String, linkParent: Path): Fixture {
        val target = tempDir("$prefix-target-")
        val targetFile = Files.writeString(target.resolve("keep.txt"), "payload")
        Files.createDirectories(target.resolve("sub"))
        Files.writeString(target.resolve("sub").resolve("nested.txt"), "nested")
        val link = linkParent.resolve("link")
        createJunctionOrSkip(link, target)
        return Fixture(target, targetFile, link)
    }

    @Test
    fun `deleting a junction removes only the link`() {
        val parent = tempDir("junction-delete-")
        val f = fixture("junction-delete", parent)

        val errors = delete(listOf(f.link))

        assertTrue("errors: $errors", errors.isEmpty())
        assertFalse(Files.exists(f.link, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(f.target))
        assertEquals("payload", Files.readString(f.targetFile))
        assertTrue(Files.exists(f.target.resolve("sub").resolve("nested.txt")))
    }

    @Test
    fun `deleting a directory that contains a junction keeps the junction target`() {
        val parent = tempDir("junction-delete-parent-")
        val holder = Files.createDirectories(parent.resolve("holder"))
        Files.writeString(holder.resolve("own.txt"), "own")
        val f = fixture("junction-delete-parent", holder)

        val errors = delete(listOf(holder))

        assertTrue("errors: $errors", errors.isEmpty())
        assertFalse(Files.exists(holder, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(f.target))
        assertEquals("payload", Files.readString(f.targetFile))
        assertTrue(Files.exists(f.target.resolve("sub").resolve("nested.txt")))
    }

    @Test
    fun `deleting a directory with a junction to an ancestor terminates and removes the directory`() {
        val parent = tempDir("junction-cycle-")
        val holder = Files.createDirectories(parent.resolve("holder"))
        Files.writeString(holder.resolve("own.txt"), "own")
        createJunctionOrSkip(holder.resolve("loop"), holder)

        val errors = delete(listOf(holder))

        assertTrue("errors: $errors", errors.isEmpty())
        assertFalse(Files.exists(holder, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `moving a junction relocates the link node and leaves the target intact`() {
        val source = tempDir("junction-move-src-")
        val destination = tempDir("junction-move-dst-")
        val f = fixture("junction-move", source)

        val errors = move(listOf(f.link), destination)

        assertTrue("errors: $errors", errors.isEmpty())
        assertFalse(Files.exists(f.link, LinkOption.NOFOLLOW_LINKS))
        val moved = destination.resolve("link")
        assertTrue(isJunction(moved))
        assertEquals("payload", Files.readString(moved.resolve("keep.txt")))
        assertEquals("payload", Files.readString(f.targetFile))
        assertTrue(Files.exists(f.target.resolve("sub").resolve("nested.txt")))
    }

    @Test
    fun `moving a directory that contains a junction moves the link node and keeps the target`() {
        val source = tempDir("junction-move-parent-src-")
        val destination = tempDir("junction-move-parent-dst-")
        val holder = Files.createDirectories(source.resolve("holder"))
        Files.writeString(holder.resolve("own.txt"), "own")
        val f = fixture("junction-move-parent", holder)

        val errors = move(listOf(holder), destination)

        assertTrue("errors: $errors", errors.isEmpty())
        assertFalse(Files.exists(holder, LinkOption.NOFOLLOW_LINKS))
        val movedHolder = destination.resolve("holder")
        assertEquals("own", Files.readString(movedHolder.resolve("own.txt")))
        assertTrue(isJunction(movedHolder.resolve("link")))
        assertEquals("payload", Files.readString(f.targetFile))
        assertTrue(Files.exists(f.target.resolve("sub").resolve("nested.txt")))
    }
}
