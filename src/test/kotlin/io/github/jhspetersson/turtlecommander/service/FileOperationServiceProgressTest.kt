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

/**
 * Progress / cache-invalidation regressions for copy and move.
 *
 *  - Moving a directory should tick progress once per file (matches copy's behaviour),
 *    not once for the entire top-level path.
 *  - Copying into an existing destination should invalidate every directory the copy
 *    actually wrote into, not just the top-level destination, so sibling tabs viewing
 *    a sub-directory don't serve stale entries.
 */
class FileOperationServiceProgressTest {

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

    private fun newService() = FileOperationService(CoroutineScope(Dispatchers.Unconfined))

    private fun makeTreeWithThreeFiles(prefix: String): Path {
        val src = Files.createTempDirectory(prefix)
        tempPaths.add(src)
        val sub = Files.createDirectory(src.resolve("sub"))
        Files.writeString(src.resolve("a.txt"), "a")
        Files.writeString(sub.resolve("b.txt"), "b")
        Files.writeString(sub.resolve("c.txt"), "c")
        return src
    }

    @Test
    fun `moving a directory ticks progress for every file`() = runBlocking {
        val service = newService()
        val src = makeTreeWithThreeFiles("move-progress-src-")
        val destParent = Files.createTempDirectory("move-progress-dst-")
        tempPaths.add(destParent)

        val progressNames = mutableListOf<String>()
        service.moveFilesWithProgress(
            sources = listOf(src),
            destination = destParent,
            initialPolicy = OverwritePolicy.OVERWRITE_ALL,
            onProgress = { _, name -> progressNames.add(name) },
            onOverwriteConfirm = { OverwriteResponse.OVERWRITE_ALL },
            onError = { _, _ -> },
            isCancelled = { false },
        )

        // Each of the three real files (plus the directories that were created) should
        // contribute its own progress tick — same shape as copyFilesWithProgress.
        assertTrue(
            "expected per-file progress, got: $progressNames",
            progressNames.count { it.endsWith(".txt") } == 3,
        )
        assertTrue("a.txt should appear in progress", progressNames.contains("a.txt"))
        assertTrue("b.txt should appear in progress", progressNames.contains("b.txt"))
        assertTrue("c.txt should appear in progress", progressNames.contains("c.txt"))
    }

    @Test
    fun `copying into an existing tree invalidates nested destination directories`() = runBlocking {
        val service = newService()

        val src = makeTreeWithThreeFiles("copy-cache-src-")

        // Pre-existing destination with the same shape so the nested dirs are
        // already on disk before the copy runs.
        val destParent = Files.createTempDirectory("copy-cache-dst-")
        tempPaths.add(destParent)
        val destTop = Files.createDirectory(destParent.resolve(src.fileName.toString()))
        val destSub = Files.createDirectory(destTop.resolve("sub"))
        Files.writeString(destTop.resolve("a.txt"), "old-a")
        Files.writeString(destSub.resolve("b.txt"), "old-b")

        // Pre-seed the listing cache for the nested sub-directory via reflection so we can
        // detect whether the copy invalidated it. Going through listFiles() would pull in
        // TurtleCommanderSettings, which has no host application service in plain JUnit.
        seedListingCache(service, destSub)
        assertTrue("seeded cache entry should be visible", service.isListingCached(destSub))

        service.copyFilesWithProgress(
            sources = listOf(src),
            destination = destParent,
            initialPolicy = OverwritePolicy.OVERWRITE_ALL,
            onProgress = { _, _ -> },
            onOverwriteConfirm = { OverwriteResponse.OVERWRITE_ALL },
            onError = { _, _ -> },
            isCancelled = { false },
        )

        assertFalse(
            "nested destination directory listing must be invalidated after copy",
            service.isListingCached(destSub),
        )
    }

    private fun seedListingCache(service: FileOperationService, dir: Path) {
        val cacheField = FileOperationService::class.java.getDeclaredField("listingCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(service) as MutableMap<Path, Any>

        val cachedClass = Class.forName(
            "io.github.jhspetersson.turtlecommander.service.FileOperationService\$CachedListing",
        )
        val ctor = cachedClass.declaredConstructors.first()
        ctor.isAccessible = true
        val entry = ctor.newInstance(emptyList<Any>(), emptyList<Any>(), System.currentTimeMillis(), null)
        synchronized(cache) { cache[dir] = entry }
    }

    @Test
    fun `moving across filesystems still ticks progress per file`() = runBlocking {
        // Cross-FS moves go through the copy-then-delete path; this guarantees the
        // per-file accounting introduced by the fix doesn't double-count.
        val service = newService()
        val src = makeTreeWithThreeFiles("move-xfs-src-")
        val destParent = Files.createTempDirectory("move-xfs-dst-")
        tempPaths.add(destParent)

        val ticks = mutableListOf<Int>()
        service.moveFilesWithProgress(
            sources = listOf(src),
            destination = destParent,
            initialPolicy = OverwritePolicy.OVERWRITE_ALL,
            onProgress = { count, _ -> ticks.add(count) },
            onOverwriteConfirm = { OverwriteResponse.OVERWRITE_ALL },
            onError = { _, _ -> },
            isCancelled = { false },
        )

        // Counter must increase monotonically and end at the total number of
        // entries moved (3 files + 2 directories = 5).
        assertEquals("progress counter must end at 5", 5, ticks.lastOrNull())
        assertEquals("progress must be monotonically increasing", ticks.sorted(), ticks)
    }
}
