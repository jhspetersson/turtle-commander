package io.github.jhspetersson.turtlecommander.settings

import io.github.jhspetersson.turtlecommander.model.FileEntry
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths
import java.nio.file.attribute.FileTime

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

    private fun meta(
        created: Long? = null,
        modified: Long? = null,
        owner: String = "",
        group: String = "",
        permissions: String = "",
        parentLink: Boolean = false,
    ) = FileEntry(
        name = "f",
        path = dummyPath,
        isDirectory = false,
        size = 0,
        creationTime = created?.let { FileTime.fromMillis(it) },
        lastModified = modified?.let { FileTime.fromMillis(it) },
        owner = owner,
        group = group,
        permissions = permissions,
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

    // --- Date: absolute ---

    @Test
    fun `date BEFORE matches strictly earlier timestamps`() {
        val m = RuleMatcher.Date(DateField.MODIFIED, DateOp.BEFORE, epochMillis = 1_000L)
        assertTrue(m.matches(meta(modified = 999L), emptyContains))
        assertFalse(m.matches(meta(modified = 1_000L), emptyContains))
        assertFalse(m.matches(meta(modified = 1_001L), emptyContains))
    }

    @Test
    fun `date AFTER is inclusive of the anchor`() {
        val m = RuleMatcher.Date(DateField.MODIFIED, DateOp.AFTER, epochMillis = 1_000L)
        assertTrue(m.matches(meta(modified = 1_000L), emptyContains))
        assertTrue(m.matches(meta(modified = 5_000L), emptyContains))
        assertFalse(m.matches(meta(modified = 999L), emptyContains))
    }

    @Test
    fun `date BETWEEN is inclusive on both ends`() {
        val m = RuleMatcher.Date(DateField.CREATED, DateOp.BETWEEN, epochMillis = 100L, epochMillisMax = 200L)
        assertTrue(m.matches(meta(created = 100L), emptyContains))
        assertTrue(m.matches(meta(created = 150L), emptyContains))
        assertTrue(m.matches(meta(created = 200L), emptyContains))
        assertFalse(m.matches(meta(created = 99L), emptyContains))
        assertFalse(m.matches(meta(created = 201L), emptyContains))
    }

    @Test
    fun `date matcher reads the field it targets`() {
        val created = RuleMatcher.Date(DateField.CREATED, DateOp.AFTER, epochMillis = 500L)
        // Only the creation time qualifies; modified is below the anchor.
        assertTrue(created.matches(meta(created = 600L, modified = 100L), emptyContains))
        assertFalse(created.matches(meta(created = 100L, modified = 600L), emptyContains))
    }

    @Test
    fun `date matcher never matches when the timestamp is absent`() {
        val m = RuleMatcher.Date(DateField.CREATED, DateOp.AFTER, epochMillis = 0L)
        assertFalse(m.matches(meta(created = null), emptyContains))
    }

    // --- Date: relative ---

    @Test
    fun `date WITHIN_LAST matches recent timestamps`() {
        val now = System.currentTimeMillis()
        val m = RuleMatcher.Date(DateField.MODIFIED, DateOp.WITHIN_LAST, amount = 7, unit = DateUnit.DAYS)
        assertTrue(m.matches(meta(modified = now - DateUnit.DAYS.toMillis(1)), emptyContains))
        assertFalse(m.matches(meta(modified = now - DateUnit.DAYS.toMillis(30)), emptyContains))
    }

    @Test
    fun `date OLDER_THAN matches stale timestamps`() {
        val now = System.currentTimeMillis()
        val m = RuleMatcher.Date(DateField.MODIFIED, DateOp.OLDER_THAN, amount = 1, unit = DateUnit.HOURS)
        assertTrue(m.matches(meta(modified = now - DateUnit.HOURS.toMillis(2)), emptyContains))
        assertFalse(m.matches(meta(modified = now - DateUnit.MINUTES.toMillis(5)), emptyContains))
    }

    @Test
    fun `date relative with non-positive amount never matches`() {
        val m = RuleMatcher.Date(DateField.MODIFIED, DateOp.WITHIN_LAST, amount = 0, unit = DateUnit.DAYS)
        assertFalse(m.matches(meta(modified = System.currentTimeMillis()), emptyContains))
    }

    @Test
    fun `date matcher skips parent links`() {
        val m = RuleMatcher.Date(DateField.MODIFIED, DateOp.AFTER, epochMillis = 0L)
        assertFalse(m.matches(meta(modified = 10L, parentLink = true), emptyContains))
    }

    // --- Text: owner / group / permissions ---

    @Test
    fun `text owner exact match is case-insensitive by default`() {
        val m = RuleMatcher.Text(TextProperty.OWNER, PatternKind.EXACT, "Root")
        assertTrue(m.matches(meta(owner = "root"), emptyContains))
        assertFalse(m.matches(meta(owner = "daemon"), emptyContains))
    }

    @Test
    fun `text group exact match`() {
        val m = RuleMatcher.Text(TextProperty.GROUP, PatternKind.EXACT, "staff")
        assertTrue(m.matches(meta(group = "staff"), emptyContains))
        assertFalse(m.matches(meta(group = "wheel"), emptyContains))
    }

    @Test
    fun `text permissions glob finds world-writable`() {
        val m = RuleMatcher.Text(TextProperty.PERMISSIONS, PatternKind.GLOB, "*w*", caseSensitive = true)
        assertTrue(m.matches(meta(permissions = "rwxrwxrwx"), emptyContains))
        assertFalse(m.matches(meta(permissions = "r-xr-xr-x"), emptyContains))
    }

    @Test
    fun `text permissions regex matches`() {
        val m = RuleMatcher.Text(TextProperty.PERMISSIONS, PatternKind.REGEX, ".{6}rwx")
        assertTrue(m.matches(meta(permissions = "rwxr-xrwx"), emptyContains))
        assertFalse(m.matches(meta(permissions = "rwxr-xr-x"), emptyContains))
    }

    @Test
    fun `text matcher targets only its own field`() {
        val ownerRule = RuleMatcher.Text(TextProperty.OWNER, PatternKind.EXACT, "root", caseSensitive = true)
        // group is "root" but owner is not — must not match on the wrong field.
        assertFalse(ownerRule.matches(meta(owner = "user", group = "root"), emptyContains))
    }

    @Test
    fun `text matcher skips parent links`() {
        val m = RuleMatcher.Text(TextProperty.OWNER, PatternKind.GLOB, "*")
        assertFalse(m.matches(meta(owner = "root", parentLink = true), emptyContains))
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

    @Test
    fun `globToRegex treats double ampersand inside char class as literal`() {
        // Glob `[a&&b]` is just three literal members. Java regex would otherwise read
        // `&&` as character-class intersection (`[a] ∩ [b]` → empty), which used to
        // make this glob match nothing.
        val m = RuleMatcher.Name(PatternKind.GLOB, "[a&&b]", caseSensitive = true)
        assertTrue(m.matches(file("a"), ContainsEvaluator.EMPTY))
        assertTrue(m.matches(file("b"), ContainsEvaluator.EMPTY))
        assertTrue(m.matches(file("&"), ContainsEvaluator.EMPTY))
        assertFalse(m.matches(file("c"), ContainsEvaluator.EMPTY))
    }

    // --- Symlink ---

    private fun symlink(broken: Boolean) = FileEntry(
        name = "link",
        path = dummyPath,
        isDirectory = false,
        size = 0,
        lastModified = null,
        permissions = "",
        isSymbolicLink = true,
        isBrokenSymlink = broken,
        linkTarget = "/somewhere",
    )

    @Test
    fun `symlink ANY matches any link but not plain files`() {
        val m = RuleMatcher.Symlink(SymlinkState.ANY)
        assertTrue(m.matches(symlink(broken = false), emptyContains))
        assertTrue(m.matches(symlink(broken = true), emptyContains))
        assertFalse(m.matches(file("regular.txt"), emptyContains))
        assertFalse(m.matches(file("dir", isDir = true), emptyContains))
    }

    @Test
    fun `symlink VALID matches only resolving links`() {
        val m = RuleMatcher.Symlink(SymlinkState.VALID)
        assertTrue(m.matches(symlink(broken = false), emptyContains))
        assertFalse(m.matches(symlink(broken = true), emptyContains))
        assertFalse(m.matches(file("regular.txt"), emptyContains))
    }

    @Test
    fun `symlink BROKEN matches only dangling links`() {
        val m = RuleMatcher.Symlink(SymlinkState.BROKEN)
        assertTrue(m.matches(symlink(broken = true), emptyContains))
        assertFalse(m.matches(symlink(broken = false), emptyContains))
        assertFalse(m.matches(file("regular.txt"), emptyContains))
    }

    @Test
    fun `default symlink rules cover valid and broken with distinct colors`() {
        val rules = ColorRuleDefaults.symlinkRules()
        val valid = rules.single { r -> r.matchers.any { it is RuleMatcher.Symlink && it.state == SymlinkState.VALID } }
        val broken = rules.single { r -> r.matchers.any { it is RuleMatcher.Symlink && it.state == SymlinkState.BROKEN } }
        assertTrue(valid.style.fontColor.isNotEmpty())
        assertTrue(broken.style.fontColor.isNotEmpty())
        assertNotEquals(valid.style.fontColor, broken.style.fontColor)
        // A dangling link points nowhere, so the broken default strikes the name through;
        // valid links don't.
        assertEquals(true, broken.style.strikethrough)
        assertNull(valid.style.strikethrough)
    }

    @Test
    fun `symlink matcher survives saved round-trip`() {
        val original = RuleMatcher.Symlink(SymlinkState.BROKEN)
        val restored = SavedColorMatcher.fromMatcher(original).toMatcher()
        assertEquals(original, restored)
    }

    // --- Named pipe (FIFO) ---

    private fun namedPipe() = FileEntry(
        name = "pipe",
        path = dummyPath,
        isDirectory = false,
        size = 0,
        lastModified = null,
        permissions = "",
        isNamedPipe = true,
    )

    @Test
    fun `named pipe matches FIFOs but not plain files, dirs, or symlinks`() {
        val m = RuleMatcher.NamedPipe
        assertTrue(m.matches(namedPipe(), emptyContains))
        assertFalse(m.matches(file("regular.txt"), emptyContains))
        assertFalse(m.matches(file("dir", isDir = true), emptyContains))
        assertFalse(m.matches(symlink(broken = false), emptyContains))
    }

    @Test
    fun `default named-pipe rule has a distinct color`() {
        val rule = ColorRuleDefaults.namedPipeRules().single()
        assertTrue(rule.matchers.any { it is RuleMatcher.NamedPipe })
        assertTrue(rule.style.fontColor.isNotEmpty())
        assertNull(rule.style.strikethrough)
    }

    @Test
    fun `named pipe matcher survives saved round-trip`() {
        val restored = SavedColorMatcher.fromMatcher(RuleMatcher.NamedPipe).toMatcher()
        assertEquals(RuleMatcher.NamedPipe, restored)
    }
}
