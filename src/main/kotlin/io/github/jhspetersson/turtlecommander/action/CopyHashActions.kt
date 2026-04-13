package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import io.github.jhspetersson.turtlecommander.model.FileEntry
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32

private fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

private fun hashableEntry(): FileEntry? {
    val entry = FileContextMenuState.clickedEntry ?: return null
    if (entry.isParentLink || entry.isDirectory) return null
    return entry
}

private fun toHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
        sb.append(Character.forDigit(b.toInt() and 0xF, 16))
    }
    return sb.toString()
}

private fun computeCrc16(path: Path, indicator: ProgressIndicator): String {
    val total = runCatching { Files.size(path) }.getOrDefault(-1L)
    var crc = 0xFFFF
    val polynomial = 0x1021
    var processed = 0L
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            indicator.checkCanceled()
            val n = input.read(buffer)
            if (n <= 0) break
            for (i in 0 until n) {
                crc = crc xor ((buffer[i].toInt() and 0xFF) shl 8)
                repeat(8) {
                    crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor polynomial) and 0xFFFF
                    else (crc shl 1) and 0xFFFF
                }
            }
            processed += n
            if (total > 0) indicator.fraction = processed.toDouble() / total
        }
    }
    return "%04X".format(crc and 0xFFFF)
}

private fun computeCrc32(path: Path, indicator: ProgressIndicator): String {
    val total = runCatching { Files.size(path) }.getOrDefault(-1L)
    val crc = CRC32()
    var processed = 0L
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            indicator.checkCanceled()
            val n = input.read(buffer)
            if (n <= 0) break
            crc.update(buffer, 0, n)
            processed += n
            if (total > 0) indicator.fraction = processed.toDouble() / total
        }
    }
    return "%08X".format(crc.value)
}

private fun computeDigest(path: Path, algorithm: String, indicator: ProgressIndicator): String {
    val total = runCatching { Files.size(path) }.getOrDefault(-1L)
    val digest = MessageDigest.getInstance(algorithm)
    var processed = 0L
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            indicator.checkCanceled()
            val n = input.read(buffer)
            if (n <= 0) break
            digest.update(buffer, 0, n)
            processed += n
            if (total > 0) indicator.fraction = processed.toDouble() / total
        }
    }
    return toHex(digest.digest())
}

abstract class CopyHashAction(
    private val label: String,
    private val compute: (Path, ProgressIndicator) -> String,
) : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

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
                    copyToClipboard(value)
                } else if (err != null) {
                    Messages.showErrorDialog(project, "Failed to compute $label: ${err.message}", "Copy $label")
                }
            }
        })
    }
}

class CopyAsCrc16Action : CopyHashAction("CRC16", ::computeCrc16)
class CopyAsCrc32Action : CopyHashAction("CRC32", ::computeCrc32)
class CopyAsMd5Action : CopyHashAction("MD5", { p, i -> computeDigest(p, "MD5", i) })
class CopyAsSha1Action : CopyHashAction("SHA-1", { p, i -> computeDigest(p, "SHA-1", i) })
class CopyAsSha256Action : CopyHashAction("SHA-256", { p, i -> computeDigest(p, "SHA-256", i) })
class CopyAsSha512Action : CopyHashAction("SHA-512", { p, i -> computeDigest(p, "SHA-512", i) })
class CopyAsSha3_256Action : CopyHashAction("SHA3-256", { p, i -> computeDigest(p, "SHA3-256", i) })
class CopyAsSha3_512Action : CopyHashAction("SHA3-512", { p, i -> computeDigest(p, "SHA3-512", i) })
