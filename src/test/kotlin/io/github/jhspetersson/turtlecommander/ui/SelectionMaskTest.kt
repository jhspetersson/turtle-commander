package io.github.jhspetersson.turtlecommander.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionMaskTest {

    @Test
    fun `valid masks produce one matcher per token`() {
        assertEquals(1, parseSelectionMaskPatterns("*.txt").size)
        assertEquals(2, parseSelectionMaskPatterns("*.txt,*.md").size)
        assertEquals(2, parseSelectionMaskPatterns("*.txt; *.md").size)
    }

    @Test
    fun `invalid glob token does not throw`() {
        assertEquals(0, parseSelectionMaskPatterns("[").size)
        assertEquals(0, parseSelectionMaskPatterns("{a").size)
    }

    @Test
    fun `invalid token is dropped and valid tokens survive`() {
        assertEquals(1, parseSelectionMaskPatterns("*.txt,[").size)
    }

    @Test
    fun `mask validity check accepts only fully valid masks`() {
        assertTrue(isValidSelectionMask("*.txt"))
        assertTrue(isValidSelectionMask("*.txt, *.md"))
        assertTrue(isValidSelectionMask("report-[0-9].pdf"))
        assertFalse(isValidSelectionMask("["))
        assertFalse(isValidSelectionMask("*.txt,["))
        assertFalse(isValidSelectionMask(""))
        assertFalse(isValidSelectionMask("  ,  "))
    }
}
