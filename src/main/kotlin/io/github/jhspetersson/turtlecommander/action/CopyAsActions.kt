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
    val contents = StringSelection(text)
    // Windows OpenClipboard rejects with IllegalStateException ("cannot open
    // system clipboard") whenever another process — clipboard manager,
    // password manager, browser — holds the clipboard at that instant. The
    // contention window is typically <100ms, so a short backoff loop turns
    // a hard failure into a silent retry. Same situation also makes the
    // CopyAs unit tests flaky on dev machines.
    var lastError: IllegalStateException? = null
    var sleep = 20L
    repeat(5) {
        try {
            clipboard.setContents(contents, null)
            return
        } catch (e: IllegalStateException) {
            lastError = e
            try { Thread.sleep(sleep) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            sleep *= 2
        }
    }
    // Final attempt — let the exception surface if it still fails so callers
    // can log or notify rather than silently dropping the user's copy.
    if (lastError != null) {
        clipboard.setContents(contents, null)
    }
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
