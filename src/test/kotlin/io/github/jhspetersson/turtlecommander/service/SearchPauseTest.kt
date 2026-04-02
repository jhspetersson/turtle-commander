package io.github.jhspetersson.turtlecommander.service

import io.github.jhspetersson.turtlecommander.dialog.FileSearchCriteria
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class SearchPauseTest {

    @Test
    fun `paused search resumes promptly when unpaused`() {
        val tempDir = Files.createTempDirectory("search-pause-test-")
        // Create some files
        for (i in 1..5) {
            Files.createFile(tempDir.resolve("file$i.txt"))
        }
        val subDir = Files.createDirectory(tempDir.resolve("sub"))
        for (i in 1..5) {
            Files.createFile(subDir.resolve("file$i.txt"))
        }

        val criteria = FileSearchCriteria(
            rootPath = tempDir,
            namePattern = null,
            namePatternMode = io.github.jhspetersson.turtlecommander.dialog.NamePatternMode.GLOB,
            sizeFilter = null,
            creationDateFilter = null,
            modificationDateFilter = null,
        )
        val service = FileSearchService(criteria)

        val searchStarted = CountDownLatch(1)
        val searchDone = AtomicBoolean(false)

        service.paused = true

        val thread = Thread {
            service.search(
                onResult = { searchStarted.countDown() },
                isCancelled = { false },
                onProgress = { _, _ -> searchStarted.countDown() },
            )
            searchDone.set(true)
        }
        thread.start()

        // Wait briefly for search to hit the pause
        Thread.sleep(200)
        assertFalse("Search should be paused", searchDone.get())

        // Unpause and measure resume time
        val before = System.currentTimeMillis()
        service.paused = false
        thread.join(5000)
        val elapsed = System.currentTimeMillis() - before

        assertTrue("Search should complete after unpause", searchDone.get())
        // With the old Thread.sleep(100), worst case is ~100ms. With LockSupport.parkNanos it should be faster.
        // We just verify it completes within reasonable time.
        assertTrue("Resume should be prompt (was ${elapsed}ms)", elapsed < 3000)

        // Cleanup
        tempDir.toFile().deleteRecursively()
    }
}
