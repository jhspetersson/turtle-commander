package io.github.jhspetersson.turtlecommander.settings

import org.junit.Assert.*
import org.junit.Test
import java.awt.Font

class ColorRulesIOTest {

    // --- round-trip -------------------------------------------------------

    @Test
    fun `export then parse round-trips a single-rule config`() {
        val config = ColorRulesConfig(
            name = "Simple",
            rules = listOf(
                ColorRule(
                    id = "rule-1",
                    name = "Rust",
                    priority = 70,
                    active = true,
                    combinator = Combinator.AND,
                    matchers = listOf(
                        RuleMatcher.Contains(
                            kind = PatternKind.EXACT,
                            pattern = "Cargo.toml",
                            caseSensitive = false,
                        ),
                    ),
                    style = RuleStyle(fontColor = "#AA5511"),
                ),
            ),
        )
        val text = ColorRulesIO.export(config)
        val round = ColorRulesIO.parse(text)!!
        assertEquals("Simple", round.name)
        assertEquals(1, round.rules.size)
        val r = round.rules[0]
        assertEquals("rule-1", r.id)
        assertEquals("Rust", r.name)
        assertEquals(70, r.priority)
        assertTrue(r.active)
        assertEquals(Combinator.AND, r.combinator)
        assertEquals("#AA5511", r.style.fontColor)
        assertEquals(1, r.matchers.size)
        val m = r.matchers[0] as RuleMatcher.Contains
        assertEquals(PatternKind.EXACT, m.kind)
        assertEquals("Cargo.toml", m.pattern)
        assertFalse(m.caseSensitive)
    }

    @Test
    fun `export then parse round-trips all three matcher kinds`() {
        val config = ColorRulesConfig(
            name = "All matchers",
            rules = listOf(
                ColorRule(
                    id = "r",
                    name = "Complex",
                    priority = 5,
                    active = true,
                    combinator = Combinator.OR,
                    matchers = listOf(
                        RuleMatcher.Size(op = SizeOp.BETWEEN, bytes = 1024, bytesMax = 4096),
                        RuleMatcher.Name(
                            kind = PatternKind.GLOB,
                            pattern = "*.kt",
                            caseSensitive = true,
                            appliesTo = AppliesTo.FILE,
                        ),
                        RuleMatcher.Contains(
                            kind = PatternKind.REGEX,
                            pattern = "build\\.(gradle|sbt)",
                            caseSensitive = true,
                        ),
                    ),
                    style = RuleStyle(
                        fontColor = "#112233",
                        backgroundColor = "#445566",
                        iconDotColor = "#778899",
                        fontStyle = Font.BOLD,
                        fontSize = 14,
                    ),
                ),
            ),
        )
        val text = ColorRulesIO.export(config)
        val round = ColorRulesIO.parse(text)!!
        val r = round.rules[0]
        assertEquals(3, r.matchers.size)

        val size = r.matchers[0] as RuleMatcher.Size
        assertEquals(SizeOp.BETWEEN, size.op)
        assertEquals(1024L, size.bytes)
        assertEquals(4096L, size.bytesMax)

        val name = r.matchers[1] as RuleMatcher.Name
        assertEquals(PatternKind.GLOB, name.kind)
        assertEquals("*.kt", name.pattern)
        assertTrue(name.caseSensitive)
        assertEquals(AppliesTo.FILE, name.appliesTo)

        val contains = r.matchers[2] as RuleMatcher.Contains
        assertEquals(PatternKind.REGEX, contains.kind)
        assertEquals("build\\.(gradle|sbt)", contains.pattern)
        assertTrue(contains.caseSensitive)

        assertEquals(Font.BOLD, r.style.fontStyle)
        assertEquals(14, r.style.fontSize)
        assertEquals("#445566", r.style.backgroundColor)
        assertEquals("#778899", r.style.iconDotColor)
    }

    @Test
    fun `export then parse round-trips date and text matchers`() {
        val config = ColorRulesConfig(
            name = "Metadata",
            rules = listOf(
                ColorRule(
                    id = "r",
                    name = "Recent root files",
                    matchers = listOf(
                        RuleMatcher.Date(DateField.MODIFIED, DateOp.BETWEEN, epochMillis = 1000L, epochMillisMax = 5000L),
                        RuleMatcher.Date(DateField.CREATED, DateOp.WITHIN_LAST, amount = 30, unit = DateUnit.DAYS),
                        RuleMatcher.Text(TextProperty.OWNER, PatternKind.EXACT, "root", caseSensitive = true),
                        RuleMatcher.Text(TextProperty.PERMISSIONS, PatternKind.GLOB, "*w*"),
                    ),
                ),
            ),
        )
        val round = ColorRulesIO.parse(ColorRulesIO.export(config))!!
        val m = round.rules[0].matchers
        assertEquals(4, m.size)

        val between = m[0] as RuleMatcher.Date
        assertEquals(DateField.MODIFIED, between.field)
        assertEquals(DateOp.BETWEEN, between.op)
        assertEquals(1000L, between.epochMillis)
        assertEquals(5000L, between.epochMillisMax)

        val within = m[1] as RuleMatcher.Date
        assertEquals(DateField.CREATED, within.field)
        assertEquals(DateOp.WITHIN_LAST, within.op)
        assertEquals(30L, within.amount)
        assertEquals(DateUnit.DAYS, within.unit)

        val owner = m[2] as RuleMatcher.Text
        assertEquals(TextProperty.OWNER, owner.field)
        assertEquals(PatternKind.EXACT, owner.kind)
        assertEquals("root", owner.pattern)
        assertTrue(owner.caseSensitive)

        val perms = m[3] as RuleMatcher.Text
        assertEquals(TextProperty.PERMISSIONS, perms.field)
        assertEquals(PatternKind.GLOB, perms.kind)
        assertEquals("*w*", perms.pattern)
        assertFalse(perms.caseSensitive)
    }

