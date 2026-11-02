package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.treeStructure.Tree
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class OpenKeymapSettingsNavigationTest : BasePlatformTestCase() {

    private class KeymapLikeConfigurable : Configurable {
        val tree: Tree = run {
            val root = DefaultMutableTreeNode("Keymap")
            val plugins = DefaultMutableTreeNode("Plugins")
            plugins.add(DefaultMutableTreeNode("Turtle Commander"))
            root.add(plugins)
            Tree(DefaultTreeModel(root))
        }
        private val panel = JPanel().apply { add(tree) }

        override fun createComponent(): JComponent = panel
        override fun getDisplayName(): String = "Keymap"
        override fun isModified(): Boolean = false
        override fun apply() {}
    }

    private fun awaitSelection(tree: Tree): String? {
        val deadline = System.currentTimeMillis() + 5_000
        while (tree.selectionPath == null && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        return tree.selectionPath?.lastPathComponent?.toString()
    }

    fun testEverySettingsDialogNavigatesItsOwnKeymapTree() {
        val action = OpenKeymapSettingsAction()
        val first = KeymapLikeConfigurable()
        action.scheduleTreeNavigation(first)
        assertEquals("Turtle Commander", awaitSelection(first.tree))

        val second = KeymapLikeConfigurable()
        action.scheduleTreeNavigation(second)
        assertEquals("Turtle Commander", awaitSelection(second.tree))
    }
}
