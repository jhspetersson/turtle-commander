package io.github.jhspetersson.turtlecommander.settings

import org.junit.Assert.*
import org.junit.Test

class ColorizationMigrationTest {

    @Test
    fun `fresh state seeds defaults active when highlighting was on`() {
        val state = TurtleCommanderSettings.State().apply {
            enableFileNameHighlighting = true
        }
        ColorRuleManager.ensureInitialRules(state)
        assertTrue(state.colorRulesInitialized)
        assertEquals(9, state.colorRules.size)
        assertTrue(state.colorRules.all { it.active })
    }

    @Test
    fun `fresh state seeds defaults inactive when highlighting was off`() {
        val state = TurtleCommanderSettings.State().apply {
            enableFileNameHighlighting = false
        }
        ColorRuleManager.ensureInitialRules(state)
        assertTrue(state.colorRulesInitialized)
        assertEquals(9, state.colorRules.size)
        assertTrue(state.colorRules.none { it.active })
    }

    @Test
    fun `seeding is idempotent after first run`() {
        val state = TurtleCommanderSettings.State()
        ColorRuleManager.ensureInitialRules(state)
        val firstRun = state.colorRules.size
        ColorRuleManager.ensureInitialRules(state)
        ColorRuleManager.ensureInitialRules(state)
        assertEquals(firstRun, state.colorRules.size)
    }

    @Test
    fun `seeder does not re-run after user deletes all rules`() {
        val state = TurtleCommanderSettings.State()
        ColorRuleManager.ensureInitialRules(state)
        state.colorRules.clear()
        ColorRuleManager.ensureInitialRules(state)
        assertTrue("seeder must respect colorRulesInitialized flag", state.colorRules.isEmpty())
    }

    @Test
    fun `user modifications survive subsequent ensureInitialRules calls`() {
        val state = TurtleCommanderSettings.State()
        ColorRuleManager.ensureInitialRules(state)
        val gitIdx = state.colorRules.indexOfFirst { it.id == "default:git" }
        state.colorRules[gitIdx].fontColor = "#DEADBE"
        ColorRuleManager.ensureInitialRules(state)
        val gitAfter = state.colorRules.first { it.id == "default:git" }
        assertEquals("#DEADBE", gitAfter.fontColor)
    }

    @Test
    fun `seeded rules cover all built-in project markers`() {
        val state = TurtleCommanderSettings.State()
        ColorRuleManager.ensureInitialRules(state)
        val ids = state.colorRules.map { it.id }.toSet()
        for (expected in listOf(
            "default:idea-project", "default:gradle", "default:maven",
            "default:cargo", "default:npm", "default:python",
            "default:cmake", "default:dotnet", "default:git",
        )) {
            assertTrue("missing default rule: $expected", expected in ids)
        }
    }

    @Test
    fun `getAllRules seeds on first call`() {
        val state = TurtleCommanderSettings.State()
        val all = ColorRuleManager.getAllRules(state)
        assertEquals(9, all.size)
        assertTrue(state.colorRulesInitialized)
    }

    @Test
    fun `resetToDefaults overwrites existing rules and re-adds defaults`() {
        val state = TurtleCommanderSettings.State()
        ColorRuleManager.ensureInitialRules(state)
        state.colorRules.clear()
        state.colorRules.add(SavedColorRule.fromRule(
            ColorRule(id = "custom-1", name = "custom", matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*.x")))
        ))
        ColorRuleManager.resetToDefaults(state)
        assertEquals(9, state.colorRules.size)
        assertTrue(state.colorRules.none { it.id == "custom-1" })
    }

    @Test
    fun `saveRule inserts new rule`() {
        val state = TurtleCommanderSettings.State().apply { colorRulesInitialized = true }
        val r = ColorRule(id = "x", name = "x", matchers = listOf(RuleMatcher.Size(SizeOp.GT, 1)))
        val result = ColorRuleManager.saveRule(state, r)
        assertEquals(ColorRuleManager.SaveResult.Saved, result)
        assertEquals(1, state.colorRules.size)
    }

    @Test
    fun `saveRule replaces rule with matching id`() {
        val state = TurtleCommanderSettings.State().apply { colorRulesInitialized = true }
        ColorRuleManager.saveRule(state, ColorRule(id = "x", name = "v1", matchers = listOf(RuleMatcher.Size(SizeOp.GT, 1))))
        val result = ColorRuleManager.saveRule(state, ColorRule(id = "x", name = "v2", matchers = listOf(RuleMatcher.Size(SizeOp.LT, 99))))
        assertEquals(ColorRuleManager.SaveResult.Replaced, result)
        assertEquals(1, state.colorRules.size)
        assertEquals("v2", state.colorRules[0].name)
    }

    @Test
    fun `deleteRule removes by id`() {
        val state = TurtleCommanderSettings.State().apply { colorRulesInitialized = true }
        ColorRuleManager.saveRule(state, ColorRule(id = "a", name = "a", matchers = listOf(RuleMatcher.Size(SizeOp.GT, 1))))
        ColorRuleManager.saveRule(state, ColorRule(id = "b", name = "b", matchers = listOf(RuleMatcher.Size(SizeOp.GT, 1))))
        assertTrue(ColorRuleManager.deleteRule(state, "a"))
        assertFalse(ColorRuleManager.deleteRule(state, "a"))
        assertEquals(1, state.colorRules.size)
        assertEquals("b", state.colorRules[0].name)
    }

    @Test
    fun `getMode defaults to WINNER when string invalid`() {
        val state = TurtleCommanderSettings.State().apply { colorizationMode = "garbage" }
        assertEquals(ColorizationMode.WINNER, ColorRuleManager.getMode(state))
    }

    @Test
    fun `setMode persists as enum name`() {
        val state = TurtleCommanderSettings.State()
        ColorRuleManager.setMode(state, ColorizationMode.LAYERED)
        assertEquals("LAYERED", state.colorizationMode)
        assertEquals(ColorizationMode.LAYERED, ColorRuleManager.getMode(state))
    }
}
