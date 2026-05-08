package io.github.jhspetersson.turtlecommander.util

import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.ShellAPI
import java.nio.file.Path

/**
 * Native "Properties / Get Info" dialog bridge.
 *
 * - **Windows**: JNA `Shell32.ShellExecuteEx` with the `"properties"` verb.
 *   `SEE_MASK_INVOKEIDLIST` is required: the verb is implemented as an IDLIST
 *   command, not a registered shell-execute verb, so without the flag Windows
 *   silently does nothing.
 * - **macOS**: AppleScript `open information window of (POSIX file …)` run
 *   through `osascript`. Spawns one short-lived process per call. The Get
 *   Info window is owned by Finder, which we `activate` so the window comes
 *   to the front instead of opening behind the IDE.
 * - **Linux**: not implemented. There's no DE-independent equivalent —
 *   GNOME Files exposes `org.freedesktop.FileManager1.ShowItemProperties`
 *   over D-Bus, KDE has `kioclient5 properties`, but neither is universal.
 */
object NativeProperties {

    private const val SEE_MASK_INVOKEIDLIST = 0x0000000C
    private const val SW_SHOW = 5

    private val osName = System.getProperty("os.name").lowercase()
    private val isWindows = osName.contains("win")
    private val isMac = osName.contains("mac")

    fun isSupported(): Boolean = isWindows || isMac

    /**
     * Opens the platform Properties / Get Info dialog for [path]. The dialog
     * runs on its own owner-process thread (Shell on Windows, Finder on
     * macOS), so this returns immediately. Returns `false` if the platform
     * is unsupported or the call fails; never throws.
     */
    fun showProperties(path: Path): Boolean = when {
        isWindows -> showOnWindows(path)
        isMac -> showOnMac(path)
        else -> false
    }

    private fun showOnWindows(path: Path): Boolean {
        return try {
            val info = ShellAPI.SHELLEXECUTEINFO()
            info.cbSize = info.size()
            info.fMask = SEE_MASK_INVOKEIDLIST
            info.lpVerb = "properties"
            info.lpFile = path.toAbsolutePath().toString()
            info.nShow = SW_SHOW
            Shell32.INSTANCE.ShellExecuteEx(info)
        } catch (_: Throwable) {
            // UnsatisfiedLinkError on a non-Windows JVM, or any other native
            // call failure — treat as a silent no-op.
            false
        }
    }

    private fun showOnMac(path: Path): Boolean {
        // The path is passed as an argv parameter rather than spliced into
        // the script body. Filenames legally contain double-quotes on macOS,
        // which would otherwise need AppleScript-string escaping. The `--`
        // ends osascript options so a filename starting with `-` isn't
        // mistaken for a flag.
        return try {
            ProcessBuilder(
                "osascript",
                "-e", "on run argv",
                "-e",   "tell application \"Finder\"",
                "-e",     "activate",
                "-e",     "open information window of (POSIX file (item 1 of argv))",
                "-e",   "end tell",
                "-e", "end run",
                "--",
                path.toAbsolutePath().toString(),
            ).start()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
