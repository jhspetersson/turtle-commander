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

    private fun matches(name: String, mask: String) = matchesMask(name, parseSelectionMaskPatterns(mask))

    @Test
    fun `names that are not valid local paths still match`() {
        assertTrue(matches("a:b.txt", "*.txt"))
        assertTrue(matches("what?.txt", "*.txt"))
        assertTrue(matches("pipe|name.md", "*.txt,*.md"))
        assertFalse(matches("a<b>.log", "*.txt"))
    }

    @Test
    fun `glob features match plain names`() {
        assertTrue(matches("report-7.pdf", "report-[0-9].pdf"))
        assertFalse(matches("report-x.pdf", "report-[0-9].pdf"))
        assertTrue(matches("report-x.pdf", "report-[!0-9].pdf"))
        assertTrue(matches("photo.jpg", "*.{jpg}"))
        assertTrue(matches("a.c", "a?c"))
        assertFalse(matches("ac", "a?c"))
        assertTrue(matches("x.txt", "**.txt"))
        assertTrue(matches("*.txt", "\\*.txt"))
        assertFalse(matches("a.txt", "\\*.txt"))
        assertTrue(matches("a+b(1).txt", "a+b(1).txt"))
    }

    @Test
    fun `case folding follows the platform`() {
        val windows = System.getProperty("os.name").startsWith("Windows")
        assertEquals(windows, matches("README.TXT", "*.txt"))
    }
}
