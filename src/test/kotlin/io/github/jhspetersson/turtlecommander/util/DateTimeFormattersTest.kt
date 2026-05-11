package io.github.jhspetersson.turtlecommander.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateTimeFormattersTest {

    /**
     * Without an Application service, [DateTimeFormatters.current] must fall back to the
     * historical default pattern so headless callers (and the file table during unit tests)
     * keep working unchanged.
     */
    @Test
    fun `fallback formatter matches default pattern when settings unavailable`() {
        val millis = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC
        val formatted = DateTimeFormatters.format(millis)
        assertTrue(
            "Formatted timestamp should match yyyy-MM-dd HH:mm, got: $formatted",
            formatted.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")),
        )
    }

    @Test
    fun `repeated calls return cached formatter instance`() {
        val a = DateTimeFormatters.current()
        val b = DateTimeFormatters.current()
        assertEquals(a, b)
    }
}
