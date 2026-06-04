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
 * Dual-pane "create symbolic link" dialog: the active panel's [sources] become links in the
 * opposite panel's [destination] directory. For a single source the link path (location + name)
 * is editable so the link can be renamed; for several, the field is the location directory and
 * each link keeps its source's name. The **Points to** choice controls whether each link stores
 * an absolute target or one relative to the link's own directory (relative makes the link
 * portable when source and destination live on the same root).
 */
class CreateSymlinkDialog(
    project: Project,
    private val sources: List<FileEntry>,
    private val destination: Path,
    destinationDisplayPath: String = destination.toString(),
) : DialogWrapper(project) {

    data class SymlinkSpec(val link: Path, val target: Path)

    /** Computed in [doOKAction]; the (link, target) pairs to create. */
    var specs: List<SymlinkSpec> = emptyList()
        private set

    private val single = sources.size == 1

    private val linkField = JBTextField(
        if (single) destination.resolve(sources[0].name).toString() else destinationDisplayPath,
    )

    private val absoluteRadio = JRadioButton("Absolute path")
    private val relativeRadio = JRadioButton("Relative to link")

    init {
        title = "Create Symbolic Link"
        setOKButtonText("Create")
        // Default to relative only when every source shares the destination's root; relativize
        // across roots (different drives) is impossible, so fall back to absolute there.
        val sameRoot = sources.all { it.path.root == destination.root }
        relativeRadio.isSelected = sameRoot
        absoluteRadio.isSelected = !sameRoot
        ButtonGroup().apply {
            add(absoluteRadio)
            add(relativeRadio)
        }
        linkField.installStandardContextMenu()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(560, if (single) 150 else 220)
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

        val computed = if (single) {
            val linkName = resolved.fileName
            if (linkName == null) {
                error("Link path must include a name.")
                return
            }
            listOf(SymlinkSpec(resolved, targetFor(sources[0].path, resolved.parent ?: destination)))
        } else {
            sources.map { src -> SymlinkSpec(resolved.resolve(src.name), targetFor(src.path, resolved)) }
        }

        // Refuse to clobber anything already at a link path (NOFOLLOW so a link itself counts).
        computed.firstOrNull { Files.exists(it.link, LinkOption.NOFOLLOW_LINKS) }?.let {
            error("Already exists:\n${it.link}")
            return
        }

        specs = computed
        super.doOKAction()
    }

    /** Target as stored in the link: absolute, or relative to the link's own directory. */
    private fun targetFor(source: Path, linkDir: Path): Path {
        val absolute = source.toAbsolutePath().normalize()
        if (absoluteRadio.isSelected) return absolute
        return try {
            linkDir.toAbsolutePath().normalize().relativize(absolute)
        } catch (_: IllegalArgumentException) {
            absolute
        }
    }

    private fun error(message: String) {
        Messages.showErrorDialog(contentPanel, message, "Create Symbolic Link")
    }
}
