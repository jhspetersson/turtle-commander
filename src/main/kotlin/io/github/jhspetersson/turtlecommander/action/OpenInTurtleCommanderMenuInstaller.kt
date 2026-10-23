package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class OpenInTurtleCommanderMenuInstaller : ProjectActivity {
    override suspend fun execute(project: Project) {
        install(ActionManager.getInstance())
    }

    companion object {
        const val ACTION_ID = "TurtleCommander.OpenInTurtleCommander"

        val GROUP_IDS: List<String> = listOf(
            "Bookmarks.ToolWindow.PopupMenu",
            "ProblemsView.ToolWindow.TreePopup",
        )

        fun install(actionManager: ActionManager) {
            val action = actionManager.getAction(ACTION_ID) ?: return
            for (groupId in GROUP_IDS) {
                val group = actionManager.getAction(groupId) as? DefaultActionGroup ?: continue
                group.getChildActionsOrStubs()
                    .filter { it !== action && it.javaClass.name == action.javaClass.name }
                    .forEach(group::remove)
                if (!group.containsAction(action)) {
                    group.add(action, Constraints.LAST)
                }
            }
        }
    }
}
