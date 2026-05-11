package io.github.jhspetersson.turtlecommander.util

import io.github.jhspetersson.turtlecommander.settings.FileSizeFormat
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
    fun `exactly 1 KiB`() {
        assertEquals("1.0 KiB", formatSize(1024))
    }

    @Test
    fun kibibytes() {
        assertEquals("1.5 KiB", formatSize(1536))
        assertEquals("10.0 KiB", formatSize(10240))
    }

    @Test
    fun `exactly 1 MiB`() {
        assertEquals("1.0 MiB", formatSize(1024L * 1024))
    }

    @Test
    fun mebibytes() {
        assertEquals("5.5 MiB", formatSize((5.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `exactly 1 GiB`() {
        assertEquals("1.0 GiB", formatSize(1024L * 1024 * 1024))
    }

    @Test
    fun gibibytes() {
        assertEquals("2.5 GiB", formatSize((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `exactly 1 TiB`() {
        assertEquals("1.0 TiB", formatSize(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun tebibytes() {
        assertEquals("3.0 TiB", formatSize(3L * 1024 * 1024 * 1024 * 1024))
    }

    @Test
    fun `SI labels use decimal base`() {
        assertEquals("999 B", formatSize(999, FileSizeFormat.AUTO_SI))
        assertEquals("1.0 kB", formatSize(1000, FileSizeFormat.AUTO_SI))
        assertEquals("1.5 MB", formatSize(1_500_000, FileSizeFormat.AUTO_SI))
        assertEquals("2.0 GB", formatSize(2_000_000_000, FileSizeFormat.AUTO_SI))
    }

    @Test
    fun `bytes mode uses comma grouping with no suffix`() {
        assertEquals("0", formatSize(0, FileSizeFormat.BYTES))
        assertEquals("512", formatSize(512, FileSizeFormat.BYTES))
        assertEquals("1,536", formatSize(1536, FileSizeFormat.BYTES))
        assertEquals("1,234,567", formatSize(1_234_567, FileSizeFormat.BYTES))
    }

    @Test
    fun `auto binary explicit format matches default`() {
        assertEquals(formatSize(1024), formatSize(1024, FileSizeFormat.AUTO_BINARY))
        assertEquals(formatSize(1_500_000), formatSize(1_500_000, FileSizeFormat.AUTO_BINARY))
    }
}
