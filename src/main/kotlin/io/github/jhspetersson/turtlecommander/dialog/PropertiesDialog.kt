package io.github.jhspetersson.turtlecommander.dialog

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.util.formatSize
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Cross-platform fallback Properties dialog. Shown when no native dialog is
 * available — unknown Linux DE, or any host where the path isn't a real
 * filesystem entry (the inside-archive case). Read-only by design: it
 * mirrors what Windows' classic Properties dialog shows on the General /
 * Permissions tabs and nothing more.
 *
 * For directories the recursive size + file/folder count is computed on a
 * pooled thread; the label is updated when the walk finishes (or stays at
 * "Calculating…" if the dialog is closed first — the [cancelled] flag
 * short-circuits the visitor).
 */
class PropertiesDialog(
    project: Project?,
    private val entry: FileEntry,
) : DialogWrapper(project) {

    private val sizeLabel = JBLabel(initialSizeText())
    private val cancelled = AtomicBoolean(false)

    init {
        title = "Properties"
        init()
        if (entry.isDirectory) {
            ApplicationManager.getApplication().executeOnPooledThread { computeAndShowDirSize() }
        }
    }

    override fun dispose() {
        cancelled.set(true)
        super.dispose()
    }

    /** Single Close button — this dialog is read-only. */
    override fun createActions(): Array<Action> = arrayOf(okAction.also { it.putValue(Action.NAME, "Close") })

    override fun createCenterPanel(): JComponent {
        val tabs = JBTabbedPane().apply {
            add("General", generalTab())
            add("Permissions", permissionsTab())
        }
        return JPanel(BorderLayout(0, 12)).apply {
            preferredSize = Dimension(460, 320)
            border = JBUI.Borders.empty(8, 4, 0, 4)
            add(headerPanel(), BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
    }

    private fun headerPanel(): JComponent {
        val icon = if (entry.isDirectory) AllIcons.Nodes.Folder
        else FileTypeManager.getInstance().getFileTypeByFileName(entry.name).icon
            ?: AllIcons.FileTypes.Any_type
        val name = JBLabel(entry.name).apply {
            this.icon = icon
            iconTextGap = 8
            font = font.deriveFont(Font.BOLD, font.size2D + 2f)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 4, 8, 0)
            add(name, BorderLayout.WEST)
        }
    }

    private fun generalTab(): JComponent {
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Type:", JBLabel(typeDescription()))
            .addLabeledComponent("Location:", JBLabel(locationText()))
            .addLabeledComponent("Size:", sizeLabel)
            .addLabeledComponent("Created:", JBLabel(formatTime(entry.creationTime?.toMillis())))
            .addLabeledComponent("Modified:", JBLabel(formatTime(entry.lastModified?.toMillis())))
        readAccessTime(entry.path)?.let { millis ->
            builder.addLabeledComponent("Accessed:", JBLabel(formatTime(millis)))
        }
        return wrap(builder.panel)
    }

    private fun permissionsTab(): JComponent {
        val builder = FormBuilder.createFormBuilder()
        if (entry.owner.isNotEmpty()) builder.addLabeledComponent("Owner:", JBLabel(entry.owner))
        if (entry.group.isNotEmpty()) builder.addLabeledComponent("Group:", JBLabel(entry.group))
        builder.addLabeledComponent("Permissions:", JBLabel(entry.permissions.ifEmpty { "-" }))
        return wrap(builder.panel)
    }

    private fun wrap(panel: JComponent): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)
        add(panel, BorderLayout.NORTH)
    }

    private fun typeDescription(): String {
        if (entry.isDirectory) return "Directory"
        val type = FileTypeManager.getInstance().getFileTypeByFileName(entry.name)
        // UnknownFileType.description is "Unknown" — not very informative.
        // Synthesize a name from the extension instead.
        if (type.name.equals("UNKNOWN", ignoreCase = true)) {
            val ext = entry.name.substringAfterLast('.', "")
            return if (ext.isNotEmpty()) "${ext.uppercase()} File" else "File"
        }
        return type.description.ifEmpty { type.name }
    }

    private fun locationText(): String =
        entry.path.parent?.toAbsolutePath()?.toString() ?: entry.path.toAbsolutePath().toString()

    private fun initialSizeText(): String =
        if (entry.isDirectory) "Calculating…"
        else "${formatSize(entry.size)} (${"%,d".format(entry.size)} bytes)"

    private fun computeAndShowDirSize() {
        var bytes = 0L
        var files = 0L
        var dirs = 0L
        try {
            Files.walkFileTree(entry.path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled.get()) return FileVisitResult.TERMINATE
                    files++
                    bytes += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled.get()) return FileVisitResult.TERMINATE
                    if (dir != entry.path) dirs++
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
            })
        } catch (_: Exception) {
            // Permission denied on the root, or path no longer exists — show
            // what we have so far.
        }
        if (cancelled.get()) return
        val text = "${formatSize(bytes)} (${"%,d".format(bytes)} bytes), $files files, $dirs folders"
        SwingUtilities.invokeLater {
            if (!cancelled.get()) sizeLabel.text = text
        }
    }

    private fun readAccessTime(path: Path): Long? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java).lastAccessTime().toMillis()
    } catch (_: Exception) {
        null
    }

    private fun formatTime(millis: Long?): String {
        if (millis == null || millis <= 0) return "-"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(millis))
    }
}
