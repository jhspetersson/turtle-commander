package io.github.jhspetersson.turtlecommander.service

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.settings.ThumbnailSize
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.imageio.ImageIO

class ThumbnailCacheBoundsTest : BasePlatformTestCase() {

    private lateinit var scope: CoroutineScope
    private lateinit var cache: ThumbnailCache
    private lateinit var tmp: Path
    private var originalSize: String = ThumbnailSize.SMALL.name

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cache = ThumbnailCache(scope)
        tmp = Files.createTempDirectory("tc-thumb-bounds")
        val state = TurtleCommanderSettings.getInstance().state
        originalSize = state.thumbnailSize
        state.thumbnailSize = ThumbnailSize.SMALL.name
    }

    override fun tearDown() {
        try {
            TurtleCommanderSettings.getInstance().state.thumbnailSize = originalSize
            scope.cancel()
            Files.walk(tmp).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
        } finally {
            super.tearDown()
        }
    }

    private fun writeImage(name: String): Path {
        val img = BufferedImage(1024, 768, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 768) for (x in 0 until 1024) img.setRGB(x, y, Color(x * 255 / 1024, 128, y * 255 / 768).rgb)
        val path = tmp.resolve(name)
        ImageIO.write(img, "png", path.toFile())
        return path
    }

    private fun mtime(path: Path): FileTime = Files.getLastModifiedTime(path)

    private fun pollUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for $what", condition())
    }

    private fun load(path: Path) {
        cache.requestThumbnail(path, mtime(path), { true }, {})
        pollUntil("thumbnail of ${path.fileName}") { cache.getCachedThumbnail(path) != null }
    }

    fun testUndecodableFileIsDecodedOnceThenSkippedUntilRefresh() {
        val bad = tmp.resolve("bad.png")
        Files.write(bad, ByteArray(512) { it.toByte() })

        assertTrue(cache.requestThumbnail(bad, mtime(bad), { true }, {}))
        pollUntil("negative entry") { cache.isKnownUndecodable(bad) && !cache.isLoading(bad) }

        assertFalse("second request must not schedule another decode", cache.requestThumbnail(bad, mtime(bad), { true }, {}))

        cache.evictDirectory(tmp)
        assertTrue("refresh must clear the negative entry", cache.requestThumbnail(bad, mtime(bad), { true }, {}))
    }

    fun testModifiedUndecodableFileIsRetried() {
        val bad = tmp.resolve("bad.png")
        Files.write(bad, ByteArray(512) { it.toByte() })
        val stamp = mtime(bad)

        assertTrue(cache.requestThumbnail(bad, stamp, { true }, {}))
        pollUntil("negative entry") { cache.isKnownUndecodable(bad) && !cache.isLoading(bad) }

        val newer = FileTime.fromMillis(stamp.toMillis() + 5_000)
        assertTrue("a newer mtime must retry the decode", cache.requestThumbnail(bad, newer, { true }, {}))
    }

    fun testMemoryCacheEvictsLeastRecentlyUsedBeyondBudget() {
        cache.memoryBudgetBytes = 276_480

        val a = writeImage("a.png")
        val b = writeImage("b.png")
        val c = writeImage("c.png")
        val d = writeImage("d.png")

        load(a)
        load(b)
        load(c)
        assertNull("oldest icon must be evicted once the budget is exceeded", cache.getCachedThumbnail(a))
        assertNotNull(cache.getCachedThumbnail(b))
        assertNotNull(cache.getCachedThumbnail(c))

        cache.getCachedThumbnail(b)
        load(d)
        assertNotNull("recently touched icon must survive", cache.getCachedThumbnail(b))
        assertNull("least recently used icon must be evicted", cache.getCachedThumbnail(c))
        assertNotNull(cache.getCachedThumbnail(d))
    }
}
