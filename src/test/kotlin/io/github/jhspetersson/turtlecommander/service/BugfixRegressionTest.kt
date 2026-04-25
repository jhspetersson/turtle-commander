package io.github.jhspetersson.turtlecommander.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

/**
 * Regression tests for thumbnail cache hash collision fix.
 */
class ThumbnailCacheHashTest {

    private fun newCache(): ThumbnailCache =
        ThumbnailCache(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Test
    fun `different paths same filename get different cache paths`() {
        val cache = newCache()
        val path1 = Path.of("C:/photos/img.jpg")
        val path2 = Path.of("D:/backup/img.jpg")

        val cache1 = cache.getCachePath(path1)
        val cache2 = cache.getCachePath(path2)

        assertNotNull("Cache path 1 should not be null", cache1)
        assertNotNull("Cache path 2 should not be null", cache2)
        assertNotEquals("Different source paths must produce different cache paths", cache1, cache2)
    }

    @Test
    fun `same path always gets same cache path`() {
        val cache = newCache()
        val path = Path.of("C:/photos/img.jpg")

        val cache1 = cache.getCachePath(path)
        val cache2 = cache.getCachePath(path)

        assertEquals("Same source path must produce same cache path", cache1, cache2)
    }

    @Test
    fun `paths differing only in case get different cache paths`() {
        val cache = newCache()
        val path1 = Path.of("C:/Photos/IMG.jpg")
        val path2 = Path.of("C:/photos/img.jpg")

        val cache1 = cache.getCachePath(path1)
        val cache2 = cache.getCachePath(path2)

        // On case-sensitive systems these are different files
        assertNotNull(cache1)
        assertNotNull(cache2)
        assertNotEquals("Case-different paths must produce different cache paths", cache1, cache2)
    }

    @Test
    fun `cache path for path without filename returns null`() {
        val cache = newCache()
        val root = Path.of("/")
        val result = cache.getCachePath(root)
        // Root path has no fileName, should return null gracefully
        assertNull("Root path should produce null cache path", result)
    }
}

/**
 * Regression tests for evictDirectory iteration fix.
 */
class ThumbnailCacheEvictTest {

    @Test
    fun `evictDirectory does not throw when cache has many entries under directory`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val thumbnailCache = ThumbnailCache(scope)

        // Populate memoryCache via reflection since it's private
        val field = ThumbnailCache::class.java.getDeclaredField("memoryCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(thumbnailCache) as java.util.concurrent.ConcurrentHashMap<Path, javax.swing.Icon>

        val dir = Path.of("/test/evict/dir")
        val icon = javax.swing.ImageIcon()

        // Add entries under the directory
        for (i in 1..20) {
            cache[dir.resolve("file$i.png")] = icon
        }
        // Add entries outside the directory
        cache[Path.of("/other/file.png")] = icon

        // Should not throw ConcurrentModificationException
        thumbnailCache.evictDirectory(dir)

        // Entries under directory should be gone
        for (i in 1..20) {
            assertNull("Entry under evicted dir should be removed", cache[dir.resolve("file$i.png")])
        }
        // Entry outside directory should remain
        assertNotNull("Entry outside evicted dir should remain", cache[Path.of("/other/file.png")])

        // Cleanup
        cache.remove(Path.of("/other/file.png"))
        scope.cancel()
    }
}
