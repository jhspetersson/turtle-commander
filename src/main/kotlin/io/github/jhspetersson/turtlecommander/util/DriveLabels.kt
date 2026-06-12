package io.github.jhspetersson.turtlecommander.util
import java.io.File

import com.intellij.openapi.util.SystemInfo
import java.util.concurrent.ConcurrentHashMap
import javax.swing.filechooser.FileSystemView

/**
 * Display labels for drive roots in the drive selector ("C:\" → "Local Disk (C:)"-style).
 *
 * `FileSystemView.getSystemDisplayName` is a Win32 shell call that can block for seconds on
 * disconnected network drives or empty card-reader slots, so results are cached, and the
 * shared drive-roots poller in
 * [io.github.jhspetersson.turtlecommander.service.FileOperationService] warms the cache on
 * an IO thread before any combo renders a root on the EDT.
 */
internal object DriveLabels {
    private val isWindows = SystemInfo.isWindows
    private val labelCache = ConcurrentHashMap<String, String>()

    fun getDisplayText(path: String): String {
        if (!isWindows || path.isEmpty()) return path
        // Only add labels for drive roots (e.g. "C:\")
        if (!path.matches(Regex("[A-Za-z]:\\\\"))) return path
        return labelCache.getOrPut(path) {
            try {
                val label = FileSystemView.getFileSystemView()
                    .getSystemDisplayName(File(path))
                if (label.isNotBlank() && label != path.trimEnd('\\')) label else path
            } catch (_: Exception) {
                path
            }
        }
    }
}
