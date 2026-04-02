package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import io.github.jhspetersson.turtlecommander.model.FileEntry
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.getDisplayPath
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private fun copyToClipboard(text: String) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(StringSelection(text), null)
}

private fun resolveEntry(): FileEntry? = FileContextMenuState.clickedEntry

private fun resolveTab(e: AnActionEvent): FileTab? =
    FileContextMenuState.clickedTab ?: findActiveTab(e)

// --- File/directory context menu actions ---

class CopyAsNameAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = resolveEntry()
        e.presentation.isEnabled = entry != null && !entry.isParentLink
        e.presentation.text = if (entry?.isDirectory == true) "Directory Name" else "Filename"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = resolveEntry() ?: return
        copyToClipboard(entry.name)
    }
}

class CopyAsFullPathAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = resolveEntry()
        e.presentation.isEnabled = entry != null && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = resolveEntry() ?: return
        val tab = resolveTab(e)
        if (tab != null && tab.isInsideArchive) {
            // For VFS entries, build the full display path + entry name
            val displayPath = tab.getDisplayPath()
            val separator = if (displayPath.contains("\\")) "\\" else "/"
            copyToClipboard(displayPath + separator + entry.name)
        } else {
            copyToClipboard(entry.path.toString())
        }
    }
}

class CopyAsParentPathAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = resolveEntry()
        e.presentation.isEnabled = entry != null && !entry.isParentLink
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = resolveEntry() ?: return
        val tab = resolveTab(e)
        if (tab != null && tab.isInsideArchive) {
            copyToClipboard(tab.getDisplayPath())
        } else {
            copyToClipboard(entry.path.parent?.toString() ?: "")
        }
    }
}

// --- Tab context menu actions ---

class TabCopyAsNameAction : TabContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        val tab = panel?.getTabAt(tabIndex) ?: return
        val name = tab.currentPath.fileName?.toString() ?: tab.currentPath.toString()
        copyToClipboard(name)
    }
}

class TabCopyAsFullPathAction : TabContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        val tab = panel?.getTabAt(tabIndex) ?: return
        if (tab.isInsideArchive) {
            copyToClipboard(tab.getDisplayPath())
        } else {
            copyToClipboard(tab.currentPath.toString())
        }
    }
}

class TabCopyAsParentPathAction : TabContextAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val (panel, tabIndex) = resolveTabContext(e)
        val tab = panel?.getTabAt(tabIndex) ?: return
        if (tab.isInsideArchive) {
            copyToClipboard(tab.realFilesystemPath.toString())
        } else {
            copyToClipboard(tab.currentPath.parent?.toString() ?: "")
        }
    }
}

// --- Search results context menu actions ---

class SearchCopyAsNameAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val entry = SearchContextMenuState.clickedEntry
        e.presentation.isEnabled = entry != null
        e.presentation.text = if (entry?.isDirectory == true) "Directory Name" else "Filename"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = SearchContextMenuState.clickedEntry ?: return
        copyToClipboard(entry.name)
    }
}

class SearchCopyAsFullPathAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = SearchContextMenuState.clickedEntry != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = SearchContextMenuState.clickedEntry ?: return
        copyToClipboard(entry.path.toString())
    }
}

class SearchCopyAsParentPathAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = SearchContextMenuState.clickedEntry != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val entry = SearchContextMenuState.clickedEntry ?: return
        copyToClipboard(entry.path.parent?.toString() ?: "")
    }
}

object SearchContextMenuState {
    var clickedEntry: FileEntry? = null
}
