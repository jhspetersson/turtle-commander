package io.github.jhspetersson.turtlecommander.operation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class CdCommandTest {

    // --- parseArgument ---

    @Test
    fun `parses cd with a relative argument`() {
        assertEquals("dir/sub", CdCommand.parseArgument("cd dir/sub"))
    }

    @Test
    fun `parses cd with backslashes verbatim`() {
        assertEquals("""dir\sub""", CdCommand.parseArgument("""cd dir\sub"""))
    }

    @Test
    fun `bare cd yields empty argument`() {
        assertEquals("", CdCommand.parseArgument("cd"))
        assertEquals("", CdCommand.parseArgument("  cd  "))
    }

    @Test
    fun `cd is matched case-insensitively`() {
        assertEquals("Windows", CdCommand.parseArgument("CD Windows"))
    }

    @Test
    fun `strips surrounding quotes so paths with spaces survive`() {
        assertEquals("my dir", CdCommand.parseArgument("cd \"my dir\""))
        assertEquals("my dir", CdCommand.parseArgument("cd 'my dir'"))
    }

    @Test
    fun `non-cd commands return null`() {
        assertNull(CdCommand.parseArgument("ls -la"))
        assertNull(CdCommand.parseArgument("cdircmd"))   // token isn't exactly "cd"
        assertNull(CdCommand.parseArgument("cd.txt"))     // no whitespace after cd
        assertNull(CdCommand.parseArgument("mkdir cd"))
    }

    // --- resolveTarget ---

    private val home = Path.of("/home/user")

    @Test
    fun `relative argument resolves against the base directory`() {
        val base = Path.of("/work/project")
        assertEquals(Path.of("/work/project/src/main"), CdCommand.resolveTarget("src/main", base, home, isWindows = false))
    }

    @Test
    fun `parent references normalize away`() {
        val base = Path.of("/work/project/src")
        assertEquals(Path.of("/work/project"), CdCommand.resolveTarget("..", base, home, isWindows = false))
    }

    @Test
    fun `absolute posix argument ignores the base`() {
        val base = Path.of("/work/project")
        assertEquals(Path.of("/etc/hosts"), CdCommand.resolveTarget("/etc/hosts", base, home, isWindows = false))
    }

    @Test
    fun `bare and tilde arguments resolve to home`() {
        val base = Path.of("/work/project")
        assertEquals(home, CdCommand.resolveTarget("", base, home, isWindows = false))
        assertEquals(home, CdCommand.resolveTarget("~", base, home, isWindows = false))
        assertEquals(Path.of("/home/user/docs"), CdCommand.resolveTarget("~/docs", base, home, isWindows = false))
        assertEquals(home, CdCommand.resolveTarget("~/", base, home, isWindows = false))
    }

    @Test
    fun `tilde with backslash expands to home on windows`() {
        val base = Path.of("/work/project")
        assertEquals(Path.of("/home/user/docs"), CdCommand.resolveTarget("""~\docs""", base, home, isWindows = true))
        assertEquals(home, CdCommand.resolveTarget("""~\""", base, home, isWindows = true))
        assertEquals(Path.of("/home/user/docs"), CdCommand.resolveTarget("~/docs", base, home, isWindows = true))
        assertEquals(home, CdCommand.resolveTarget("~", base, home, isWindows = true))
    }

    @Test
    fun `backslashes act as separators on posix`() {
        val base = Path.of("/work/project")
        assertEquals(
            Path.of("/work/project/src/main"),
            CdCommand.resolveTarget("""src\main""", base, home, isWindows = false),
        )
    }
}
