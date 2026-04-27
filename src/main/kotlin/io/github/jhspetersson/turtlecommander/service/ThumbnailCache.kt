package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import io.github.jhspetersson.turtlecommander.settings.ThumbnailSize
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import javax.swing.Icon

@Service(Service.Level.APP)
class ThumbnailCache(private val scope: CoroutineScope) {

    /**
     * Bounded-parallelism IO dispatcher. Replaces the explicit Semaphore(4) the previous
     * virtual-thread implementation used: limitedParallelism gives the same "at most N
     * concurrent loads" guarantee while reading more naturally than acquire/release pairs.
     */
    @Suppress("OPT_IN_USAGE")
    private val thumbnailDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_LOADS)

    private val cacheDir: Path by lazy {
        val dir = Path.of(PathManager.getSystemPath(), CACHE_DIR_NAME)
        Files.createDirectories(dir)
        dir
    }

    /** In-memory cache: absolute file path -> loaded thumbnail icon */
    private val memoryCache = ConcurrentHashMap<Path, Icon>()

    /** Tracks which files are currently being loaded to avoid duplicate work */
    private val loading = ConcurrentHashMap.newKeySet<Path>()

    fun getCachedThumbnail(path: Path): Icon? {
        return memoryCache[path]
    }

    /**
     * Drop the in-memory icon cache without touching the on-disk cache. Called
     * when the user changes the thumbnail size preset so visible cells re-render
     * at the new logical size on the next paint.
     */
    fun clearMemoryCache() {
        memoryCache.clear()
    }

    private fun currentSize(): ThumbnailSize {
        return ThumbnailSize.fromName(TurtleCommanderSettings.getInstance().state.thumbnailSize)
    }

    /**
     * Fire-and-forget thumbnail load. [onReady] runs on the EDT once the icon lands in
     * [memoryCache] — callers no longer need their own SwingUtilities.invokeLater wrapper.
     * Cancellation is cooperative: the [isStillVisible] gate is re-checked before each
     * expensive step, and the whole job participates in the application-scoped CoroutineScope
     * so it cancels cleanly on shutdown.
     */
    fun requestThumbnail(path: Path, lastModified: FileTime?, isStillVisible: () -> Boolean, onReady: () -> Unit) {
        if (memoryCache.containsKey(path)) return
        if (!loading.add(path)) return

        scope.launch(thumbnailDispatcher) {
            try {
                if (memoryCache.containsKey(path)) return@launch
                if (!isStillVisible()) return@launch
                ensureActive()
                val icon = loadOrCreateThumbnail(path, lastModified) ?: return@launch
                ensureActive()
                memoryCache.putIfAbsent(path, icon)
                withContext(Dispatchers.EDT) { onReady() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().debug("Failed to create thumbnail for $path: ${e.message}")
            } finally {
                loading.remove(path)
            }
        }
    }

    fun evictDirectory(directory: Path) {
        val evicted = mutableListOf<Path>()
        memoryCache.keys.removeAll { key ->
            if (key.startsWith(directory)) {
                evicted.add(key)
                true
            } else {
                false
            }
        }
        if (evicted.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                for (sourcePath in evicted) {
                    try {
                        val cachePath = getCachePath(sourcePath) ?: continue
                        Files.deleteIfExists(cachePath)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun getCacheSize(): Long {
        return try {
            if (!Files.isDirectory(cacheDir)) return 0L
            Files.list(cacheDir).use { stream ->
                stream.mapToLong { path ->
                    try { Files.size(path) } catch (_: Exception) { 0L }
                }.sum()
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun clearCache() {
        memoryCache.clear()
        try {
            if (!Files.isDirectory(cacheDir)) return
            Files.list(cacheDir).use { stream ->
                stream.forEach { path ->
                    try { Files.deleteIfExists(path) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun loadOrCreateThumbnail(path: Path, lastModified: FileTime?): Icon? {
        val size = currentSize()
        val cachePath = getCachePath(path, size.cacheSize)
        val modMillis = lastModified?.toMillis() ?: 0L

        // Check disk cache
        if (cachePath != null && Files.exists(cachePath)) {
            val cacheModified = Files.getLastModifiedTime(cachePath).toMillis()
            if (cacheModified >= modMillis) {
                val img = ImageIO.read(cachePath.toFile())
                if (img != null) return HighQualityImageIcon(img, size.displaySize)
            }
        }

        // Generate thumbnail from source using subsampled reading
        val thumb = readSubsampledThumbnail(path, size.cacheSize) ?: return null

        // Write to disk cache
        if (cachePath != null) {
            try {
                Files.createDirectories(cachePath.parent)
                ImageIO.write(thumb, "png", cachePath.toFile())
                if (modMillis > 0) {
                    Files.setLastModifiedTime(cachePath, FileTime.fromMillis(modMillis))
                }
            } catch (e: Exception) {
                thisLogger().debug("Failed to write thumbnail cache for $path: ${e.message}")
            }
        }

        return HighQualityImageIcon(thumb, size.displaySize)
    }

    private fun readSubsampledThumbnail(path: Path, targetSize: Int): BufferedImage? {
        val stream = try {
            ImageIO.createImageInputStream(path.toFile())
        } catch (_: Exception) {
            return null
        } ?: return null

        return stream.use { imageStream ->
            val readers = ImageIO.getImageReaders(imageStream)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = imageStream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                val maxDim = maxOf(width, height)

                val param: ImageReadParam = reader.defaultReadParam
                // Two-step downscale: subsample to ~2× the target so the bicubic
                // pass in createScaledThumbnail has enough source data to produce
                // a sharp result. Subsampling alone is a nearest-neighbour pick
                // and produces visible aliasing on photographic content.
                val subsampleTarget = targetSize * 2
                if (maxDim > subsampleTarget) {
                    val subsample = maxOf(1, maxDim / subsampleTarget)
                    param.setSourceSubsampling(subsample, subsample, 0, 0)
                }

                val subsampled = reader.read(0, param)

                if (subsampled.width <= targetSize && subsampled.height <= targetSize) {
                    subsampled
                } else {
                    createScaledThumbnail(subsampled, targetSize)
                }
            } finally {
                reader.dispose()
            }
        }
    }

    private fun createScaledThumbnail(source: BufferedImage, targetSize: Int): BufferedImage {
        val srcW = source.width
        val srcH = source.height
        val scale = targetSize.toDouble() / maxOf(srcW, srcH)
        val dstW = maxOf(1, (srcW * scale).toInt())
        val dstH = maxOf(1, (srcH * scale).toInt())

        @Suppress("UndesirableClassUsage")
        val thumb = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB)
        val g = thumb.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(source, 0, 0, dstW, dstH, null)
        } finally {
            g.dispose()
        }
        return thumb
    }

    /**
     * Default-size cache path; kept for [evictDirectory] which doesn't know what
     * size the entry was generated at. Evicting the in-memory entry is enough
     * to force a regeneration; orphaned disk entries will be replaced or wiped
     * on the next manual cache clear.
     */
    internal fun getCachePath(sourcePath: Path): Path? =
        getCachePath(sourcePath, currentSize().cacheSize)

    internal fun getCachePath(sourcePath: Path, cacheSize: Int): Path? {
        return try {
            // Use a hash of the absolute path to avoid filesystem issues with long paths.
            // The cache size is part of the filename so different size presets coexist
            // on disk without colliding, and entries from the old hardcoded 64px layout
            // (which had no size segment) are naturally bypassed.
            val bytes = sourcePath.toAbsolutePath().toString().toByteArray()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            val hash = digest.take(16).joinToString("") { "%02x".format(it) }
            val name = sourcePath.fileName?.toString() ?: return null
            cacheDir.resolve("$hash-$cacheSize-$name.png")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Icon backed by a high-resolution thumbnail bitmap. paintIcon scales the
     * underlying [BufferedImage] to a fixed logical display size with bicubic
     * interpolation, so on HiDPI displays the Graphics2D's existing device-scale
     * transform turns the larger source bitmap into a sharp render at physical
     * pixel resolution.
     */
    private class HighQualityImageIcon(
        private val image: BufferedImage,
        maxLogicalSize: Int,
    ) : Icon {

        private val displayW: Int
        private val displayH: Int

        init {
            val maxDim = maxOf(image.width, image.height)
            if (maxDim <= maxLogicalSize) {
                displayW = image.width
                displayH = image.height
            } else {
                val scale = maxLogicalSize.toDouble() / maxDim
                displayW = maxOf(1, (image.width * scale).toInt())
                displayH = maxOf(1, (image.height * scale).toInt())
            }
        }

        override fun getIconWidth(): Int = displayW
        override fun getIconHeight(): Int = displayH

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.drawImage(image, x, y, displayW, displayH, null)
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        private const val CACHE_DIR_NAME = "turtle-commander-thumbnails"
        private const val MAX_CONCURRENT_LOADS = 4

        private val IMAGE_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "tif", "tiff",
        )

        fun isImageFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in IMAGE_EXTENSIONS
        }

        fun getInstance(): ThumbnailCache =
            ApplicationManager.getApplication().getService(ThumbnailCache::class.java)
    }
}
