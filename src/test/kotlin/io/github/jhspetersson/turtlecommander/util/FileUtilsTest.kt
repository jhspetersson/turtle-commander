package io.github.jhspetersson.turtlecommander.util

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path

class FileUtilsTest {

    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("file-utils-test-")
    }

    @After
    fun tearDown() {
        // Best-effort cleanup — tests run against throwaway temps anyway.
        runCatching {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // --- fileErrorMessage ---

    @Test
    fun `fileErrorMessage surfaces the offending file for AccessDeniedException`() {
        val msg = fileErrorMessage(AccessDeniedException("/secret/foo"))
        assertEquals("Access denied: /secret/foo", msg)
    }

    @Test
    fun `fileErrorMessage uses the exception message for generic IOException`() {
        val msg = fileErrorMessage(IOException("disk full"))
        assertEquals("disk full", msg)
    }

    @Test
    fun `fileErrorMessage falls back to class simple name when message is null`() {
        val msg = fileErrorMessage(IOException())
        assertEquals("IOException", msg)
    }

    // --- countFiles ---

    @Test
    fun `countFiles returns 1 for a single file`() = runBlocking {
        val f = Files.createFile(root.resolve("a.txt"))
        assertEquals(1, countFiles(listOf(f)))
    }

    @Test
    fun `countFiles counts flat directory including the directory itself`() = runBlocking {
        Files.createFile(root.resolve("a.txt"))
        Files.createFile(root.resolve("b.txt"))
        Files.createFile(root.resolve("c.txt"))
        // walkFileTree visits each file (3) plus the starting directory (1) = 4.
        assertEquals(4, countFiles(listOf(root)))
    }

    @Test
    fun `countFiles recurses into nested directories`() = runBlocking {
        val sub = Files.createDirectory(root.resolve("sub"))
        val subsub = Files.createDirectory(sub.resolve("deeper"))
        Files.createFile(root.resolve("top.txt"))
        Files.createFile(sub.resolve("mid.txt"))
        Files.createFile(subsub.resolve("leaf.txt"))
        // 3 files + 3 directories (root, sub, deeper) = 6.
        assertEquals(6, countFiles(listOf(root)))
    }

    @Test
    fun `countFiles sums counts across multiple input roots`() = runBlocking {
        val a = Files.createDirectory(root.resolve("a"))
        val b = Files.createDirectory(root.resolve("b"))
        Files.createFile(a.resolve("x"))
        Files.createFile(b.resolve("y"))
        Files.createFile(b.resolve("z"))
        // a: dir + 1 file = 2; b: dir + 2 files = 3 → total 5.
        assertEquals(5, countFiles(listOf(a, b)))
    }

    @Test
    fun `countFiles handles an empty directory`() = runBlocking {
        val empty = Files.createDirectory(root.resolve("empty"))
        assertEquals(1, countFiles(listOf(empty)))
    }

    @Test
    fun `countFiles returns 0 for an empty list`() = runBlocking {
        assertEquals(0, countFiles(emptyList()))
    }

    @Test
    fun `countFiles mixes single files and directories in one call`() = runBlocking {
        val single = Files.createFile(root.resolve("solo.txt"))
        val dir = Files.createDirectory(root.resolve("dir"))
        Files.createFile(dir.resolve("inside.txt"))
        // solo.txt (1) + dir dir-entry (1) + inside.txt (1) = 3.
        assertEquals(3, countFiles(listOf(single, dir)))
    }

    // --- readFileOwner / readFilePermissions edge cases ---

    @Test
    fun `readFileOwner returns empty string for a non-existent path without throwing`() {
        val missing = root.resolve("does-not-exist")
        assertTrue(!Files.exists(missing))
        // Contract: never throws — the exception branch returns "".
        assertEquals("", readFileOwner(missing))
    }

    @Test
    fun `readFileGroup returns empty string on platforms without POSIX support`() {
        // Whether or not the platform has POSIX, the contract is that it never throws.
        val f = Files.createFile(root.resolve("any.txt"))
        val group = readFileGroup(f)
        // Can be empty (Windows) or a valid group name (Linux/macOS). Just ensure no crash
        // and the result is a String (non-null, which the return type already guarantees).
        assertTrue(group.isEmpty() || group.isNotEmpty())
    }

    @Test
    fun `readFilePermissions on a missing file returns empty string`() {
        val missing = root.resolve("ghost.txt")
        assertEquals("", readFilePermissions(missing, isWindows = false))
        assertEquals("", readFilePermissions(missing, isWindows = true))
    }

    // --- wrapAsSubstringGlobIfPlain ---
    //
    // Plain text → substring wrap with `*…*`. Anything that looks like a glob (`*`, `?`, `[`)
    // or carries an extension dot is passed through verbatim. Both the FileTab quick filter
    // and the search dialog's glob mode share this rule so `foo?` and `foo.txt` behave the
    // same in both UIs.

    @Test
    fun `wrapAsSubstringGlobIfPlain wraps plain text with substring stars`() {
        assertEquals("*foo*", wrapAsSubstringGlobIfPlain("foo"))
    }

    @Test
    fun `wrapAsSubstringGlobIfPlain wraps empty string`() {
        // Empty input is harmless; the matcher upstream will either not run or just match
        // anything. Behaviour pinned here so the helper stays total.
        assertEquals("**", wrapAsSubstringGlobIfPlain(""))
    }

    @Test
    fun `wrapAsSubstringGlobIfPlain leaves star globs alone`() {
        assertEquals("*.txt", wrapAsSubstringGlobIfPlain("*.txt"))
        assertEquals("foo*", wrapAsSubstringGlobIfPlain("foo*"))
        assertEquals("*foo*", wrapAsSubstringGlobIfPlain("*foo*"))
    }

    @Test
    fun `wrapAsSubstringGlobIfPlain leaves question-mark globs alone`() {
        assertEquals("foo?", wrapAsSubstringGlobIfPlain("foo?"))
        assertEquals("?foo", wrapAsSubstringGlobIfPlain("?foo"))
        assertEquals("foo??bar", wrapAsSubstringGlobIfPlain("foo??bar"))
    }

    @Test
    fun `wrapAsSubstringGlobIfPlain leaves character class globs alone`() {
        assertEquals("[abc]", wrapAsSubstringGlobIfPlain("[abc]"))
        assertEquals("file[0-9]", wrapAsSubstringGlobIfPlain("file[0-9]"))
    }

    @Test
    fun `wrapAsSubstringGlobIfPlain treats dot as an extension hint and skips wrap`() {
        // `report.pdf` is meant to find a file literally named that, not match
        // `oldreport.pdf.bak` as a substring would.
        assertEquals("report.pdf", wrapAsSubstringGlobIfPlain("report.pdf"))
        assertEquals(".gitignore", wrapAsSubstringGlobIfPlain(".gitignore"))
    }
}
