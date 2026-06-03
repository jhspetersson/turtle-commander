package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import io.github.jhspetersson.turtlecommander.model.FileEntry
import com.intellij.openapi.ide.CopyPasteManager
import java.nio.file.Path

private fun hashableEntry(): FileEntry? {
    val entry = FileContextMenuState.clickedEntry ?: return null
    if (entry.isParentLink || entry.isDirectory) return null
    return entry
}

abstract class CopyHashAction(
    private val label: String,
    private val compute: (Path, ProgressIndicator) -> String,
) : EdtAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = hashableEntry() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = hashableEntry() ?: return
        val project = e.project
        val path = entry.path
        val title = "Computing $label of ${entry.name}"

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var result: String? = null
            private var error: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                try {
                    result = compute(path, indicator)
                } catch (t: Throwable) {
                    error = t
                }
            }

            override fun onSuccess() {
                val value = result
                val err = error
                if (value != null) {
                    CopyPasteManager.copyTextToClipboard(value)
                } else if (err != null) {
                    Messages.showErrorDialog(project, "Failed to compute $label: ${err.message}", "Copy $label")
                }
            }
        })
    }
}

class CopyAsCrc16Action : CopyHashAction("CRC16", HashComputations::crc16)
class CopyAsCrc32Action : CopyHashAction("CRC32", HashComputations::crc32)
class CopyAsMd5Action : CopyHashAction("MD5", { p, i -> HashComputations.digest(p, "MD5", i) })
class CopyAsSha1Action : CopyHashAction("SHA-1", { p, i -> HashComputations.digest(p, "SHA-1", i) })
class CopyAsSha256Action : CopyHashAction("SHA-256", { p, i -> HashComputations.digest(p, "SHA-256", i) })
class CopyAsSha512Action : CopyHashAction("SHA-512", { p, i -> HashComputations.digest(p, "SHA-512", i) })
class CopyAsSha3_256Action : CopyHashAction("SHA3-256", { p, i -> HashComputations.digest(p, "SHA3-256", i) })
class CopyAsSha3_512Action : CopyHashAction("SHA3-512", { p, i -> HashComputations.digest(p, "SHA3-512", i) })
