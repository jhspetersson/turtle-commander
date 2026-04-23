package io.github.jhspetersson.turtlecommander.service

import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.settings.ColorRule
import io.github.jhspetersson.turtlecommander.settings.ColorizationMode
import io.github.jhspetersson.turtlecommander.settings.PatternKind
import io.github.jhspetersson.turtlecommander.settings.RuleMatcher
import io.github.jhspetersson.turtlecommander.settings.RuleStyle
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ColorizationResolverTest {

    private fun dirEntry(path: Path) = FileEntry(
        name = path.fileName?.toString() ?: path.toString(),
        path = path,
        isDirectory = true,
        size = 0,
        lastModified = null,
        permissions = "",
    )

    private fun fileEntry(name: String, size: Long = 0) = FileEntry(
        name = name,
        path = Paths.get("dummy"),
        isDirectory = false,
        size = size,
        lastModified = null,
        permissions = "",
    )

    private val gitRule = ColorRule(
        id = "git",
        name = "git",
        priority = 10,
        matchers = listOf(RuleMatcher.Contains(PatternKind.EXACT, ".git")),
        style = RuleStyle(fontColor = "#AAA"),
    )

    private val nameRule = ColorRule(
        id = "logs",
        name = "logs",
        priority = 5,
        matchers = listOf(RuleMatcher.Name(PatternKind.GLOB, "*.log")),
        style = RuleStyle(fontColor = "#BBB"),
    )

    // --- master switch ---

    @Test
    fun `disabled flag returns EMPTY without consulting rules`() {
        val resolver = ColorizationResolver()
        val r = resolver.resolve(fileEntry("a.log"), listOf(nameRule), ColorizationMode.WINNER, enabled = false)
        assertTrue(r.isEmpty())
    }

    @Test
    fun `empty ruleset returns EMPTY`() {
        val resolver = ColorizationResolver()
        assertTrue(resolver.resolve(fileEntry("a"), emptyList(), ColorizationMode.WINNER, enabled = true).isEmpty())
    }

    @Test
    fun `parent link is skipped`() {
        val resolver = ColorizationResolver()
        val parentLink = FileEntry(
            name = "..",
            path = Paths.get("dummy"),
            isDirectory = true,
            size = 0,
            lastModified = null,
            permissions = "",
            isParentLink = true,
        )
        assertTrue(resolver.resolve(parentLink, listOf(gitRule), ColorizationMode.WINNER, enabled = true).isEmpty())
    }

    // --- caching ---

    @Test
    fun `child listing is cached between resolves`() {
        val resolver = ColorizationResolver()
        val calls = AtomicInteger()
        resolver.readChildNames = { _ ->
            calls.incrementAndGet()
            listOf(".git", "src")
        }
        val entry = dirEntry(Paths.get("repo"))
        resolver.resolve(entry, listOf(gitRule), ColorizationMode.WINNER, enabled = true)
        resolver.resolve(entry, listOf(gitRule), ColorizationMode.WINNER, enabled = true)
        resolver.resolve(entry, listOf(gitRule), ColorizationMode.WINNER, enabled = true)
        assertEquals("readChildNames must be memoized", 1, calls.get())
    }

    @Test
    fun `cache expires after TTL`() {
        val resolver = ColorizationResolver()
        val now = AtomicLong(1_000)
        resolver.clock = { now.get() }
        val calls = AtomicInteger()
        resolver.readChildNames = { _ ->
            calls.incrementAndGet()
            listOf(".git")
        }
        val entry = dirEntry(Paths.get("repo"))
        resolver.resolve(entry, listOf(gitRule), ColorizationMode.WINNER, enabled = true)
        now.addAndGet(ColorizationResolver.CACHE_TTL_MS + 1)
        resolver.resolve(entry, listOf(gitRule), ColorizationMode.WINNER, enabled = true)
        assertEquals(2, calls.get())
    }

    @Test
    fun `invalidate drops single path from cache`() {
        val resolver = ColorizationResolver()
        val calls = AtomicInteger()
        resolver.readChildNames = { _ ->
            calls.incrementAndGet()
            listOf(".git")
        }
        val entry = dirEntry(Paths.get("repo"))
        resolver.resolve(entry, listOf(gitRule), ColorizationMode.WINNER, enabled = true)
        assertTrue(resolver.isCached(entry.path))
        resolver.invalidate(entry.path)
        assertFalse(resolver.isCached(entry.path))
        resolver.resolve(entry, listOf(gitRule), ColorizationMode.WINNER, enabled = true)
        assertEquals(2, calls.get())
    }

    @Test
    fun `clearCache drops everything`() {
        val resolver = ColorizationResolver()
        resolver.readChildNames = { _ -> listOf(".git") }
        resolver.resolve(dirEntry(Paths.get("a")), listOf(gitRule), ColorizationMode.WINNER, enabled = true)
        resolver.resolve(dirEntry(Paths.get("b")), listOf(gitRule), ColorizationMode.WINNER, enabled = true)
        assertEquals(2, resolver.cachedChildrenSize())
        resolver.clearCache()
        assertEquals(0, resolver.cachedChildrenSize())
    }

    @Test
    fun `resolver skips Contains evaluation entirely for non-directory when only contains rules exist`() {
        val resolver = ColorizationResolver()
        val calls = AtomicInteger()
        resolver.readChildNames = { _ ->
            calls.incrementAndGet()
            listOf(".git")
        }
        val r = resolver.resolve(fileEntry("readme.md"), listOf(gitRule), ColorizationMode.WINNER, enabled = true)
        assertTrue(r.isEmpty())
        assertEquals("readChildNames must not run for file entries", 0, calls.get())
    }

    @Test
    fun `resolver skips Contains evaluator when no Contains rule is active`() {
        val resolver = ColorizationResolver()
        val calls = AtomicInteger()
        resolver.readChildNames = { _ ->
            calls.incrementAndGet()
            listOf("whatever")
        }
        // Only a Name-based rule; engine should never need child listings
        val r = resolver.resolve(
            entry = dirEntry(Paths.get("some-dir")),
            rules = listOf(nameRule),
            mode = ColorizationMode.WINNER,
            enabled = true,
            anyContainsMatcher = false,
        )
        assertTrue(r.isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun `readChildNames null result does not poison cache`() {
        val resolver = ColorizationResolver()
        val calls = AtomicInteger()
        resolver.readChildNames = { _ ->
            calls.incrementAndGet()
            null
        }
        val entry = dirEntry(Paths.get("broken"))
        resolver.resolve(entry, listOf(gitRule), ColorizationMode.WINNER, enabled = true)
        resolver.resolve(entry, listOf(gitRule), ColorizationMode.WINNER, enabled = true)
        // Not cached when listing failed — both calls hit the reader
        assertEquals(2, calls.get())
        assertFalse(resolver.isCached(entry.path))
    }

    @Test
    fun `resolve with contains returns style on match`() {
        val resolver = ColorizationResolver()
        resolver.readChildNames = { _ -> listOf(".git", "src") }
        val r = resolver.resolve(dirEntry(Paths.get("repo")), listOf(gitRule), ColorizationMode.WINNER, enabled = true)
        assertEquals("#AAA", r.fontColor)
    }
}
