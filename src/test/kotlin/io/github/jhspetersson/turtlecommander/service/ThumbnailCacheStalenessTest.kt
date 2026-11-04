package io.github.jhspetersson.turtlecommander.service

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.settings.ThumbnailSize
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.vfs.OpenVfsRegistry
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class ThumbnailCacheStalenessTest : BasePlatformTestCase() {

    private lateinit var scope: CoroutineScope
    private lateinit var cache: ThumbnailCache
    private lateinit var tmp: Path
    private var originalSize: String = ThumbnailSize.SMALL.name

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cache = ThumbnailCache(scope)
        cache.clearCache()
        tmp = Files.createTempDirectory("tc-thumb-stale")
        val state = TurtleCommanderSettings.getInstance().state
        originalSize = state.thumbnailSize
        state.thumbnailSize = ThumbnailSize.SMALL.name
    }

    override fun tearDown() {
        try {
            TurtleCommanderSettings.getInstance().state.thumbnailSize = originalSize
            scope.cancel()
            cache.clearCache()
            Files.walk(tmp).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
        } finally {
            super.tearDown()
        }
    }

    private fun pngBytes(seed: Int): ByteArray {
        val img = BufferedImage(1024, 768, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 768) for (x in 0 until 1024) {
            img.setRGB(x, y, Color((x + seed * 37) % 256, (y * seed + x) % 256, (x * y + seed) % 256).rgb)
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun writeImage(dir: Path, name: String, seed: Int): Path {
        Files.createDirectories(dir)
        val path = dir.resolve(name)
        Files.write(path, pngBytes(seed))
        return path
    }

    private fun pollUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for $what", condition())
    }

    private fun load(path: Path) {
        cache.requestThumbnail(path, Files.getLastModifiedTime(path), { true }, {})
        pollUntil("thumbnail of ${path.fileName}") { cache.getCachedThumbnail(path) != null }
    }

    private class GatedVfs(override val root: Path) : VirtualFileSystem {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override val archivePath: Path = root.resolveSibling("gated.fake")
        override suspend fun listFiles(directory: Path): List<FileEntry> = emptyList()
        override fun isRoot(path: Path): Boolean = path == root
        override fun getPath(relativePath: String): Path = root.resolve(relativePath)
        override fun flush() {}
        override suspend fun renameFile(source: Path, newName: String): Path = source
        override fun close() {}
        override fun materialize(path: Path) {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
    }

    fun testThumbnailDecodedBeforeSizeChangeIsNotCachedAfterIt() {
        val vfs = GatedVfs(tmp.resolve("gated"))
        val image = writeImage(vfs.root, "photo.png", 1)
        OpenVfsRegistry.register(vfs)
        try {
            assertTrue(cache.requestThumbnail(image, Files.getLastModifiedTime(image), { true }, {}))
            assertTrue("decode must start", vfs.entered.await(30, TimeUnit.SECONDS))

            TurtleCommanderSettings.getInstance().state.thumbnailSize = ThumbnailSize.LARGE.name
            cache.clearMemoryCache()
            vfs.release.countDown()

            pollUntil("load to finish") { !cache.isLoading(image) }
            val icon = cache.getCachedThumbnail(image)
            if (icon != null) {
                assertTrue(
                    "icon cached after the size change must be LARGE, was ${icon.iconWidth}",
                    icon.iconWidth > ThumbnailSize.SMALL.displaySize,
                )
            }
        } finally {
            vfs.release.countDown()
            OpenVfsRegistry.unregister(vfs)
        }
    }

    fun testCorruptDiskCacheEntryIsRegeneratedNotMarkedUndecodable() {
        val image = writeImage(tmp, "photo.png", 2)
        val cachePath = cache.getCachePath(image, ThumbnailSize.SMALL.cacheSize)!!
        Files.createDirectories(cachePath.parent)
        Files.write(cachePath, pngBytes(3).copyOf(120))
        Files.setLastModifiedTime(cachePath, FileTime.fromMillis(Files.getLastModifiedTime(image).toMillis() + 60_000))

        assertTrue(cache.requestThumbnail(image, Files.getLastModifiedTime(image), { true }, {}))
        pollUntil("load to finish") { !cache.isLoading(image) }

        assertFalse("a corrupt cache file must not mark the source undecodable", cache.isKnownUndecodable(image))
        assertNotNull("the thumbnail must be regenerated from the source", cache.getCachedThumbnail(image))
        assertNotNull("the rewritten cache file must decode", ImageIO.read(cachePath.toFile()))
    }

    fun testDiskCacheIsPrunedOldestFirstBeyondBudget() {
        val first = writeImage(tmp, "img0.png", 10)
        load(first)
        val firstCache = cache.getCachePath(first, ThumbnailSize.SMALL.cacheSize)!!
        pollUntil("first cache file") { Files.exists(firstCache) }
        val perEntry = Files.size(firstCache)
        cache.diskBudgetBytes = perEntry * 5 / 2

        val rest = (1..4).map { writeImage(tmp, "img$it.png", 10 + it) }
        rest.forEach { load(it) }
        val lastCache = cache.getCachePath(rest.last(), ThumbnailSize.SMALL.cacheSize)!!
        pollUntil("last cache file") { Files.exists(lastCache) }

        assertTrue(
            "disk cache ${cache.getCacheSize()} must stay within ${cache.diskBudgetBytes}",
            cache.getCacheSize() <= cache.diskBudgetBytes,
        )
        assertFalse("oldest cache file must be pruned", Files.exists(firstCache))
        assertTrue("newest cache file must be kept", Files.exists(lastCache))
    }
}
