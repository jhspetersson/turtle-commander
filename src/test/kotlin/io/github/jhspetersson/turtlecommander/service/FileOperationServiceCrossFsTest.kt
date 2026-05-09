package io.github.jhspetersson.turtlecommander.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Regression for crossFileSystemMove: when moving a directory across filesystems with
 * REPLACE_EXISTING, the target dir must be replaced (not merged with stale leftovers).
 */
class FileOperationServiceCrossFsTest {

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
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    @Test
    fun `crossFileSystemMove of directory with REPLACE_EXISTING removes stale files in target`() {
        val service = FileOperationService(CoroutineScope(Dispatchers.Unconfined))

        val srcDir = Files.createTempDirectory("xfs-src-")
        tempPaths.add(srcDir)
        Files.writeString(srcDir.resolve("new.txt"), "new")

        val zipPath = Files.createTempFile("xfs-tgt-", ".zip")
        Files.deleteIfExists(zipPath)
        tempPaths.add(zipPath)

        // Pre-populate the zip with a target dir that already has a stale file
        FileSystems.newFileSystem(
            URI.create("jar:" + zipPath.toUri()),
            mapOf("create" to "true"),
        ).use { zipFs ->
            val tgt = zipFs.getPath("/" + srcDir.fileName.toString())
            Files.createDirectories(tgt)
            Files.writeString(tgt.resolve("stale.txt"), "stale")
        }

        FileSystems.newFileSystem(zipPath, emptyMap<String, Any>()).use { zipFs ->
            val tgt = zipFs.getPath("/" + srcDir.fileName.toString())
            service.crossFileSystemMove(srcDir, tgt, StandardCopyOption.REPLACE_EXISTING)

            assertTrue("new file should be present after move", Files.exists(tgt.resolve("new.txt")))
            assertFalse(
                "stale file in target dir should be removed when REPLACE_EXISTING is set",
                Files.exists(tgt.resolve("stale.txt")),
            )
        }
    }
}
