package io.github.jhspetersson.turtlecommander.service

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import javax.swing.Icon
import javax.swing.ImageIcon

object ThumbnailCache {

    private const val THUMBNAIL_SIZE = 64
    private const val CACHE_DIR_NAME = "turtle-commander-thumbnails"
    private const val MAX_CONCURRENT_LOADS = 4

    private val IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "tif", "tiff",
    )

    private val cacheDir: Path by lazy {
        val dir = Path.of(PathManager.getSystemPath(), CACHE_DIR_NAME)
        Files.createDirectories(dir)
        dir
    }

    /** In-memory cache: absolute file path -> loaded thumbnail icon */
    private val memoryCache = ConcurrentHashMap<Path, Icon>()

    /** Tracks which files are currently being loaded to avoid duplicate work */
    private val loading = ConcurrentHashMap.newKeySet<Path>()

    /** Limits concurrent image decoding to avoid OOM with many large images */
    private val loadSemaphore = Semaphore(MAX_CONCURRENT_LOADS)

    fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    fun getCachedThumbnail(path: Path): Icon? {
        return memoryCache[path]
    }

    fun requestThumbnail(path: Path, lastModified: FileTime?, isStillVisible: () -> Boolean, onReady: () -> Unit) {
        if (memoryCache.containsKey(path)) return
        if (!loading.add(path)) return

        Thread.startVirtualThread {
            try {
                loadSemaphore.acquire()
                try {
                    if (!isStillVisible()) return@startVirtualThread
                    val icon = loadOrCreateThumbnail(path, lastModified)
                    if (icon != null) {
                        memoryCache[path] = icon
                        onReady()
                    }
                } finally {
                    loadSemaphore.release()
                }
            } catch (e: Exception) {
                thisLogger().debug("Failed to create thumbnail for $path: ${e.message}")
            } finally {
                loading.remove(path)
            }
        }
    }

    fun evictDirectory(directory: Path) {
        val evicted = mutableListOf<Path>()
        val iter = memoryCache.keys().asIterator()
        while (iter.hasNext()) {
            val key = iter.next()
            if (key.startsWith(directory)) {
                evicted.add(key)
            }
        }
        for (key in evicted) {
            memoryCache.remove(key)
        }
        if (evicted.isNotEmpty()) {
            Thread.startVirtualThread {
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
        val cachePath = getCachePath(path)
        val modMillis = lastModified?.toMillis() ?: 0L

        // Check disk cache
        if (cachePath != null && Files.exists(cachePath)) {
            val cacheModified = Files.getLastModifiedTime(cachePath).toMillis()
            if (cacheModified >= modMillis) {
                val img = ImageIO.read(cachePath.toFile())
                if (img != null) return ImageIcon(img)
            }
        }

        // Generate thumbnail from source using subsampled reading
        val thumb = readSubsampledThumbnail(path) ?: return null

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

        return ImageIcon(thumb)
    }

    private fun readSubsampledThumbnail(path: Path): BufferedImage? {
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
                if (maxDim > THUMBNAIL_SIZE) {
                    // Subsample to reduce memory: read every Nth pixel
                    val subsample = maxOf(1, maxDim / THUMBNAIL_SIZE)
                    param.setSourceSubsampling(subsample, subsample, 0, 0)
                }

                val subsampled = reader.read(0, param)

                // Scale to exact thumbnail size
                if (subsampled.width <= THUMBNAIL_SIZE && subsampled.height <= THUMBNAIL_SIZE) {
                    subsampled
                } else {
                    createScaledThumbnail(subsampled)
                }
            } finally {
                reader.dispose()
            }
        }
    }

    private fun createScaledThumbnail(source: BufferedImage): BufferedImage {
        val srcW = source.width
        val srcH = source.height
        val scale = THUMBNAIL_SIZE.toDouble() / maxOf(srcW, srcH)
        val dstW = maxOf(1, (srcW * scale).toInt())
        val dstH = maxOf(1, (srcH * scale).toInt())

        @Suppress("UndesirableClassUsage")
        val thumb = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB)
        val g = thumb.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(source, 0, 0, dstW, dstH, null)
        g.dispose()
        return thumb
    }

    private fun getCachePath(sourcePath: Path): Path? {
        return try {
            // Use a hash of the absolute path to avoid filesystem issues with long paths
            val bytes = sourcePath.toAbsolutePath().toString().toByteArray()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            val hash = digest.take(16).joinToString("") { "%02x".format(it) }
            val name = sourcePath.fileName?.toString() ?: return null
            cacheDir.resolve("$hash-$name.png")
        } catch (_: Exception) {
            null
        }
    }
}
