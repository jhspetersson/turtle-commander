package io.github.jhspetersson.turtlecommander.settings

import java.util.UUID

/**
 * Built-in ruleset for the nine project-marker directory types that turtle-commander
 * used to colorize via hardcoded logic. Used by the migration seeder so upgraded
 * installs keep their previous look, and by the "Reset to defaults" UI action.
 */
object ColorRuleDefaults {

    /** Stable ID used by the migration seeder to detect and refresh defaults. */
    private fun defaultId(slug: String) = "default:$slug"

    fun isDefault(rule: ColorRule): Boolean = rule.id.startsWith("default:")

    private fun containsRule(
        slug: String,
        name: String,
        priority: Int,
        patterns: List<Pair<PatternKind, String>>,
        font: String,
        dot: String,
    ): ColorRule {
        val matchers = patterns.map { (kind, p) ->
            RuleMatcher.Contains(kind = kind, pattern = p, caseSensitive = false)
        }
        return ColorRule(
            id = defaultId(slug),
            name = name,
            priority = priority,
            active = true,
            combinator = if (matchers.size > 1) Combinator.OR else Combinator.AND,
            matchers = matchers,
            style = RuleStyle(
                fontColor = font,
                iconDotColor = dot,
            ),
        )
    }

    /**
     * Nine rules detecting common project markers (`.idea`, `pom.xml`, `Cargo.toml`, etc).
     * Priorities mirror the original detection fall-through order so WINNER mode reproduces
     * the pre-rule-engine behavior exactly.
     */
    fun builtinRules(): List<ColorRule> = listOf(
        containsRule(
            slug = "idea-project",
            name = "IntelliJ IDEA project",
            priority = 100,
            patterns = listOf(PatternKind.EXACT to ".idea"),
            font = "#5A9AD8", dot = "#5A9AD8",
        ),
        containsRule(
            slug = "gradle",
            name = "Gradle project",
            priority = 90,
            patterns = listOf(
                PatternKind.EXACT to "build.gradle.kts",
                PatternKind.EXACT to "build.gradle",
                PatternKind.EXACT to "settings.gradle.kts",
                PatternKind.EXACT to "settings.gradle",
            ),
            font = "#69B7A2", dot = "#69B7A2",
        ),
        containsRule(
            slug = "maven",
            name = "Maven project",
            priority = 80,
            patterns = listOf(PatternKind.EXACT to "pom.xml"),
            font = "#7A9ABC", dot = "#7A9ABC",
        ),
        containsRule(
            slug = "cargo",
            name = "Cargo (Rust) project",
            priority = 70,
            patterns = listOf(PatternKind.EXACT to "Cargo.toml"),
            font = "#B0A080", dot = "#B0A080",
        ),
        containsRule(
            slug = "npm",
            name = "Node / npm project",
            priority = 60,
            patterns = listOf(PatternKind.EXACT to "package.json"),
            font = "#7AB0A0", dot = "#7AB0A0",
        ),
        containsRule(
            slug = "python",
            name = "Python project",
            priority = 50,
            patterns = listOf(
                PatternKind.EXACT to "pyproject.toml",
                PatternKind.EXACT to "setup.py",
                PatternKind.EXACT to "requirements.txt",
                PatternKind.EXACT to ".venv",
                PatternKind.EXACT to "venv",
            ),
            font = "#7AAAC0", dot = "#7AAAC0",
        ),
        containsRule(
            slug = "cmake",
            name = "CMake project",
            priority = 40,
            patterns = listOf(PatternKind.EXACT to "CMakeLists.txt"),
            font = "#7A8AAA", dot = "#7A8AAA",
        ),
        containsRule(
            slug = "dotnet",
            name = ".NET project",
            priority = 30,
            patterns = listOf(
                PatternKind.GLOB to "*.csproj",
                PatternKind.GLOB to "*.vbproj",
                PatternKind.GLOB to "*.fsproj",
                PatternKind.EXACT to "Directory.Build.props",
                PatternKind.EXACT to "global.json",
            ),
            font = "#9A8ABB", dot = "#9A8ABB",
        ),
        containsRule(
            slug = "git",
            name = "Git repository",
            priority = 20,
            patterns = listOf(PatternKind.EXACT to ".git"),
            font = "#8AAA7A", dot = "#9AAA7C",
        ),
    ) + symlinkRules()

    private fun symlinkRule(slug: String, name: String, priority: Int, state: SymlinkState, font: String): ColorRule =
        ColorRule(
            id = defaultId(slug),
            name = name,
            priority = priority,
            active = true,
            combinator = Combinator.AND,
            matchers = listOf(RuleMatcher.Symlink(state)),
            style = RuleStyle(fontColor = font, iconDotColor = font),
        )

    /**
     * Two built-in rules giving symbolic links a distinct look out of the box: valid links in
     * cyan (the long-standing terminal convention for symlinks), broken/dangling links in red.
     * High priority so a link's nature wins over project-marker coloring in WINNER mode.
     */
    fun symlinkRules(): List<ColorRule> = listOf(
        symlinkRule("symlink-valid", "Symbolic link", priority = 110, state = SymlinkState.VALID, font = "#4DB6AC"),
        symlinkRule("symlink-broken", "Broken symbolic link", priority = 120, state = SymlinkState.BROKEN, font = "#E06666"),
    )

    /** Clones the built-in rules with fresh random IDs — used by "Reset to defaults" UI action. */
    fun freshBuiltinRules(): List<ColorRule> =
        builtinRules().map { it.copy(id = UUID.randomUUID().toString()) }
}
