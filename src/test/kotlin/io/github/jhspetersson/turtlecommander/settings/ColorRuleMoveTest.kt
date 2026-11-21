package io.github.jhspetersson.turtlecommander.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ColorRuleMoveTest {

    private fun rules(vararg priorities: Int): List<ColorRule> =
        priorities.mapIndexed { i, p -> ColorRule(name = ('a' + i).toString(), priority = p) }

    private fun displayed(rules: List<ColorRule>): List<String> =
        rules.sortedWith(compareByDescending<ColorRule> { it.priority }.thenBy { it.name.lowercase() }).map { it.name }

    @Test
    fun `moving up among tied priorities swaps one position only`() {
        val moved = moveColorRule(rules(5, 5, 5, 5), 3, -1)

        assertEquals(listOf("a", "b", "d", "c"), displayed(moved))
    }

    @Test
    fun `moving down among tied priorities swaps one position only`() {
        val moved = moveColorRule(rules(5, 5, 5, 5), 0, 1)

        assertEquals(listOf("b", "a", "c", "d"), displayed(moved))
    }

    @Test
    fun `moving next to a tie group does not jump over it`() {
        val moved = moveColorRule(rules(5, 5, 5, 3), 3, -1)

        assertEquals(listOf("a", "b", "d", "c"), displayed(moved))
    }

    @Test
    fun `distinct priorities are exchanged and nothing else changes`() {
        val moved = moveColorRule(rules(30, 20, 10), 2, -1)

        assertEquals(listOf("a", "c", "b"), displayed(moved))
        assertEquals(listOf(30, 20, 10), moved.map { it.priority })
    }

    @Test
    fun `moving past either end changes nothing`() {
        val original = rules(3, 2, 1)

        assertSame(original, moveColorRule(original, 0, -1))
        assertSame(original, moveColorRule(original, 2, 1))
    }
}
