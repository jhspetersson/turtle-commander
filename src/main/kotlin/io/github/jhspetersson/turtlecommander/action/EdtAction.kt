package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.DumbAware

/**
 * Base class for the plugin's actions. All of them resolve their enabled/visible
 * state from Swing UI state (the focused tab, the right-clicked entry, …), which
 * must be touched on the EDT, so they all share the same update thread and are
 * dumb-aware. Subclassing this removes the repeated boilerplate of declaring
 * `DumbAware` and overriding `getActionUpdateThread()` on every action.
 */
abstract class EdtAction : AnAction, DumbAware {
    constructor() : super()
    constructor(text: String?) : super(text)
    constructor(text: String?, description: String?, icon: javax.swing.Icon?) : super(text, description, icon)

    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
