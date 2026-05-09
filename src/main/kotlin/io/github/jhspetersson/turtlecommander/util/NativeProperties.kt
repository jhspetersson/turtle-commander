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
 *   through `osascript`. The Get Info window is owned by Finder, which we
 *   `activate` so the window comes to the front instead of opening behind the
 *   IDE.
 * - **Linux**: dispatch by `XDG_CURRENT_DESKTOP`. KDE → `kioclient6 properties`
 *   (with `kioclient5` fallback). GNOME-like (GNOME, Unity, Cinnamon, MATE,
 *   Pantheon, Budgie) → `gdbus call …
 *   org.freedesktop.FileManager1.ShowItemProperties` against Nautilus / Files
 *   / Nemo. Anything else returns false; the caller is expected to fall back
 *   to a Swing dialog.
 */
object NativeProperties {

    private const val SEE_MASK_INVOKEIDLIST = 0x0000000C
    private const val SW_SHOW = 5

    private val osName = System.getProperty("os.name").lowercase()
    private val isWindows = osName.contains("win")
    private val isMac = osName.contains("mac")
    private val isLinux = !isWindows && !isMac &&
        (osName.contains("linux") || osName.contains("nix") || osName.contains("bsd"))

    /**
     * Opens the platform Properties / Get Info dialog for [path]. The dialog
     * runs on its owner-process thread (Shell on Windows, Finder on macOS,
     * Nautilus / Dolphin on Linux), so this returns immediately. Returns
     * `false` if the platform / DE has no native dialog or the call fails;
     * never throws.
     */
    fun showProperties(path: Path): Boolean = when {
        isWindows -> showOnWindows(path)
        isMac -> showOnMac(path)
        isLinux -> showOnLinux(path)
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
        //
        // The `as alias` coercion is load-bearing: `POSIX file "..."` returns
        // a «class furl» (file URL specifier) that Finder won't accept as a
        // Finder item, so `open information window of <furl>` silently
        // degrades to the generic `open` verb — which just reveals the file
        // in a Finder window without showing Get Info. Coercing to «class
        // alis» (alias) gives Finder a real item reference and the Get Info
        // window opens as expected.
        return try {
            ProcessBuilder(
                "osascript",
                "-e", "on run argv",
                "-e",   "set theItem to (POSIX file (item 1 of argv)) as alias",
                "-e",   "tell application \"Finder\"",
                "-e",     "activate",
                "-e",     "open information window of theItem",
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

    private fun showOnLinux(path: Path): Boolean {
        val desktop = currentDesktop()
        return when {
            desktop.isKde -> tryKde(path)
            desktop.isGnomeLike -> tryGnome(path)
            else -> false
        }
    }

    private fun tryKde(path: Path): Boolean {
        // KDE 6 ships kioclient6, KDE 5 ships kioclient5. Try the newer one
        // first. ProcessBuilder.start() throws IOException synchronously when
        // the binary isn't on PATH, so we can detect "missing" without waiting.
        val uri = path.toUri().toString()
        for (cmd in listOf("kioclient6", "kioclient5")) {
            try {
                ProcessBuilder(cmd, "properties", uri).start()
                return true
            } catch (_: Throwable) {
                // Try the next variant.
            }
        }
        return false
    }

    private fun tryGnome(path: Path): Boolean {
        // org.freedesktop.FileManager1 is the standardised D-Bus interface
        // implemented by Nautilus, Nemo, and Caja. ShowItemProperties takes
        // an array of URIs and a startup id (we pass an empty string).
        val uri = path.toUri().toString()
        return try {
            ProcessBuilder(
                "gdbus", "call", "--session",
                "--dest", "org.freedesktop.FileManager1",
                "--object-path", "/org/freedesktop/FileManager1",
                "--method", "org.freedesktop.FileManager1.ShowItemProperties",
                "['$uri']", "",
            ).start()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun currentDesktop(): LinuxDesktop {
        val xdg = (System.getenv("XDG_CURRENT_DESKTOP") ?: "").uppercase()
        return LinuxDesktop(
            isKde = xdg.contains("KDE"),
            // GNOME-like = anything implementing org.freedesktop.FileManager1
            // via Nautilus or one of its forks (Nemo for Cinnamon, Caja for
            // MATE). Pantheon and Budgie use Files (Nautilus) directly.
            isGnomeLike = xdg.split(':').any { token ->
                token in setOf("GNOME", "UNITY", "CINNAMON", "X-CINNAMON",
                    "MATE", "PANTHEON", "BUDGIE", "GNOME-CLASSIC")
            },
        )
    }

    private data class LinuxDesktop(val isKde: Boolean, val isGnomeLike: Boolean)
}
