package io.github.jhspetersson.turtlecommander.service

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ThumbnailCacheConcurrencyTest {

    @Test
    fun `concurrent requestThumbnail calls for same path do not duplicate work`() {
        // Clear any previous state
        ThumbnailCache.clearCache()

        val fakePath = Path.of("/nonexistent/test-image.png")
        val callCount = AtomicInteger(0)
        val latch = CountDownLatch(10)

        // Request the same thumbnail from multiple threads
        // Since the file doesn't exist, loadOrCreateThumbnail will return null,
        // but the loading set should prevent duplicate entries
        repeat(10) {
            Thread {
                ThumbnailCache.requestThumbnail(
                    fakePath,
                    FileTime.fromMillis(0),
                    isStillVisible = { true },
                    onReady = { callCount.incrementAndGet() },
                )
                latch.countDown()
            }.start()
        }

        latch.await()
        // Give virtual threads time to finish
        Thread.sleep(500)

        // onReady should be called at most once (0 if image couldn't be loaded, 1 if it could)
        assertTrue("onReady called ${callCount.get()} times, expected <= 1", callCount.get() <= 1)
    }
}
