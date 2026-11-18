package io.github.jhspetersson.turtlecommander.vfs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class ZipNameCharsetTest {

    private val allHighBytes = ByteArray(128) { (it + 128).toByte() }

    @Test
    fun `western and unknown hosts use the code page the zip format specifies`() {
        assertEquals(Charset.forName("IBM437"), ZipNameCharset.fallback("Cp1252"))
        assertEquals(Charset.forName("IBM437"), ZipNameCharset.fallback("UTF-8"))
        assertEquals(Charset.forName("IBM437"), ZipNameCharset.fallback(null))
        assertEquals(Charset.forName("IBM437"), ZipNameCharset.fallback("no-such-charset"))
    }

    @Test
    fun `a host with an ansi code page uses the matching oem code page`() {
        assertEquals(Charset.forName("IBM866"), ZipNameCharset.fallback("Cp1251"))
        assertEquals(Charset.forName("IBM852"), ZipNameCharset.fallback("Cp1250"))
        assertEquals(Charset.forName("IBM775"), ZipNameCharset.fallback("windows-1257"))
    }

    @Test
    fun `a host whose ansi and oem code pages are the same keeps it`() {
        assertEquals(Charset.forName("windows-31j"), ZipNameCharset.fallback("MS932"))
        assertEquals(Charset.forName("GBK"), ZipNameCharset.fallback("GBK"))
    }

    @Test
    fun `a cyrillic dos name is read as written`() {
        val raw = byteArrayOf(0x8F.toByte(), 0xE0.toByte(), 0xA8.toByte(), 0xA2.toByte(), 0xA5.toByte(), 0xE2.toByte())

        assertEquals("Привет", String(raw, ZipNameCharset.fallback("Cp1251")))
    }

    @Test
    fun `every byte survives decoding and encoding with the single-byte fallbacks`() {
        for (native in listOf("Cp1250", "Cp1251", "Cp1252", "Cp1253", "Cp1254", "Cp1255", "Cp1257")) {
            val charset = ZipNameCharset.fallback(native)

            assertArrayEquals(native, allHighBytes, String(allHighBytes, charset).toByteArray(charset))
        }
    }
}
