package io.github.jhspetersson.turtlecommander.settings

import org.junit.Assert.*
import org.junit.Test

class ColorRuleOrderingTest {

    private val rule = ColorRule(
        name = "r",
        matchers = listOf(
            RuleMatcher.Contains(PatternKind.EXACT, "x"),
            RuleMatcher.Name(PatternKind.EXACT, "a"),
            RuleMatcher.Size(SizeOp.GT, 1),
        ),
    )

    @Test
    fun `matchers are ordered cheapest first`() {
        assertEquals(listOf(0, 1, 2), rule.orderedMatchers.map { it.cost })
    }

    @Test
    fun `ordering is computed once per rule`() {
        assertSame(rule.orderedMatchers, rule.orderedMatchers)
    }

    @Test
    fun `single matcher keeps the original list`() {
        val single = ColorRule(name = "s", matchers = listOf(RuleMatcher.Size(SizeOp.GT, 1)))
        assertSame(single.matchers, single.orderedMatchers)
    }
}
