package io.github.jhspetersson.turtlecommander.util

import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.ShellAPI
import java.nio.file.Path

/**
 * Invokes the Windows Shell "properties" verb on a file or directory, which
 * pops up the same modeless properties dialog Explorer shows. JNA's Shell32
 * binding is bundled with the IntelliJ Platform (`util-8.jar`), so we don't
 * pull a new dependency.
 *
 * The "properties" verb requires SEE_MASK_INVOKEIDLIST — without it, Windows
 * silently does nothing because the verb is implemented as an IDLIST command
 * rather than a registered shell-execute verb.
 */
object WindowsProperties {

    private const val SEE_MASK_INVOKEIDLIST = 0x0000000C
    private const val SW_SHOW = 5

    fun isSupported(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    /**
     * Opens the Properties dialog for [path]. The dialog runs on its own
     * shell-owned thread, so this returns immediately. Returns false if the
     * call fails (the caller can surface a notification); never throws.
     */
    fun showProperties(path: Path): Boolean {
        return isSupported() && try {
            val info = ShellAPI.SHELLEXECUTEINFO()
            info.cbSize = info.size()
            info.fMask = SEE_MASK_INVOKEIDLIST
            info.lpVerb = "properties"
            info.lpFile = path.toAbsolutePath().toString()
            info.nShow = SW_SHOW
            Shell32.INSTANCE.ShellExecuteEx(info)
        } catch (_: Throwable) {
            // UnsatisfiedLinkError on a non-Windows JVM, or any other native
            // call failure — treat as "not supported here" and let the caller
            // decide whether to notify.
            false
        }
    }
}
