package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import io.github.jhspetersson.turtlecommander.ui.FileTab
import io.github.jhspetersson.turtlecommander.ui.hasAnyDisplayedEntries
import io.github.jhspetersson.turtlecommander.ui.hasAnyDisplayedUnmarked
import io.github.jhspetersson.turtlecommander.ui.hasAnyDisplayedUnmarkedFile
import io.github.jhspetersson.turtlecommander.ui.hasMarkedFileWithFocusedExtension
import io.github.jhspetersson.turtlecommander.ui.hasUnmarkedFileWithFocusedExtension
import io.github.jhspetersson.turtlecommander.ui.invertSelectionEntries
import io.github.jhspetersson.turtlecommander.ui.restoreSavedSelection
import io.github.jhspetersson.turtlecommander.ui.saveCurrentSelection
import io.github.jhspetersson.turtlecommander.ui.selectAllEntries
import io.github.jhspetersson.turtlecommander.ui.selectByMask
import io.github.jhspetersson.turtlecommander.ui.selectSameExtension
import io.github.jhspetersson.turtlecommander.ui.unselectAllEntries
import io.github.jhspetersson.turtlecommander.ui.unselectByMask
import io.github.jhspetersson.turtlecommander.ui.unselectSameExtension

private const val FILE_CONTEXT_PLACE = "TurtleCommander.FileContextMenu"

/**
 * Resolves the tab a Selection action should target.
 *
 * - File context menu: the tab that was right-clicked (cached in [FileContextMenuState]).
 * - Tab context menu (place is [ActionPlaces.POPUP]): the clicked tab from [TabContextMenuState],
 *   which lets users mark entries inside a tab they aren't currently viewing.
 * - Keyboard / anywhere else: the currently active tab.
 *
 * Falling back to [findActiveTab] keeps the action functional even when the cached popup state
 * is somehow missing.
 */
private fun resolveSelectionTab(e: AnActionEvent): FileTab? {
    when (e.place) {
        FILE_CONTEXT_PLACE -> return FileContextMenuState.clickedTab ?: findActiveTab(e)
        ActionPlaces.POPUP -> {
            val panel = TabContextMenuState.clickedPanel
            val index = TabContextMenuState.clickedTabIndex
            if (panel != null && index >= 0) {
                panel.getTabAt(index)?.let { return it }
            }
            return findActiveTab(e)
        }
    }
    return findActiveTab(e)
}

private fun isInvokedFromContextMenu(place: String): Boolean {
    // ActionPlaces.POPUP is what FileManagerPanel uses for the tab context menu.
    return place == FILE_CONTEXT_PLACE || place == ActionPlaces.POPUP
}

abstract class SelectionAction : EdtAction() {
    override fun update(e: AnActionEvent) {
        val tab = resolveSelectionTab(e)
        if (tab == null) {
            e.presentation.isEnabled = false
            return
        }
        // Keyboard shortcuts must require panel focus to avoid clobbering globally-bound keys
        // like Ctrl+A in the editor. Popup invocations bypass the focus check since the popup
        // itself owns focus once it opens.
        e.presentation.isEnabled = tab.hasAnyViewFocus() || isInvokedFromContextMenu(e.place)
    }

    protected fun targetTab(e: AnActionEvent): FileTab? = resolveSelectionTab(e)
}

class SelectAllAction : SelectionAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.presentation.isEnabled) {
            e.presentation.isEnabled = targetTab(e)?.hasAnyDisplayedUnmarked() == true
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        targetTab(e)?.selectAllEntries()
    }
}

class UnselectAllAction : SelectionAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.presentation.isEnabled) {
            e.presentation.isEnabled = targetTab(e)?.markedPaths?.isNotEmpty() == true
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        targetTab(e)?.unselectAllEntries()
    }
}

class InvertSelectionAction : SelectionAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.presentation.isEnabled) {
            e.presentation.isEnabled = targetTab(e)?.hasAnyDisplayedEntries() == true
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        targetTab(e)?.invertSelectionEntries()
    }
}

class SelectByPatternAction : SelectionAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.presentation.isEnabled) {
            e.presentation.isEnabled = targetTab(e)?.hasAnyDisplayedUnmarkedFile() == true
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val tab = targetTab(e) ?: return
        val mask = Messages.showInputDialog(
            e.project,
            "Mask (e.g. *.txt, separate multiple patterns with ,):",
            "Select Files",
            Messages.getQuestionIcon(),
            "*.*",
            null,
        ) ?: return
        if (mask.isBlank()) return
        tab.selectByMask(mask.trim())
    }
}

class UnselectByPatternAction : SelectionAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.presentation.isEnabled) {
            e.presentation.isEnabled = targetTab(e)?.markedPaths?.isNotEmpty() == true
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val tab = targetTab(e) ?: return
        val mask = Messages.showInputDialog(
            e.project,
            "Mask (e.g. *.txt, separate multiple patterns with ,):",
            "Unselect Files",
            Messages.getQuestionIcon(),
            "*.*",
            null,
        ) ?: return
        if (mask.isBlank()) return
        tab.unselectByMask(mask.trim())
    }
}

class SelectSameExtensionAction : SelectionAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.presentation.isEnabled) {
            e.presentation.isEnabled = targetTab(e)?.hasUnmarkedFileWithFocusedExtension() == true
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        targetTab(e)?.selectSameExtension()
    }
}

class UnselectSameExtensionAction : SelectionAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.presentation.isEnabled) {
            e.presentation.isEnabled = targetTab(e)?.hasMarkedFileWithFocusedExtension() == true
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        targetTab(e)?.unselectSameExtension()
    }
}

class SaveSelectionAction : SelectionAction() {
    override fun actionPerformed(e: AnActionEvent) {
        targetTab(e)?.saveCurrentSelection()
    }
}

class RestoreSelectionAction : SelectionAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.presentation.isEnabled) {
            e.presentation.isEnabled = targetTab(e)?.savedMarkedPaths != null
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        targetTab(e)?.restoreSavedSelection()
    }
}
