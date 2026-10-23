package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class OpenInTurtleCommanderMenuTest : BasePlatformTestCase() {

    private val actionManager: ActionManager get() = ActionManager.getInstance()

    private fun childIds(groupId: String): List<String> {
        val group = actionManager.getAction(groupId) as? DefaultActionGroup
            ?: error("group $groupId is not a DefaultActionGroup")
        return group.getChildActionsOrStubs().mapNotNull { actionManager.getId(it) }
    }

    fun testDeclaredGroupsContainTheAction() {
        val groups = listOf(
            "RevealGroup",
            "UsageView.Popup",
            "FindInFiles.Results.ContextMenu",
            "ChangesViewPopupMenu",
            "Vcs.RepositoryChangesBrowserMenu",
            "Vcs.FileHistory.ContextMenu",
            "ShelvedChangesPopupMenu",
            "Git.Stage.Tree.Menu",
            "Images.EditorPopupMenu",
        )
        for (groupId in groups) {
            assertTrue(groupId, OpenInTurtleCommanderMenuInstaller.ACTION_ID in childIds(groupId))
        }
    }

    fun testInstallerAddsActionOnceToCodeRegisteredGroups() {
        OpenInTurtleCommanderMenuInstaller.install(actionManager)
        OpenInTurtleCommanderMenuInstaller.install(actionManager)
        for (groupId in OpenInTurtleCommanderMenuInstaller.GROUP_IDS) {
            assertEquals(groupId, 1, childIds(groupId).count { it == OpenInTurtleCommanderMenuInstaller.ACTION_ID })
        }
    }
}
