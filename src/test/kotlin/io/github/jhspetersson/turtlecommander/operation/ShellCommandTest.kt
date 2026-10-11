package io.github.jhspetersson.turtlecommander.operation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ShellCommandTest {

    private val local = Path.of("C:\\work\\dir")
    private val unc = Path.of("\\\\server\\share\\dir")

    @Test
    fun `windows local directory runs through cmd in that directory`() {
        val invocation = ShellCommand.build("dir /b", local, isWindows = true)
        assertEquals("cmd.exe", invocation.exePath)
        assertEquals(listOf("/c", "dir /b"), invocation.args)
        assertEquals(local, invocation.workingDirectory)
    }

    @Test
    fun `windows unc directory is entered with pushd and no process working directory`() {
        val invocation = ShellCommand.build("dir /b", unc, isWindows = true)
        assertEquals("cmd.exe", invocation.exePath)
        assertEquals(listOf("/c", "pushd \"\\\\server\\share\\dir\" && dir /b"), invocation.args)
        assertNull(invocation.workingDirectory)
    }

    @Test
    fun `windows bare shell is launched in its own window`() {
        val invocation = ShellCommand.build("PowerShell", local, isWindows = true)
        assertEquals(listOf("/c", "start \"\" PowerShell"), invocation.args)
        assertEquals(local, invocation.workingDirectory)
    }

    @Test
    fun `windows bare shell on unc directory still pushes the directory first`() {
        val invocation = ShellCommand.build("cmd", unc, isWindows = true)
        assertEquals(listOf("/c", "pushd \"\\\\server\\share\\dir\" && start \"\" cmd"), invocation.args)
        assertNull(invocation.workingDirectory)
    }

    @Test
    fun `unix runs through sh in the directory`() {
        val dir = Path.of("/home/user/dir")
        val invocation = ShellCommand.build("ls -la", dir, isWindows = false)
        assertEquals("/bin/sh", invocation.exePath)
        assertEquals(listOf("-c", "ls -la"), invocation.args)
        assertEquals(dir, invocation.workingDirectory)
    }

    @Test
    fun `unix never treats a leading double backslash as unc`() {
        val dir = Path.of("\\\\odd\\name")
        val invocation = ShellCommand.build("ls", dir, isWindows = false)
        assertEquals(listOf("-c", "ls"), invocation.args)
        assertEquals(dir, invocation.workingDirectory)
    }

    private var lastExitCode = -1

    private fun runAndRead(invocation: ShellInvocation): String {
        val builder = ProcessBuilder(listOf(invocation.exePath) + invocation.args).redirectErrorStream(true)
        invocation.workingDirectory?.let { builder.directory(it.toFile()) }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        lastExitCode = process.waitFor()
        return output
    }

    @Test
    fun `live cmd keeps quotes typed in the command`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val dir = Path.of(System.getProperty("java.io.tmpdir"))
        val output = runAndRead(ShellCommand.build("echo \"a b\" && echo c", dir, isWindows = true))
        assertEquals(0, lastExitCode)
        assertEquals(listOf("\"a b\"", "c"), output.trim().lines().map { it.trim() })
    }

    @Test
    fun `live cmd on a unc share reports the share as its current directory`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val share = Path.of("\\\\localhost\\C$\\Windows")
        assumeTrue(Files.isDirectory(share))
        val invocation = ShellCommand.build("cd", share, isWindows = true)
        val output = runAndRead(invocation)
        assertEquals(0, lastExitCode)
        assertFalse(output, output.contains("UNC paths are not supported"))
        assertTrue(output, output.trim().endsWith("\\Windows"))
        assertFalse(output, output.trim().equals("C:\\Windows", ignoreCase = true))
    }
}
