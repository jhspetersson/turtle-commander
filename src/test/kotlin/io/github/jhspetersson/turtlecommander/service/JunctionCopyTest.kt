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
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class JunctionCopyTest {

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

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
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

    private fun createJunctionOrSkip(link: Path, target: Path) {
        assumeTrue("junctions exist only on Windows", SystemInfo.isWindows)
        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        val exit = process.waitFor()
        assumeTrue("mklink /J is not available on this host", exit == 0 && isJunction(link))
    }

    private fun copy(sources: List<Path>, destination: Path): List<Pair<Path, Exception>> = runBlocking {
        val errors = mutableListOf<Pair<Path, Exception>>()
        service.copyFilesWithProgress(
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

    @Test
    fun `junction pointing back into the tree being copied is reported instead of recursing`() {
        val root = tempDir("turtle-junction-copy-")
        val src = Files.createDirectory(root.resolve("src"))
        Files.writeString(src.resolve("file.txt"), "content")
        val loop = src.resolve("loop")
        createJunctionOrSkip(loop, src)
        val dest = Files.createDirectory(root.resolve("dest"))

        val errors = copy(listOf(src), dest)

        assertEquals("content", Files.readString(dest.resolve("src/file.txt")))
        assertEquals(errors.toString(), listOf(loop), errors.map { it.first })
        assertFalse(Files.exists(dest.resolve("src/loop/file.txt"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `junction pointing outside the tree is copied as its content`() {
        val root = tempDir("turtle-junction-copy-")
        val other = Files.createDirectory(root.resolve("other"))
        Files.writeString(other.resolve("g.txt"), "outside")
        val src = Files.createDirectory(root.resolve("src"))
        createJunctionOrSkip(src.resolve("link"), other)
        val dest = Files.createDirectory(root.resolve("dest"))

        val errors = copy(listOf(src), dest)

        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals("outside", Files.readString(dest.resolve("src/link/g.txt")))
        assertFalse(isJunction(dest.resolve("src/link")))
    }
}