    @Test
    fun `export then parse round-trips multiple rules with preserved order`() {
        val config = ColorRulesConfig(
            name = "Ordered",
            rules = listOf(
                ColorRule(id = "a", name = "Alpha", priority = 1,
                    matchers = listOf(RuleMatcher.Name(PatternKind.EXACT, "a"))),
                ColorRule(id = "b", name = "Bravo", priority = 2,
                    matchers = listOf(RuleMatcher.Name(PatternKind.EXACT, "b"))),
                ColorRule(id = "c", name = "Charlie", priority = 3,
                    matchers = listOf(RuleMatcher.Name(PatternKind.EXACT, "c"))),
            ),
        )
        val text = ColorRulesIO.export(config)
        val round = ColorRulesIO.parse(text)!!
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), round.rules.map { it.name })
        assertEquals(listOf("a", "b", "c"), round.rules.map { it.id })
    }

    @Test
    fun `inactive rule survives round-trip`() {
        val config = ColorRulesConfig(
            name = "Inactive test",
            rules = listOf(
                ColorRule(
                    id = "x", name = "Off", active = false,
                    matchers = listOf(RuleMatcher.Size(SizeOp.GT, 100)),
                ),
            ),
        )
        val text = ColorRulesIO.export(config)
        assertTrue(text.contains("rule.0.active=false"))
        val round = ColorRulesIO.parse(text)!!
        assertFalse(round.rules[0].active)
    }

    @Test
    fun `default active rule omits active=true line on export`() {
        val config = ColorRulesConfig(
            name = "Default active",
            rules = listOf(
                ColorRule(id = "x", name = "On",
                    matchers = listOf(RuleMatcher.Size(SizeOp.GT, 0))),
            ),
        )
        val text = ColorRulesIO.export(config)
        assertFalse(text.contains("rule.0.active="))
    }

    @Test
    fun `export omits empty style fields`() {
        val config = ColorRulesConfig(
            name = "Sparse",
            rules = listOf(
                ColorRule(id = "x", name = "R",
                    matchers = listOf(RuleMatcher.Size(SizeOp.GT, 1)),
                    style = RuleStyle(fontColor = "#FF0000")),
            ),
        )
        val text = ColorRulesIO.export(config)
        assertTrue(text.contains("rule.0.fontColor=#FF0000"))
        assertFalse(text.contains("rule.0.backgroundColor"))
        assertFalse(text.contains("rule.0.iconDotColor"))
        assertFalse(text.contains("rule.0.fontStyle"))
        assertFalse(text.contains("rule.0.fontSize"))
    }

    @Test
    fun `size matcher omits bytesMax when zero`() {
        val config = ColorRulesConfig(
            name = "No max",
            rules = listOf(
                ColorRule(id = "x", name = "R",
                    matchers = listOf(RuleMatcher.Size(SizeOp.GT, 100))),
            ),
        )
        val text = ColorRulesIO.export(config)
        assertTrue(text.contains("rule.0.matcher.0.sizeBytes=100"))
        assertFalse(text.contains("sizeBytesMax"))
    }

    @Test
    fun `name matcher omits appliesTo when BOTH`() {
        val config = ColorRulesConfig(
            name = "Both scope",
            rules = listOf(
                ColorRule(id = "x", name = "R",
                    matchers = listOf(RuleMatcher.Name(PatternKind.EXACT, "x"))),
            ),
        )
        val text = ColorRulesIO.export(config)
        assertFalse(text.contains("appliesTo"))
    }

    // --- parse malformed input --------------------------------------------

    @Test
    fun `parse returns null for empty text`() {
        assertNull(ColorRulesIO.parse(""))
    }

    @Test
    fun `parse returns null when name is missing`() {
        assertNull(ColorRulesIO.parse("rule.0.name=Foo"))
    }

    @Test
    fun `parse returns null when name is blank`() {
        assertNull(ColorRulesIO.parse("name=   "))
    }

    @Test
    fun `parse ignores comment and blank lines`() {
        val text = """
            # This is a config I made
            name=Commented

            rule.0.name=Alpha
            rule.0.matcher.0.type=NAME
            rule.0.matcher.0.patternKind=EXACT
            rule.0.matcher.0.pattern=a
        """.trimIndent()
        val config = ColorRulesIO.parse(text)!!
        assertEquals("Commented", config.name)
        assertEquals(1, config.rules.size)
    }

    @Test
    fun `parse skips lines without equals sign`() {
        val text = "name=OK\nthis-is-a-bad-line\nrule.0.name=Alpha\nrule.0.matcher.0.type=NAME\nrule.0.matcher.0.patternKind=EXACT\nrule.0.matcher.0.pattern=a"
        val config = ColorRulesIO.parse(text)!!
        assertEquals(1, config.rules.size)
    }

    @Test
    fun `parse trims whitespace around keys and values`() {
        val text = "  name  =  My Config  \n  rule.0.name  =  Alpha  \n  rule.0.matcher.0.type  =  NAME  \n  rule.0.matcher.0.patternKind  =  EXACT  \n  rule.0.matcher.0.pattern  =  foo  "
        val config = ColorRulesIO.parse(text)!!
        assertEquals("My Config", config.name)
        assertEquals("Alpha", config.rules[0].name)
        assertEquals("foo", (config.rules[0].matchers[0] as RuleMatcher.Name).pattern)
    }

    @Test
    fun `rule with missing name is dropped but config still parses`() {
        val text = """
            name=Partial
            rule.0.priority=5
            rule.0.matcher.0.type=SIZE
            rule.0.matcher.0.sizeOp=GT
            rule.0.matcher.0.sizeBytes=100
            rule.1.name=Alpha
            rule.1.matcher.0.type=SIZE
            rule.1.matcher.0.sizeOp=GT
            rule.1.matcher.0.sizeBytes=10
        """.trimIndent()
        val config = ColorRulesIO.parse(text)!!
        assertEquals(1, config.rules.size)
        assertEquals("Alpha", config.rules[0].name)
    }

    @Test
    fun `matcher with invalid type is silently dropped`() {
        val text = """
            name=X
            rule.0.name=Has bad matcher
            rule.0.matcher.0.type=BOGUS
            rule.0.matcher.0.pattern=foo
            rule.0.matcher.1.type=NAME
            rule.0.matcher.1.patternKind=EXACT
            rule.0.matcher.1.pattern=real
        """.trimIndent()
        val config = ColorRulesIO.parse(text)!!
        assertEquals(1, config.rules.size)
        assertEquals(1, config.rules[0].matchers.size)
        assertEquals("real", (config.rules[0].matchers[0] as RuleMatcher.Name).pattern)
    }

    @Test
    fun `matcher with invalid enum value is dropped`() {
        val text = """
            name=X
            rule.0.name=Bad enum
            rule.0.matcher.0.type=NAME
            rule.0.matcher.0.patternKind=NOT_A_KIND
            rule.0.matcher.0.pattern=foo
            rule.0.matcher.1.type=NAME
            rule.0.matcher.1.patternKind=GLOB
            rule.0.matcher.1.pattern=*.ok
        """.trimIndent()
        val config = ColorRulesIO.parse(text)!!
        assertEquals(1, config.rules[0].matchers.size)
        assertEquals(PatternKind.GLOB, (config.rules[0].matchers[0] as RuleMatcher.Name).kind)
    }

    @Test
    fun `combinator falls back to AND when invalid`() {
        val text = """
            name=X
            rule.0.name=Bad combinator
            rule.0.combinator=NOT_VALID
            rule.0.matcher.0.type=NAME
            rule.0.matcher.0.patternKind=EXACT
            rule.0.matcher.0.pattern=x
        """.trimIndent()
        val config = ColorRulesIO.parse(text)!!
        assertEquals(Combinator.AND, config.rules[0].combinator)
    }

    @Test
    fun `missing id is replaced with a fresh UUID`() {
        val text = """
            name=X
            rule.0.name=NoId
            rule.0.matcher.0.type=NAME
            rule.0.matcher.0.patternKind=EXACT
            rule.0.matcher.0.pattern=x
        """.trimIndent()
        val config = ColorRulesIO.parse(text)!!
        assertTrue(config.rules[0].id.isNotEmpty())
    }

    @Test
    fun `size matcher with non-numeric bytes is dropped`() {
        val text = """
            name=X
            rule.0.name=Rule
            rule.0.matcher.0.type=SIZE
            rule.0.matcher.0.sizeOp=GT
            rule.0.matcher.0.sizeBytes=not-a-number
        """.trimIndent()
        val config = ColorRulesIO.parse(text)!!
        assertEquals(0, config.rules[0].matchers.size)
    }

    @Test
    fun `sparse rule indices are supported`() {
        val text = """
            name=Gaps
            rule.5.name=Five
            rule.5.matcher.0.type=NAME
            rule.5.matcher.0.patternKind=EXACT
            rule.5.matcher.0.pattern=five
            rule.2.name=Two
            rule.2.matcher.0.type=NAME
            rule.2.matcher.0.patternKind=EXACT
            rule.2.matcher.0.pattern=two
        """.trimIndent()
        val config = ColorRulesIO.parse(text)!!
        assertEquals(listOf("Two", "Five"), config.rules.map { it.name })
    }
}
