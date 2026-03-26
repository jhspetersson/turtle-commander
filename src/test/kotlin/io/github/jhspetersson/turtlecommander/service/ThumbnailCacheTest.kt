package io.github.jhspetersson.turtlecommander.service

import org.junit.Assert.*
import org.junit.Test

class ThumbnailCacheTest {

    @Test
    fun `png is image file`() {
        assertTrue(ThumbnailCache.isImageFile("photo.png"))
    }

    @Test
    fun `jpg is image file`() {
        assertTrue(ThumbnailCache.isImageFile("photo.jpg"))
    }

    @Test
    fun `jpeg is image file`() {
        assertTrue(ThumbnailCache.isImageFile("photo.jpeg"))
    }

    @Test
    fun `gif is image file`() {
        assertTrue(ThumbnailCache.isImageFile("anim.gif"))
    }

    @Test
    fun `bmp is image file`() {
        assertTrue(ThumbnailCache.isImageFile("image.bmp"))
    }

    @Test
    fun `webp is image file`() {
        assertTrue(ThumbnailCache.isImageFile("image.webp"))
    }

    @Test
    fun `ico is image file`() {
        assertTrue(ThumbnailCache.isImageFile("favicon.ico"))
    }

    @Test
    fun `tif is image file`() {
        assertTrue(ThumbnailCache.isImageFile("scan.tif"))
    }

    @Test
    fun `tiff is image file`() {
        assertTrue(ThumbnailCache.isImageFile("scan.tiff"))
    }

    @Test
    fun `uppercase extension is recognized`() {
        assertTrue(ThumbnailCache.isImageFile("PHOTO.PNG"))
    }

    @Test
    fun `mixed case extension is recognized`() {
        assertTrue(ThumbnailCache.isImageFile("photo.Jpg"))
    }

    @Test
    fun `txt is not image file`() {
        assertFalse(ThumbnailCache.isImageFile("readme.txt"))
    }

    @Test
    fun `pdf is not image file`() {
        assertFalse(ThumbnailCache.isImageFile("document.pdf"))
    }

    @Test
    fun `file without extension is not image`() {
        assertFalse(ThumbnailCache.isImageFile("Makefile"))
    }

    @Test
    fun `svg is not image file`() {
        assertFalse(ThumbnailCache.isImageFile("icon.svg"))
    }
}
