package io.github.jhspetersson.turtlecommander.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar

class ParseDateRangeTest {

    private fun parse(text: String) = FileSearchDialog.parseDateRange(text)

    private fun millis(pattern: String, text: String): Long {
        val sdf = SimpleDateFormat(pattern)
        sdf.isLenient = false
        return sdf.parse(text).time
    }

    @Test
    fun `blank text yields null`() {
        assertNull(parse(""))
        assertNull(parse("   "))
    }

    @Test
    fun `full timestamp spans one minute inclusive`() {
        val (start, end) = parse("2020-06-15 14:30")!!
        assertEquals(millis("yyyy-MM-dd HH:mm", "2020-06-15 14:30"), start)
        assertEquals(start + 60_000 - 1, end)
    }

    @Test
    fun `date spans the whole day`() {
        val (start, end) = parse("2020-06-15")!!
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        // End is one millisecond before the next day's midnight.
        assertEquals(start + 24L * 60 * 60 * 1000 - 1, end)
    }

    @Test
    fun `year-month and year forms parse`() {
        assertNotNull(parse("2020-06"))
        assertNotNull(parse("2020"))
    }

    @Test
    fun `end is always after start`() {
        for (t in listOf("2020", "2020-06", "2020-06-15", "2020-06-15 14:30")) {
            val (start, end) = parse(t)!!
            assertTrue("range for $t should be non-empty", end > start)
        }
    }

    @Test
    fun `out-of-range fields are rejected rather than silently accepted`() {
        assertNull(parse("2020-13"))       // month 13
        assertNull(parse("2020-13-45"))    // would previously parse just the 2020 prefix
        assertNull(parse("2020-06-31"))    // June has 30 days
        assertNull(parse("2020-06-15 25:00")) // hour 25
    }

    @Test
    fun `garbage and partial input is rejected`() {
        assertNull(parse("not a date"))
        assertNull(parse("2020-"))
        assertNull(parse("2020-06-15 14"))  // incomplete time
        assertNull(parse("15/06/2020"))     // wrong separator/order
    }
}
