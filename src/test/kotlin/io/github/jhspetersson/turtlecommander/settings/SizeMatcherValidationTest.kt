package io.github.jhspetersson.turtlecommander.settings

import org.junit.Assert.*
import org.junit.Test

class SizeMatcherValidationTest {

    @Test
    fun `between with upper bound below lower bound is rejected`() {
        assertNotNull(sizeRangeError(SizeOp.BETWEEN, bytes = 2048, bytesMax = 1024))
    }

    @Test
    fun `between with equal bounds is accepted`() {
        assertNull(sizeRangeError(SizeOp.BETWEEN, bytes = 1024, bytesMax = 1024))
    }

    @Test
    fun `other operators ignore the upper bound`() {
        assertNull(sizeRangeError(SizeOp.GT, bytes = 2048, bytesMax = 0))
    }

    @Test
    fun `overflowing size is rejected`() {
        assertNull(sizeMatcherBytes(Long.MAX_VALUE, "GB"))
        assertNotNull(sizeRangeError(SizeOp.LT, bytes = null, bytesMax = 0))
        assertNotNull(sizeRangeError(SizeOp.BETWEEN, bytes = 1, bytesMax = null))
    }

    @Test
    fun `size in units multiplies without overflow`() {
        assertEquals(3L * 1024 * 1024, sizeMatcherBytes(3, "MB"))
    }
}
