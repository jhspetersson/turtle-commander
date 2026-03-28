package io.github.jhspetersson.turtlecommander.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatSizeTest {

    @Test
    fun `zero bytes`() {
        assertEquals("0 B", formatSize(0))
    }

    @Test
    fun `bytes below 1024`() {
        assertEquals("512 B", formatSize(512))
        assertEquals("1023 B", formatSize(1023))
    }

    @Test
    fun `exactly 1 KB`() {
        assertEquals("1.0 KB", formatSize(1024))
    }

    @Test
    fun kilobytes() {
        assertEquals("1.5 KB", formatSize(1536))
        assertEquals("10.0 KB", formatSize(10240))
    }

    @Test
    fun `exactly 1 MB`() {
        assertEquals("1.0 MB", formatSize(1024L * 1024))
    }

    @Test
    fun megabytes() {
        assertEquals("5.5 MB", formatSize((5.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `exactly 1 GB`() {
        assertEquals("1.0 GB", formatSize(1024L * 1024 * 1024))
    }

    @Test
    fun gigabytes() {
        assertEquals("2.5 GB", formatSize((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `exactly 1 TB`() {
        assertEquals("1.0 TB", formatSize(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun terabytes() {
        assertEquals("3.0 TB", formatSize(3L * 1024 * 1024 * 1024 * 1024))
    }
}
