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
import javax.swing.Icon

/**
 * End-to-end check that the thumbnail pipeline (subsample -> bicubic downscale ->
 * disk cache -> in-memory icon -> paintIcon) actually produces a usable, sharper-
 * than-the-cell rendering. Decodes a real PNG instead of stubbing the file system.
 *
 * Uses [BasePlatformTestCase] because [ThumbnailCache] resolves the configured
 * size via [TurtleCommanderSettings.getInstance], and the disk cache directory
 * via [com.intellij.openapi.application.PathManager] — both need a live
 * application service container.
 */
class ThumbnailCacheRenderTest : BasePlatformTestCase() {

    private lateinit var scope: CoroutineScope
    private lateinit var cache: ThumbnailCache
    private lateinit var tmp: Path
    private var originalSize: String = ThumbnailSize.SMALL.name

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cache = ThumbnailCache(scope)
        tmp = Files.createTempDirectory("tc-thumb-test")
        // Pin to SMALL so assertions about 192-px cache / 64-px display hold no
        // matter what the dev machine has the preference set to.
        val state = TurtleCommanderSettings.getInstance().state
        originalSize = state.thumbnailSize
        state.thumbnailSize = ThumbnailSize.SMALL.name
    }

    override fun tearDown() {
        try {
            TurtleCommanderSettings.getInstance().state.thumbnailSize = originalSize
            scope.cancel()
            cleanupTmp()
        } finally {
            super.tearDown()
        }
    }

    private fun cleanupTmp() {
        if (!::tmp.isInitialized || !Files.exists(tmp)) return
        try {
            Files.walk(tmp).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach {
                    try { Files.deleteIfExists(it) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun makeTestImage(path: Path, w: Int, h: Int) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        // A simple gradient — gives us something with edges so paintIcon writes
        // non-zero pixels we can detect.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = (x * 255 / w).coerceIn(0, 255)
                val b = (y * 255 / h).coerceIn(0, 255)
                img.setRGB(x, y, Color(r, 128, b).rgb)
            }
        }
        ImageIO.write(img, "png", path.toFile())
    }

    private fun runPipeline(sourceW: Int, sourceH: Int): Triple<Icon?, BufferedImage?, Path?> {
        val src = tmp.resolve("test-image-${sourceW}x${sourceH}.png")
        makeTestImage(src, sourceW, sourceH)

        cache.requestThumbnail(
            src,
            FileTime.fromMillis(Files.getLastModifiedTime(src).toMillis()),
            isStillVisible = { true },
            onReady = {},
        )

        // Poll instead of blocking on a latch: the test thread IS the EDT in
        // headless mode, and requestThumbnail finishes by switching to
        // Dispatchers.EDT for the onReady callback — a CountDownLatch.await on
        // the EDT would deadlock waiting for itself. The icon lands in
        // memoryCache *before* the EDT switch, so polling that is enough.
        val deadlineMillis = System.currentTimeMillis() + 30_000
        while (cache.getCachedThumbnail(src) == null && System.currentTimeMillis() < deadlineMillis) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }

        val icon = cache.getCachedThumbnail(src)
        assertNotNull("Thumbnail load timed out for $src", icon)
        val cachePath = cache.getCachePath(src)
        val onDisk = if (cachePath != null && Files.exists(cachePath)) ImageIO.read(cachePath.toFile()) else null
        return Triple(icon, onDisk, cachePath)
    }

    fun testLargeSourceProduces192pxCachedBitmap() {
        val (icon, onDisk, _) = runPipeline(1024, 768)
        assertNotNull("Icon should be produced", icon)
        assertNotNull("Disk cache file should exist", onDisk)
        // SMALL preset has cacheSize=192 — the longer side should land at ~192
        // after the bicubic pass. Allow a 1-px rounding tolerance.
        val maxDim = maxOf(onDisk!!.width, onDisk.height)
        assertTrue("Cached longest side should be ~192, got $maxDim", maxDim in 191..193)
        // Aspect ratio preserved (1024:768 = 4:3 -> 192:144).
        val expectedShort = (192.0 * 768 / 1024).toInt()
        val shortDim = minOf(onDisk.width, onDisk.height)
        assertTrue("Cached short side ~$expectedShort, got $shortDim", shortDim in (expectedShort - 1)..(expectedShort + 1))
    }

    fun testSmallSourceIsPreservedAtNativeSize() {
        // Source smaller than cacheSize should not be upscaled — we accept the
        // native bitmap.
        val (_, onDisk, _) = runPipeline(80, 60)
        assertNotNull(onDisk)
        assertEquals(80, onDisk!!.width)
        assertEquals(60, onDisk.height)
    }

    fun testIconPaintsAtConfiguredDisplaySize() {
        val (icon, _, _) = runPipeline(800, 800)
        assertNotNull(icon)
        // 800×800 source -> 192×192 cached -> downscaled to 64×64 on paint
        // (SMALL preset displaySize).
        assertEquals("Square source should map to 64×64 logical display", 64, icon!!.iconWidth)
        assertEquals(64, icon.iconHeight)

        val canvas = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val g = canvas.createGraphics()
        try {
            icon.paintIcon(null, g, 0, 0)
        } finally {
            g.dispose()
        }
        var anyPainted = false
        outer@ for (y in 0 until 64) {
            for (x in 0 until 64) {
                if ((canvas.getRGB(x, y) ushr 24) != 0) {
                    anyPainted = true
                    break@outer
                }
            }
        }
        assertTrue("paintIcon should write pixels into the target Graphics2D", anyPainted)
    }

    fun testCacheKeyEmbedsSizeSegmentSoPresetsCoexist() {
        // Different size presets must hash to different filenames for the same
        // source path so an entry generated at SMALL doesn't collide with one
        // generated at LARGE on the next size change.
        val src = Path.of("/tmp/example.png")
        val small = cache.getCachePath(src, ThumbnailSize.SMALL.cacheSize)?.fileName?.toString()
        val medium = cache.getCachePath(src, ThumbnailSize.MEDIUM.cacheSize)?.fileName?.toString()
        val large = cache.getCachePath(src, ThumbnailSize.LARGE.cacheSize)?.fileName?.toString()

        assertNotNull(small)
        assertNotNull(medium)
        assertNotNull(large)
        assertFalse("SMALL and MEDIUM should differ", small == medium)
        assertFalse("MEDIUM and LARGE should differ", medium == large)
        assertFalse("SMALL and LARGE should differ", small == large)
        assertTrue("Filename should embed cache size", small!!.contains("-${ThumbnailSize.SMALL.cacheSize}-"))
        assertTrue("Filename should embed cache size", medium!!.contains("-${ThumbnailSize.MEDIUM.cacheSize}-"))
        assertTrue("Filename should embed cache size", large!!.contains("-${ThumbnailSize.LARGE.cacheSize}-"))
    }
}
