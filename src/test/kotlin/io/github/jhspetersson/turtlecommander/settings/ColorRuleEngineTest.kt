package io.github.jhspetersson.turtlecommander.settings

import io.github.jhspetersson.turtlecommander.model.FileEntry
import org.junit.Assert.*
import org.junit.Test
import java.awt.Font
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

class ColorRuleEngineTest {

    private val dummyPath = Paths.get("dummy")

    private fun file(name: String, size: Long = 0, isDir: Boolean = false) = FileEntry(
        name = name,
        path = dummyPath,
        isDirectory = isDir,
        size = size,
        lastModified = null,
        permissions = "",
    )

    private fun rule(
        name: String,
        priority: Int = 0,
        active: Boolean = true,
        combinator: Combinator = Combinator.AND,
        matchers: List<RuleMatcher>,
        style: RuleStyle = RuleStyle(fontColor = "#111111"),
    ) = ColorRule(
        id = name,
        name = name,
        priority = priority,
        active = active,
        combinator = combinator,
        matchers = matchers,
        style = style,
    )

    // --- empty / fallthrough ---

    @Test
    fun `empty ruleset returns EMPTY`() {
        val r = ColorRuleEngine.resolve(emptyList(), file("f"))
        assertTrue(r.isEmpty())
    }

    @Test
    fun `no matching rules returns EMPTY`() {
        val rules = listOf(rule("only", matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*.kt"))))
        val r = ColorRuleEngine.resolve(rules, file("picture.png"))
        assertTrue(r.isEmpty())
    }

    @Test
    fun `matchers empty never produces a match`() {
        val rules = listOf(rule("bad", matchers = emptyList(), style = RuleStyle(fontColor = "#F00")))
        val r = ColorRuleEngine.resolve(rules, file("anything"))
        assertTrue(r.isEmpty())
    }

    // --- active flag ---

    @Test
    fun `inactive rules are ignored`() {
        val rules = listOf(
            rule("off", active = false, matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")), style = RuleStyle(fontColor = "#AAA")),
        )
        val r = ColorRuleEngine.resolve(rules, file("f"))
        assertTrue(r.isEmpty())
    }

    // --- priority / winner mode ---

    @Test
    fun `winner mode picks highest priority match`() {
        val rules = listOf(
            rule("low", priority = 1, matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")), style = RuleStyle(fontColor = "#111")),
            rule("high", priority = 10, matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")), style = RuleStyle(fontColor = "#EEE")),
        )
        val r = ColorRuleEngine.resolve(rules, file("f"), mode = ColorizationMode.WINNER)
        assertEquals("#EEE", r.fontColor)
    }

    @Test
    fun `winner mode tiebreaks by name case-insensitive`() {
        val rules = listOf(
            rule("bravo", priority = 5, matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")), style = RuleStyle(fontColor = "#B")),
            rule("Alpha", priority = 5, matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")), style = RuleStyle(fontColor = "#A")),
        )
        val r = ColorRuleEngine.resolve(rules, file("f"), mode = ColorizationMode.WINNER)
        // bravo > alpha alphabetically, and thenBy picks the "max" => bravo wins
        assertEquals("#B", r.fontColor)
    }

    @Test
    fun `winner mode inactive rule does not beat active lower priority`() {
        val rules = listOf(
            rule("low-active", priority = 1, matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")), style = RuleStyle(fontColor = "#ACT")),
            rule("high-inactive", priority = 100, active = false, matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")), style = RuleStyle(fontColor = "#NO")),
        )
        val r = ColorRuleEngine.resolve(rules, file("f"))
        assertEquals("#ACT", r.fontColor)
    }

    // --- layered mode ---

    @Test
    fun `layered mode overlays fields from lower to higher priority`() {
        val rules = listOf(
            rule(
                "low", priority = 1,
                matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")),
                style = RuleStyle(fontColor = "#111", backgroundColor = "#AAA"),
            ),
            rule(
                "high", priority = 10,
                matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")),
                // only overrides font color — leaves background untouched
                style = RuleStyle(fontColor = "#EEE"),
            ),
        )
        val r = ColorRuleEngine.resolve(rules, file("f"), mode = ColorizationMode.LAYERED)
        assertEquals("#EEE", r.fontColor)
        assertEquals("#AAA", r.backgroundColor)
    }

    @Test
    fun `layered mode different rules fill different fields`() {
        val rules = listOf(
            rule(
                "color", priority = 5,
                matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")),
                style = RuleStyle(fontColor = "#F00"),
            ),
            rule(
                "dot", priority = 6,
                matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")),
                style = RuleStyle(iconDotColor = "#0F0"),
            ),
            rule(
                "size", priority = 7,
                matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")),
                style = RuleStyle(fontSize = 18),
            ),
        )
        val r = ColorRuleEngine.resolve(rules, file("f"), mode = ColorizationMode.LAYERED)
        assertEquals("#F00", r.fontColor)
        assertEquals("#0F0", r.iconDotColor)
        assertEquals(18, r.fontSize)
    }

    @Test
    fun `layered mode fontStyle null does not clobber earlier set value`() {
        val rules = listOf(
            rule(
                "bold", priority = 1,
                matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")),
                style = RuleStyle(fontStyle = Font.BOLD),
            ),
            rule(
                "nostyle", priority = 2,
                matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*")),
                style = RuleStyle(fontColor = "#ABC"), // fontStyle left null
            ),
        )
        val r = ColorRuleEngine.resolve(rules, file("f"), mode = ColorizationMode.LAYERED)
        assertEquals(Font.BOLD, r.fontStyle)
        assertEquals("#ABC", r.fontColor)
    }

    // --- combinator ---

    @Test
    fun `AND combinator requires all matchers`() {
        val r = rule(
            "and",
            combinator = Combinator.AND,
            matchers = listOf(
                RuleMatcher.Name(PatternKind.GLOB, "*.log"),
                RuleMatcher.Size(SizeOp.GT, 1000),
            ),
        )
        // name matches but size does not
        val tooSmall = ColorRuleEngine.resolve(listOf(r), file("a.log", size = 10))
        assertTrue(tooSmall.isEmpty())
        // both match
        val both = ColorRuleEngine.resolve(listOf(r), file("a.log", size = 5000))
        assertEquals("#111111", both.fontColor)
    }

    @Test
    fun `OR combinator needs one matcher`() {
        val r = rule(
            "or",
            combinator = Combinator.OR,
            matchers = listOf(
                RuleMatcher.Name(PatternKind.GLOB, "*.log"),
                RuleMatcher.Size(SizeOp.GT, 1000),
            ),
        )
        val onlyName = ColorRuleEngine.resolve(listOf(r), file("a.log", size = 1))
        val onlySize = ColorRuleEngine.resolve(listOf(r), file("a.png", size = 9999))
        val neither = ColorRuleEngine.resolve(listOf(r), file("a.png", size = 1))
        assertFalse(onlyName.isEmpty())
        assertFalse(onlySize.isEmpty())
        assertTrue(neither.isEmpty())
    }

    // --- short-circuit / cost ordering ---

    @Test
    fun `OR short-circuits before invoking Contains matcher when cheap matcher passes`() {
        val containsCalls = AtomicInteger()
        val evaluator = ContainsEvaluator {
            containsCalls.incrementAndGet()
            listOf("hit")
        }
        val r = rule(
            "or",
            combinator = Combinator.OR,
            matchers = listOf(
                // cheap Name matcher passes first (cost 1); Contains (cost 2) must be skipped
                RuleMatcher.Name(PatternKind.GLOB, "*", appliesTo = AppliesTo.BOTH),
                RuleMatcher.Contains(PatternKind.EXACT, "hit"),
            ),
        )
        val res = ColorRuleEngine.resolve(listOf(r), file("proj", isDir = true), contains = evaluator)
        assertFalse(res.isEmpty())
        assertEquals("Contains matcher should not have been invoked", 0, containsCalls.get())
    }

    @Test
    fun `AND short-circuits before invoking Contains matcher when cheap matcher fails`() {
        val containsCalls = AtomicInteger()
        val evaluator = ContainsEvaluator {
            containsCalls.incrementAndGet()
            listOf("hit")
        }
        val r = rule(
            "and",
            combinator = Combinator.AND,
            matchers = listOf(
                // Name matcher fails first → engine abandons rule before calling Contains
                RuleMatcher.Name(PatternKind.GLOB, "nope", appliesTo = AppliesTo.BOTH),
                RuleMatcher.Contains(PatternKind.EXACT, "hit"),
            ),
        )
        val res = ColorRuleEngine.resolve(listOf(r), file("proj", isDir = true), contains = evaluator)
        assertTrue(res.isEmpty())
        assertEquals("Contains matcher should not have been invoked", 0, containsCalls.get())
    }

    // --- contains evaluator wiring ---

    @Test
    fun `engine threads contains evaluator into Contains matcher`() {
        val r = rule(
            "git",
            matchers = listOf(RuleMatcher.Contains(PatternKind.EXACT, ".git")),
            style = RuleStyle(fontColor = "#G"),
        )
        val dir = file("repo", isDir = true)
        val withDotGit = ContainsEvaluator { listOf("src", ".git", "README") }
        val withoutDotGit = ContainsEvaluator { listOf("src", "README") }

        assertEquals("#G", ColorRuleEngine.resolve(listOf(r), dir, contains = withDotGit).fontColor)
        assertTrue(ColorRuleEngine.resolve(listOf(r), dir, contains = withoutDotGit).isEmpty())
    }

    // --- serialization round-trip ---

    @Test
    fun `SavedColorRule round-trips through domain model`() {
        val original = rule(
            "round",
            priority = 42,
            active = true,
            combinator = Combinator.OR,
            matchers = listOf(
                RuleMatcher.Size(SizeOp.BETWEEN, 100, 500),
                RuleMatcher.Name(PatternKind.REGEX, "^foo\\..*$", caseSensitive = true, appliesTo = AppliesTo.FILE),
                RuleMatcher.Contains(PatternKind.GLOB, "*.csproj"),
            ),
            style = RuleStyle(
                fontColor = "#F00",
                backgroundColor = "#111",
                iconDotColor = "#222",
                fontStyle = Font.BOLD,
                fontSize = 14,
            ),
        )
        val restored = SavedColorRule.fromRule(original).toRule()
        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.priority, restored.priority)
        assertEquals(original.active, restored.active)
        assertEquals(original.combinator, restored.combinator)
        assertEquals(original.matchers, restored.matchers)
        assertEquals(original.style, restored.style)
    }

    @Test
    fun `SavedColorRule defaults to AND when combinator string unparseable`() {
        val saved = SavedColorRule().apply {
            name = "x"; combinator = "NOT_A_REAL_VALUE"
        }
        assertEquals(Combinator.AND, saved.toRule().combinator)
    }

    @Test
    fun `SavedColorRule assigns fresh id if blank`() {
        val saved = SavedColorRule().apply { name = "x" }
        val restored = saved.toRule()
        assertFalse(restored.id.isBlank())
    }

    // --- defaults ---

    @Test
    fun `builtinRules contains expected slugs`() {
        val slugs = ColorRuleDefaults.builtinRules().map { it.id }.toSet()
        assertTrue(slugs.contains("default:git"))
        assertTrue(slugs.contains("default:gradle"))
        assertTrue(slugs.contains("default:idea-project"))
        assertTrue(slugs.contains("default:dotnet"))
        assertTrue(slugs.contains("default:symlink-valid"))
        assertTrue(slugs.contains("default:symlink-broken"))
        assertEquals(11, ColorRuleDefaults.builtinRules().size)
    }

    @Test
    fun `isDefault recognizes seeded rules`() {
        for (r in ColorRuleDefaults.builtinRules()) {
            assertTrue(ColorRuleDefaults.isDefault(r))
        }
    }

    @Test
    fun `freshBuiltinRules assigns non-default ids`() {
        for (r in ColorRuleDefaults.freshBuiltinRules()) {
            assertFalse("fresh should not keep default: id", ColorRuleDefaults.isDefault(r))
        }
    }

    @Test
    fun `builtin idea-project rule resolves against synthetic dir`() {
        val idea = ColorRuleDefaults.builtinRules().first { it.id == "default:idea-project" }
        val dir = file("proj", isDir = true)
        val hasIdea = ContainsEvaluator { listOf(".idea", "src") }
        val r = ColorRuleEngine.resolve(listOf(idea), dir, contains = hasIdea)
        assertEquals("#5A9AD8", r.fontColor)
        assertEquals("#5A9AD8", r.iconDotColor)
    }

    @Test
    fun `builtin dotnet rule matches csproj glob`() {
        val dotnet = ColorRuleDefaults.builtinRules().first { it.id == "default:dotnet" }
        val dir = file("proj", isDir = true)
        val withProj = ContainsEvaluator { listOf("App.csproj", "Program.cs") }
        val noProj = ContainsEvaluator { listOf("foo.txt") }
        assertFalse(ColorRuleEngine.resolve(listOf(dotnet), dir, contains = withProj).isEmpty())
        assertTrue(ColorRuleEngine.resolve(listOf(dotnet), dir, contains = noProj).isEmpty())
    }
}
