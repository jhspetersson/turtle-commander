package io.github.jhspetersson.turtlecommander.service

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.settings.ThumbnailSize
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import io.github.jhspetersson.turtlecommander.vfs.CompressedStreams
import io.github.jhspetersson.turtlecommander.vfs.TarVirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VfsOpenProgress
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystem
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemProvider
import io.github.jhspetersson.turtlecommander.vfs.VirtualFileSystemRegistry
import io.github.jhspetersson.turtlecommander.vfs.ZipVirtualFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

class ThumbnailCacheArchiveTest : BasePlatformTestCase() {

    companion object {
        private const val EXT = "thumbtgz"
        private const val IMAGES = 30
        private val opens = AtomicInteger()

        init {
            VirtualFileSystemRegistry.register(object : VirtualFileSystemProvider {
                override fun supportsExtension(ext: String): Boolean = ext == EXT
                override fun create(archivePath: Path): VirtualFileSystem = create(archivePath, null)
                override fun create(archivePath: Path, openProgress: VfsOpenProgress?): VirtualFileSystem =
                    TarVirtualFileSystem(
                        archivePath,
                        inputStreamFactory = { path ->
                            opens.incrementAndGet()
                            CompressedStreams.openInput(path) { raw -> CompressedStreams.gzipInput(raw) }
                        },
                        openProgress = openProgress,
                    )
            })
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var cache: ThumbnailCache
    private lateinit var tmp: Path
    private var originalSize: String = ThumbnailSize.SMALL.name

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cache = ThumbnailCache(scope)
        cache.clearCache()
        tmp = Files.createTempDirectory("tc-thumb-archive")
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
        val img = BufferedImage(256, 192, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 192) for (x in 0 until 256) {
            img.setRGB(x, y, Color((x + seed * 7) % 256, (y + seed * 13) % 256, seed % 256).rgb)
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun buildTar(): Path {
        val archive = tmp.resolve("photos.$EXT")
        GzipCompressorOutputStream(Files.newOutputStream(archive)).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                for (i in 0 until IMAGES) {
                    val body = pngBytes(i)
                    val entry = TarArchiveEntry("album/img$i.png")
                    entry.size = body.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(body)
                    tar.closeArchiveEntry()
                }
            }
        }
        return archive
    }

    private fun buildZip(name: String, seed: Int): Path {
        val archive = tmp.resolve(name)
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("img.png"))
            zip.write(pngBytes(seed))
            zip.closeEntry()
        }
        return archive
    }

    private fun pollUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for $what", condition())
    }

    private fun request(path: Path) {
        cache.requestThumbnail(path, Files.getLastModifiedTime(path), { true }, {})
    }

    fun testThumbnailsInsideCompressedTarStreamTheArchiveOncePerDirectory() {
        val archive = buildTar()
        opens.set(0)

        VirtualFileSystemRegistry.create(archive).use { vfs ->
            val images = (0 until IMAGES).map { vfs.root.resolve("album/img$it.png") }
            images.forEach { request(it) }
            pollUntil("all thumbnails") { images.all { cache.getCachedThumbnail(it) != null } }
            assertEquals(2, opens.get())
            for (image in images) {
                val icon = cache.getCachedThumbnail(image)!!
                assertTrue(icon.iconWidth in 1..64 && icon.iconHeight in 1..64)
            }
        }
    }

    fun testZipfsEntryProducesThumbnailAndCacheKeysDoNotCollideAcrossArchives() {
        val first = buildZip("one.zip", 1)
        val second = buildZip("two.zip", 2)

        VirtualFileSystemRegistry.create(first).use { a ->
            VirtualFileSystemRegistry.create(second).use { b ->
                assertTrue(a is ZipVirtualFileSystem)
                val imgA = a.root.resolve("img.png")
                val imgB = b.root.resolve("img.png")
                request(imgA)
                request(imgB)
                pollUntil("zip thumbnails") {
                    cache.getCachedThumbnail(imgA) != null && cache.getCachedThumbnail(imgB) != null
                }
                val keyA = cache.getCachePath(imgA)
                val keyB = cache.getCachePath(imgB)
                assertNotNull(keyA)
                assertNotNull(keyB)
                assertFalse(keyA == keyB)
                assertTrue(Files.exists(keyA!!))
                assertTrue(Files.exists(keyB!!))
            }
        }
    }

    fun testCachePathOfArchiveEntryIsStableAcrossReopens() {
        val archive = buildTar()
        val firstKey = VirtualFileSystemRegistry.create(archive).use { vfs ->
            cache.getCachePath(vfs.root.resolve("album/img3.png"))
        }
        val secondKey = VirtualFileSystemRegistry.create(archive).use { vfs ->
            cache.getCachePath(vfs.root.resolve("album/img3.png"))
        }
        assertNotNull(firstKey)
        assertEquals(firstKey, secondKey)
    }
}
