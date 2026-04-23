package io.github.jhspetersson.turtlecommander.settings

import io.github.jhspetersson.turtlecommander.model.FileEntry
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths

class RuleMatcherTest {

    // Use a constant path — Windows rejects characters like `*` or `?` in paths, and
    // matcher logic examines only FileEntry.name / size / isDirectory.
    private val dummyPath = Paths.get("dummy")

    private fun file(name: String, size: Long = 0, isDir: Boolean = false, parentLink: Boolean = false) = FileEntry(
        name = name,
        path = dummyPath,
        isDirectory = isDir,
        size = size,
        lastModified = null,
        permissions = "",
        isParentLink = parentLink,
    )

    private val emptyContains = ContainsEvaluator.EMPTY

    // --- Size ---

    @Test
    fun `size LT matches under threshold`() {
        val m = RuleMatcher.Size(SizeOp.LT, 100)
        assertTrue(m.matches(file("a", 50), emptyContains))
        assertFalse(m.matches(file("a", 100), emptyContains))
        assertFalse(m.matches(file("a", 200), emptyContains))
    }

    @Test
    fun `size LE matches at and under threshold`() {
        val m = RuleMatcher.Size(SizeOp.LE, 100)
        assertTrue(m.matches(file("a", 99), emptyContains))
        assertTrue(m.matches(file("a", 100), emptyContains))
        assertFalse(m.matches(file("a", 101), emptyContains))
    }

    @Test
    fun `size EQ matches exactly`() {
        val m = RuleMatcher.Size(SizeOp.EQ, 1024)
        assertTrue(m.matches(file("a", 1024), emptyContains))
        assertFalse(m.matches(file("a", 1023), emptyContains))
        assertFalse(m.matches(file("a", 1025), emptyContains))
    }

    @Test
    fun `size GE matches at and over threshold`() {
        val m = RuleMatcher.Size(SizeOp.GE, 100)
        assertTrue(m.matches(file("a", 100), emptyContains))
        assertTrue(m.matches(file("a", 999), emptyContains))
        assertFalse(m.matches(file("a", 99), emptyContains))
    }

    @Test
    fun `size GT matches over threshold`() {
        val m = RuleMatcher.Size(SizeOp.GT, 100)
        assertTrue(m.matches(file("a", 101), emptyContains))
        assertFalse(m.matches(file("a", 100), emptyContains))
    }

    @Test
    fun `size BETWEEN is inclusive on both ends`() {
        val m = RuleMatcher.Size(SizeOp.BETWEEN, 100, 200)
        assertTrue(m.matches(file("a", 100), emptyContains))
        assertTrue(m.matches(file("a", 150), emptyContains))
        assertTrue(m.matches(file("a", 200), emptyContains))
        assertFalse(m.matches(file("a", 99), emptyContains))
        assertFalse(m.matches(file("a", 201), emptyContains))
    }

    @Test
    fun `size matcher never matches directories`() {
        val m = RuleMatcher.Size(SizeOp.GT, 0)
        assertFalse(m.matches(file("a", 999, isDir = true), emptyContains))
    }

    @Test
    fun `size handles large values past Int range`() {
        val giga = 5L * 1024 * 1024 * 1024
        val m = RuleMatcher.Size(SizeOp.GT, giga)
        assertTrue(m.matches(file("a", giga + 1), emptyContains))
        assertFalse(m.matches(file("a", giga), emptyContains))
    }

    // --- Name: glob ---

    @Test
    fun `name glob matches simple extension`() {
        val m = RuleMatcher.Name(PatternKind.GLOB, "*.kt")
        assertTrue(m.matches(file("Foo.kt"), emptyContains))
        assertTrue(m.matches(file(".kt"), emptyContains))
        assertFalse(m.matches(file("Foo.kts"), emptyContains))
    }

    @Test
    fun `name glob question mark matches one character`() {
        val m = RuleMatcher.Name(PatternKind.GLOB, "a?c")
        assertTrue(m.matches(file("abc"), emptyContains))
        assertTrue(m.matches(file("axc"), emptyContains))
        assertFalse(m.matches(file("ac"), emptyContains))
        assertFalse(m.matches(file("abbc"), emptyContains))
    }

    @Test
    fun `name glob character class matches any member`() {
        val m = RuleMatcher.Name(PatternKind.GLOB, "file.[ch]")
        assertTrue(m.matches(file("file.c"), emptyContains))
        assertTrue(m.matches(file("file.h"), emptyContains))
        assertFalse(m.matches(file("file.cpp"), emptyContains))
    }

    @Test
    fun `name glob negated character class`() {
        val m = RuleMatcher.Name(PatternKind.GLOB, "x[!0-9]")
        assertTrue(m.matches(file("xa"), emptyContains))
        assertFalse(m.matches(file("x5"), emptyContains))
    }

    @Test
    fun `name glob is case-insensitive by default`() {
        val m = RuleMatcher.Name(PatternKind.GLOB, "*.LOG")
        assertTrue(m.matches(file("server.log"), emptyContains))
    }

