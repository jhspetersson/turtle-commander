package io.github.jhspetersson.turtlecommander.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SizeUnitsTest {

    private val kb = 1024L
    private val mb = 1024L * 1024
    private val gb = 1024L * 1024 * 1024

    @Test
    fun `fractional sizes are not truncated`() {
        assertEquals(mb + mb / 2, SizeUnits.parseBytes("1.5", "MB"))
        assertEquals(gb / 2, SizeUnits.parseBytes("0.5", "GB"))
        assertEquals(1536L, SizeUnits.parseBytes("1.5", "KB"))
        assertEquals(1587L, SizeUnits.parseBytes("1.55", "KB"))
    }

    @Test
    fun `whole sizes and bytes parse as before`() {
        assertEquals(500 * kb, SizeUnits.parseBytes("500", "KB"))
        assertEquals(2 * mb, SizeUnits.parseBytes(" 2 ", "MB"))
        assertEquals(7L, SizeUnits.parseBytes("7", "B"))
    }

    @Test
    fun `invalid text yields null`() {
        assertNull(SizeUnits.parseBytes("", "KB"))
        assertNull(SizeUnits.parseBytes("abc", "KB"))
    }

    @Test
    fun `in-between bounds share a single unit that represents both`() {
        val (text1, text2, unit) = SizeUnits.rangeToUnit(500 * kb, 2 * mb)
        assertEquals("KB", unit)
        assertEquals(500 * kb, SizeUnits.parseBytes(text1, unit))
        assertEquals(2 * mb, SizeUnits.parseBytes(text2!!, unit))
    }

    @Test
    fun `sub-kilobyte lower bound with megabyte upper bound round-trips`() {
        val (text1, text2, unit) = SizeUnits.rangeToUnit(700, 3 * mb)
        assertEquals(700L, SizeUnits.parseBytes(text1, unit))
        assertEquals(3 * mb, SizeUnits.parseBytes(text2!!, unit))
    }

    @Test
    fun `single bound round-trips through edit search`() {
        for (bytes in listOf(1536L, 1587L, mb + mb / 2, gb / 2, 42L, 10 * gb)) {
            val (text1, text2, unit) = SizeUnits.rangeToUnit(bytes, null)
            assertNull(text2)
            assertEquals("bytes=$bytes", bytes, SizeUnits.parseBytes(text1, unit))
        }
    }

    @Test
    fun `round sizes are shown in the largest natural unit`() {
        assertEquals(Triple("1.5", null, "MB"), SizeUnits.rangeToUnit(mb + mb / 2, null))
        assertEquals(Triple("2", null, "GB"), SizeUnits.rangeToUnit(2 * gb, null))
        assertEquals(Triple("42", null, "B"), SizeUnits.rangeToUnit(42, null))
    }

    @Test
    fun `suffix parsing accepts fractions and is case-insensitive`() {
        assertEquals(mb + mb / 2, SizeUnits.parseBytesWithSuffix("1.5 MB"))
        assertEquals(5046586573L, SizeUnits.parseBytesWithSuffix("4.7gb"))
        assertEquals(2L * 1024 * gb, SizeUnits.parseBytesWithSuffix("2 TB"))
        assertEquals(123L, SizeUnits.parseBytesWithSuffix("123"))
        assertNull(SizeUnits.parseBytesWithSuffix("lots"))
    }

    @Test
    fun `whole unit split uses the largest evenly dividing combo unit`() {
        assertEquals(3L to "MB", SizeUnits.toWholeUnit(3 * mb))
        assertEquals(1536L to "KB", SizeUnits.toWholeUnit(mb + mb / 2))
        assertEquals(0L to "B", SizeUnits.toWholeUnit(0))
        assertEquals(2048L to "GB", SizeUnits.toWholeUnit(2048 * gb))
    }
}
