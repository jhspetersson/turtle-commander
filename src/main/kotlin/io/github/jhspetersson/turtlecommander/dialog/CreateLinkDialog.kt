package io.github.jhspetersson.turtlecommander.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.ui.installStandardContextMenu
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.swing.*

/**
 * Dual-pane "create link" dialog: the active panel's [sources] become links in the
 * opposite panel's [destination] directory. For a single source the link path (location + name)
 * is editable so the link can be renamed; for several, the field is the location directory and
 * each link keeps its source's name. The **Link type** choice selects a symbolic or a hard link.
 * For symbolic links the **Points to** choice controls whether each link stores an absolute target
 * or one relative to the link's own directory (relative makes the link portable when source and
 * destination live on the same root); hard links always reference the existing file directly, so
 * that choice does not apply to them.
 */
class CreateLinkDialog(
    project: Project,
    private val sources: List<FileEntry>,
    private val destination: Path,
    destinationDisplayPath: String = destination.toString(),
) : DialogWrapper(project) {

    data class LinkSpec(val link: Path, val target: Path, val hard: Boolean = false)

    /** Computed in [doOKAction]; the links to create. */
    var specs: List<LinkSpec> = emptyList()
        private set

    private val single = sources.size == 1

    private val linkField = JBTextField(
        if (single) destination.resolve(sources[0].name).toString() else destinationDisplayPath,
    )

    private val symbolicRadio = JRadioButton("Symbolic link")
    private val hardRadio = JRadioButton("Hard link")

    private val absoluteRadio = JRadioButton("Absolute path")
    private val relativeRadio = JRadioButton("Relative to link")

    init {
        title = "Create Link"
        setOKButtonText("Create")
        symbolicRadio.isSelected = true
        ButtonGroup().apply {
            add(symbolicRadio)
            add(hardRadio)
        }
        // Default to relative only when every source shares the destination's root; relativize
        // across roots (different drives) is impossible, so fall back to absolute there.
        val sameRoot = sources.all { it.path.root == destination.root }
        relativeRadio.isSelected = sameRoot
        absoluteRadio.isSelected = !sameRoot
        ButtonGroup().apply {
            add(absoluteRadio)
            add(relativeRadio)
        }
        // The absolute/relative distinction is meaningless for hard links — they always point at
        // the existing file's data — so grey it out unless a symbolic link is selected.
        val syncPointsTo = {
            absoluteRadio.isEnabled = symbolicRadio.isSelected
            relativeRadio.isEnabled = symbolicRadio.isSelected
        }
        symbolicRadio.addItemListener { syncPointsTo() }
        syncPointsTo()
        linkField.installStandardContextMenu()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(560, if (single) 185 else 255)
        }

        val sourceLabel = if (single) {
            "Link to \"${sources[0].name}\":"
        } else {
            "Link to ${sources.size} items in:"
        }
        panel.add(JBLabel(sourceLabel).apply { alignmentX = JComponent.LEFT_ALIGNMENT })

        if (!single) {
            val textArea = JTextArea(sources.joinToString("\n") { it.name }).apply {
                isEditable = false
                rows = minOf(sources.size, 8)
            }
            panel.add(JScrollPane(textArea).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        }

        panel.add(Box.createVerticalStrut(8))
        panel.add(JBLabel(if (single) "Link path:" else "Location:").apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        panel.add(linkField.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        })

        panel.add(Box.createVerticalStrut(8))
        val typeRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("Link type:"))
            add(Box.createHorizontalStrut(8))
            add(symbolicRadio)
            add(Box.createHorizontalStrut(8))
            add(hardRadio)
        }
        panel.add(typeRow)

        panel.add(Box.createVerticalStrut(8))
        val pointsRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("Points to:"))
            add(Box.createHorizontalStrut(8))
            add(absoluteRadio)
            add(Box.createHorizontalStrut(8))
            add(relativeRadio)
        }
        panel.add(pointsRow)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = linkField

    override fun doOKAction() {
        val text = linkField.text.trim()
        if (text.isEmpty()) {
            error("Link path cannot be empty.")
            return
        }
        val entered = try {
            Path.of(text)
        } catch (e: InvalidPathException) {
            error("Invalid path: ${e.message}")
            return
        }
        val resolved = if (entered.isAbsolute) entered.normalize() else destination.resolve(entered).normalize()

        val hard = hardRadio.isSelected
        // Hard links cannot reference directories — reject up front rather than failing per-item.
        if (hard) {
            val dirs = sources.filter { it.isDirectory }
            if (dirs.isNotEmpty()) {
                error("Hard links cannot point to directories:\n${dirs.joinToString("\n") { it.name }}")
                return
            }
        }

        val computed = if (single) {
            val linkName = resolved.fileName
            if (linkName == null) {
                error("Link path must include a name.")
                return
            }
            listOf(LinkSpec(resolved, targetFor(sources[0].path, resolved.parent ?: destination, hard), hard))
        } else {
            sources.map { src -> LinkSpec(resolved.resolve(src.name), targetFor(src.path, resolved, hard), hard) }
        }

        // Refuse to clobber anything already at a link path (NOFOLLOW so a link itself counts).
        computed.firstOrNull { Files.exists(it.link, LinkOption.NOFOLLOW_LINKS) }?.let {
            error("Already exists:\n${it.link}")
            return
        }

        specs = computed
        super.doOKAction()
    }

    /**
     * The link's target. Hard links always reference the existing file by absolute path; for
     * symbolic links it is absolute or relative to the link's own directory per the user's choice.
     */
    private fun targetFor(source: Path, linkDir: Path, hard: Boolean): Path {
        val absolute = source.toAbsolutePath().normalize()
        if (hard || absoluteRadio.isSelected) return absolute
        return try {
            linkDir.toAbsolutePath().normalize().relativize(absolute)
        } catch (_: IllegalArgumentException) {
            absolute
        }
    }

    private fun error(message: String) {
        Messages.showErrorDialog(contentPanel, message, "Create Link")
    }
}
