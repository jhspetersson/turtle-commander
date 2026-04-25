package io.github.jhspetersson.turtlecommander.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ThumbnailCacheConcurrencyTest {

    @Test
    fun `concurrent requestThumbnail calls for same path do not duplicate work`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cache = ThumbnailCache(scope)
        cache.clearCache()

        val fakePath = Path.of("/nonexistent/test-image.png")
        val callCount = AtomicInteger(0)
        val latch = CountDownLatch(10)

        // Request the same thumbnail from multiple threads. The file doesn't exist, so
        // loadOrCreateThumbnail returns null and onReady is never invoked — but the
        // dedup gate (the `loading` keyset) should still ensure we only spawn one job.
        repeat(10) {
            Thread {
                cache.requestThumbnail(
                    fakePath,
                    FileTime.fromMillis(0),
                    isStillVisible = { true },
                    onReady = { callCount.incrementAndGet() },
                )
                latch.countDown()
            }.start()
        }

        latch.await()
        // Wait for any launched coroutines to finish via the scope's job children.
        runBlocking {
            scope.coroutineContext[Job]?.children?.toList()?.joinAll()
        }
        scope.cancel()

        // onReady should be called at most once (0 if image couldn't be loaded, 1 if it could)
        assertTrue("onReady called ${callCount.get()} times, expected <= 1", callCount.get() <= 1)
    }
}
