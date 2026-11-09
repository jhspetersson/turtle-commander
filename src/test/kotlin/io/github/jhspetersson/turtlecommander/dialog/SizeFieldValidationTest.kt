package io.github.jhspetersson.turtlecommander.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SizeFieldValidationTest {

    @Test
    fun `plain and fractional numbers are accepted`() {
        assertNull(sizeFieldError("10", "KB"))
        assertNull(sizeFieldError(" 1.5 ", "MB"))
        assertNull(sizeFieldError("0", "B"))
    }

    @Test
    fun `NaN and infinity are rejected`() {
        assertEquals("Size must be a number", sizeFieldError("NaN", "KB"))
        assertEquals("Size must be a number", sizeFieldError("Infinity", "KB"))
        assertEquals("Size must be a number", sizeFieldError("-Infinity", "KB"))
    }

    @Test
    fun `values that overflow a long are rejected`() {
        assertEquals("Size is too large", sizeFieldError("1e400", "B"))
        assertEquals("Size is too large", sizeFieldError("9223372036854775807", "GB"))
    }

    @Test
    fun `empty, text and negative input are rejected`() {
        assertEquals("Enter a size", sizeFieldError("   ", "KB"))
        assertNotNull(sizeFieldError("abc", "KB"))
        assertEquals("Size cannot be negative", sizeFieldError("-1", "KB"))
    }
}
