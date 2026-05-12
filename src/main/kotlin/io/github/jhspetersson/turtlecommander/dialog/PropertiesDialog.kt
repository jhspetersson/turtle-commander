package io.github.jhspetersson.turtlecommander.dialog

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.ui.installStandardContextMenu
import io.github.jhspetersson.turtlecommander.util.DateTimeFormatters
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
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Cross-platform fallback Properties dialog. Shown when no native dialog is
 * available — unknown Linux DE, or any host where the path isn't a real
 * filesystem entry (the inside-archive case). Read-only by design.
 *
 * All values are rendered as transparent, non-editable [JBTextField]s so the
 * user can select text and copy it (Ctrl+C, or right-click → Copy via
 * [installStandardContextMenu]). Visually they read as labels — no border, no
 * filled background.
 *
 * For directories the recursive size + file/folder count is computed on a
 * pooled thread; the field is updated when the walk finishes (or stays at
 * "Calculating…" if the dialog is closed first — the [cancelled] flag
 * short-circuits the visitor).
 */
class PropertiesDialog(
    project: Project?,
    private val entry: FileEntry,
) : DialogWrapper(project) {

    private val sizeField = copyableField(initialSizeText())
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
    override fun createActions(): Array<Action> =
        arrayOf(okAction.also { it.putValue(Action.NAME, "Close") })

    override fun createCenterPanel(): JComponent {
        val accessTime = readAccessTime(entry.path)
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Type:", copyableField(typeDescription()))
            .addLabeledComponent("Path:", copyableField(entry.path.toAbsolutePath().toString()))
            .addLabeledComponent("Location:", copyableField(locationText()))
            .addLabeledComponent("Size:", sizeField)
            .addLabeledComponent("Created:", copyableField(formatTime(entry.creationTime?.toMillis())))
            .addLabeledComponent("Modified:", copyableField(formatTime(entry.lastModified?.toMillis())))
        if (accessTime != null) {
            builder.addLabeledComponent("Accessed:", copyableField(formatTime(accessTime)))
        }
        builder.addLabeledComponent("Owner:", copyableField(entry.owner.ifEmpty { "-" }))
        builder.addLabeledComponent("Group:", copyableField(entry.group.ifEmpty { "-" }))
        builder.addLabeledComponent("Permissions:", copyableField(entry.permissions.ifEmpty { "-" }))

        return JPanel(BorderLayout(0, 12)).apply {
            preferredSize = Dimension(520, 320)
            border = JBUI.Borders.empty(8, 4, 0, 4)
            add(headerPanel(), BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(8)
                add(builder.panel, BorderLayout.NORTH)
            }, BorderLayout.CENTER)
        }
    }

    private fun headerPanel(): JComponent {
        val icon = if (entry.isDirectory) AllIcons.Nodes.Folder
        else FileTypeManager.getInstance().getFileTypeByFileName(entry.name).icon
            ?: AllIcons.FileTypes.Any_type
        // Filename gets the same copy-friendly treatment, just bold/larger.
        val nameField = copyableField(entry.name).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 2f)
        }
        val iconLabel = JBLabel(icon).apply {
            border = JBUI.Borders.emptyRight(8)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 4, 8, 0)
            add(iconLabel, BorderLayout.WEST)
            add(nameField, BorderLayout.CENTER)
        }
    }

    /**
     * A label-shaped, non-editable, copyable text field. No border, no fill —
     * visually reads as a JBLabel but the text can be selected and copied.
     * The Ctrl+C / right-click → Copy menu come from [installStandardContextMenu].
     */
    private fun copyableField(text: String): JBTextField = JBTextField(text).apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        // The blinking caret on a non-editable field is distracting; hide it.
        caret.isVisible = false
        installStandardContextMenu()
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
            if (!cancelled.get()) sizeField.text = text
        }
    }

    private fun readAccessTime(path: Path): Long? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java).lastAccessTime().toMillis()
    } catch (_: Exception) {
        null
    }

    private fun formatTime(millis: Long?): String {
        if (millis == null || millis <= 0) return "-"
        return DateTimeFormatters.format(millis)
    }
}
