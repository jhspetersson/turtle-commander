package io.github.jhspetersson.turtlecommander.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.FileSystems
import java.nio.file.Path

class FileNameGlobMatcherTest {

    @Test
    fun `matches ordinary names like the default PathMatcher`() {
        val matcher = FileNameGlobMatcher("*.txt")
        assertTrue(matcher.matches("alpha.txt"))
        assertFalse(matcher.matches("alpha.md"))
    }

    @Test
    fun `does not throw on names with Windows-illegal characters`() {
        val names = listOf("what?.txt", "a*b.txt", "a<b>.txt", "a|b.txt", "a\"b.txt", "a:b.txt")
        val matcher = FileNameGlobMatcher("*")
        for (name in names) {
            assertTrue("'$name' should match '*'", matcher.matches(name))
        }
    }

    @Test
    fun `filters names with Windows-illegal characters by extension`() {
        val matcher = FileNameGlobMatcher("*.md")
        assertFalse(matcher.matches("what?.txt"))
        assertTrue(matcher.matches("what?.md"))
    }

    @Test
    fun `substring wrapped filter matches names with Windows-illegal characters`() {
        val matcher = FileNameGlobMatcher(wrapAsSubstringGlobIfPlain("what"))
        assertTrue(matcher.matches("what?.txt"))
        assertFalse(matcher.matches("other?.txt"))
    }

    @Test
    fun `question mark glob matches a literal question mark in the name`() {
        val matcher = FileNameGlobMatcher("what?.txt")
        assertTrue(matcher.matches("what?.txt"))
        assertTrue(matcher.matches("whatX.txt"))
        assertFalse(matcher.matches("what.txt"))
    }

    @Test
    fun `invalid glob throws at construction`() {
        try {
            FileNameGlobMatcher("[")
            fail("expected an exception for an unclosed character class")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `regex fallback agrees with the default PathMatcher on legal names`() {
        val globs = listOf(
            "*", "*.txt", "a?c", "*foo*", "[abc].txt", "[!abc].txt", "[a-c]x", "{foo,bar}.txt",
            "foo.{txt,md}", "\\*.txt", "a.b", "**", "file[0-9][0-9]", "x+y", "(a)", "a^b\$c",
        )
        val names = listOf(
            "a.txt", "abc", "aXc", "foo", "xfoox", "b.txt", "d.txt", "bx", "bar.txt", "foo.md",
            "a.b", "axb", "file42", "file4", "x+y", "(a)", "a^b\$c", "A.TXT", "FOO.md",
        )
        val fs = FileSystems.getDefault()
        for (glob in globs) {
            val expected = fs.getPathMatcher("glob:$glob")
            val regex = globToRegex(glob)
            for (name in names) {
                assertEquals(
                    "glob '$glob' vs name '$name'",
                    expected.matches(Path.of(name)),
                    regex.matches(name),
                )
            }
        }
    }

    @Test
    fun `regex fallback handles Windows-illegal characters`() {
        assertTrue(globToRegex("*").matches("what?.txt"))
        assertTrue(globToRegex("*what*").matches("what?.txt"))
        assertTrue(globToRegex("*.txt").matches("a<b>|c.txt"))
        assertFalse(globToRegex("*.md").matches("a<b>|c.txt"))
        assertTrue(globToRegex("a\\?b").matches("a?b"))
        assertFalse(globToRegex("a\\?b").matches("aXb"))
    }
}
