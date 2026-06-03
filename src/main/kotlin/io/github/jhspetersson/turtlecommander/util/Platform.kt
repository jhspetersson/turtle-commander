package io.github.jhspetersson.turtlecommander.util

/**
 * Single source of truth for host-OS detection. The `os.name` system property
 * never changes during a JVM run, so it is read once here instead of being
 * re-parsed at every call site.
 */
object Platform {
    private val osName: String = System.getProperty("os.name").lowercase()

    val isWindows: Boolean = osName.contains("win")
    val isMac: Boolean = osName.contains("mac")
    val isLinux: Boolean = !isWindows && !isMac
}