    @Test
    fun `name glob case-sensitive when requested`() {
        val m = RuleMatcher.Name(PatternKind.GLOB, "*.LOG", caseSensitive = true)
        assertFalse(m.matches(file("server.log"), emptyContains))
        assertTrue(m.matches(file("server.LOG"), emptyContains))
    }

    @Test
    fun `name glob escapes regex metacharacters`() {
        val m = RuleMatcher.Name(PatternKind.GLOB, "a.b")
        assertTrue(m.matches(file("a.b"), emptyContains))
        assertFalse(m.matches(file("aXb"), emptyContains))
    }

    // --- Name: regex ---

    @Test
    fun `name regex must fully match`() {
        val m = RuleMatcher.Name(PatternKind.REGEX, """.*\.tmp""")
        assertTrue(m.matches(file("a.tmp"), emptyContains))
        assertFalse(m.matches(file("a.tmp.bak"), emptyContains))
    }

    @Test
    fun `name regex case insensitive by default`() {
        val m = RuleMatcher.Name(PatternKind.REGEX, "readme")
        assertTrue(m.matches(file("README"), emptyContains))
    }

    @Test
    fun `invalid regex never matches instead of throwing`() {
        val m = RuleMatcher.Name(PatternKind.REGEX, "[unterminated")
        assertFalse(m.matches(file("anything"), emptyContains))
    }

    @Test
    fun `empty pattern never matches`() {
        val glob = RuleMatcher.Name(PatternKind.GLOB, "")
        val regex = RuleMatcher.Name(PatternKind.REGEX, "")
        assertFalse(glob.matches(file("a"), emptyContains))
        assertFalse(regex.matches(file("a"), emptyContains))
    }

    // --- Name: appliesTo scope ---

    @Test
    fun `name appliesTo FILE ignores directories`() {
        val m = RuleMatcher.Name(PatternKind.GLOB, "*", appliesTo = AppliesTo.FILE)
        assertTrue(m.matches(file("f"), emptyContains))
        assertFalse(m.matches(file("d", isDir = true), emptyContains))
    }

    @Test
    fun `name appliesTo DIR ignores files`() {
        val m = RuleMatcher.Name(PatternKind.GLOB, "*", appliesTo = AppliesTo.DIR)
        assertFalse(m.matches(file("f"), emptyContains))
        assertTrue(m.matches(file("d", isDir = true), emptyContains))
    }

    @Test
    fun `name matcher skips parent links`() {
        val m = RuleMatcher.Name(PatternKind.EXACT, "..", appliesTo = AppliesTo.BOTH)
        assertFalse(m.matches(file("..", parentLink = true, isDir = true), emptyContains))
    }

    // --- Contains ---

    @Test
    fun `contains exact match on child name`() {
        val m = RuleMatcher.Contains(PatternKind.EXACT, ".git")
        val children = { _: FileEntry -> listOf("src", ".git", "README.md") }
        assertTrue(m.matches(file("root", isDir = true), children))
    }

    @Test
    fun `contains only evaluates directories`() {
        val m = RuleMatcher.Contains(PatternKind.EXACT, "x")
        val children = ContainsEvaluator { listOf("x") } // would match if invoked
        assertFalse(m.matches(file("f", isDir = false), children))
    }

    @Test
    fun `contains returns false when evaluator yields null`() {
        val m = RuleMatcher.Contains(PatternKind.EXACT, "x")
        assertFalse(m.matches(file("d", isDir = true), ContainsEvaluator.EMPTY))
    }

    @Test
    fun `contains glob matches any one entry`() {
        val m = RuleMatcher.Contains(PatternKind.GLOB, "*.csproj")
        val children = ContainsEvaluator { listOf("readme.txt", "App.csproj") }
        assertTrue(m.matches(file("d", isDir = true), children))
    }

    @Test
    fun `contains regex matches first qualifying entry`() {
        val m = RuleMatcher.Contains(PatternKind.REGEX, """settings\.gradle(\.kts)?""")
        val children = ContainsEvaluator { listOf("src", "settings.gradle.kts", "build.gradle") }
        assertTrue(m.matches(file("d", isDir = true), children))
    }

    @Test
    fun `contains case sensitivity respected`() {
        val insensitive = RuleMatcher.Contains(PatternKind.EXACT, "POM.XML", caseSensitive = false)
        val sensitive = RuleMatcher.Contains(PatternKind.EXACT, "POM.XML", caseSensitive = true)
        val children = ContainsEvaluator { listOf("pom.xml") }
        assertTrue(insensitive.matches(file("d", isDir = true), children))
        assertFalse(sensitive.matches(file("d", isDir = true), children))
    }

    // --- globToRegex direct coverage ---

    @Test
    fun `globToRegex wraps pattern with anchors`() {
        val r = globToRegex("abc")
        assertTrue(r.startsWith("^"))
        assertTrue(r.endsWith("$"))
    }

    @Test
    fun `globToRegex preserves backslash-escaped glob char`() {
        // "\*" should match a literal asterisk in the name
        val m = RuleMatcher.Name(PatternKind.GLOB, """\*literal""", caseSensitive = true)
        assertTrue(m.matches(file("*literal"), ContainsEvaluator.EMPTY))
        assertFalse(m.matches(file("Xliteral"), ContainsEvaluator.EMPTY))
    }
}
