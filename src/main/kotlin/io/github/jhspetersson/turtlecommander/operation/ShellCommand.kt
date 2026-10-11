package io.github.jhspetersson.turtlecommander.operation

import java.nio.file.Path

data class ShellInvocation(val exePath: String, val args: List<String>, val workingDirectory: Path?)

object ShellCommand {

    private val bareShells = setOf(
        "cmd", "cmd.exe",
        "powershell", "powershell.exe",
        "pwsh", "pwsh.exe",
        "bash", "bash.exe",
        "wsl", "wsl.exe",
    )

    fun build(command: String, workingDir: Path, isWindows: Boolean): ShellInvocation {
        if (!isWindows) return ShellInvocation("/bin/sh", listOf("-c", command), workingDir)
        val body = if (command.lowercase() in bareShells) "start \"\" $command" else command
        val dir = workingDir.toString()
        return if (dir.startsWith("\\\\")) {
            ShellInvocation("cmd.exe", listOf("/c", "pushd \"$dir\" && $body"), null)
        } else {
            ShellInvocation("cmd.exe", listOf("/c", body), workingDir)
        }
    }
}
