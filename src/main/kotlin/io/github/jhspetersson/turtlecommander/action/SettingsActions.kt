package io.github.jhspetersson.turtlecommander.action

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

class OpenKeymapSettingsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            { it is SearchableConfigurable && it.id == "preferences.keymap" },
            { configurable ->
                scheduleTreeNavigation(configurable, 0)
            },
        )
    }

    private var cachedComponent: Component? = null

    private fun scheduleTreeNavigation(configurable: Configurable, attempt: Int) {
        if (attempt > 50) return
        val delay = if (attempt == 0) 500 else 200
        Timer(delay, null).apply {
            isRepeats = false
            addActionListener {
                if (cachedComponent == null) {
                    cachedComponent = configurable.createComponent()
                }
                val tree = cachedComponent?.let { findTree(it) }
                if (tree != null && canNavigate(tree, "Plugins", "Turtle Commander")) {
                    // Apply selection multiple times to survive post-initialization resets
                    for (d in listOf(0, 100, 300, 500, 750, 1000)) {
                        Timer(d, null).apply {
                            isRepeats = false
                            addActionListener {
                                expandAndSelect(tree, "Plugins", "Turtle Commander")
                            }
                            start()
                        }
                    }
                } else {
                    scheduleTreeNavigation(configurable, attempt + 1)
                }
            }
            start()
        }
    }

    private fun canNavigate(tree: JTree, vararg path: String): Boolean {
        val model = tree.model
        var current: Any = model.root ?: return false
        for (name in path) {
            val child = findChild(model, current, name) ?: return false
            current = child
        }
        return true
    }

    private fun findChild(model: TreeModel, parent: Any, name: String): Any? {
        for (i in 0 until model.getChildCount(parent)) {
            val child = model.getChild(parent, i)
            if (getNodeName(child) == name) return child
        }
        return null
    }

    private fun getNodeName(node: Any): String {
        val obj = if (node is DefaultMutableTreeNode) node.userObject else node
        obj ?: return ""
        // Try getName() via reflection (Group, QuickList, etc.)
        try {
            val method = obj.javaClass.getMethod("getName")
            val result = method.invoke(obj) as? String
            if (result != null) return result
        } catch (_: Exception) {}
        return obj.toString()
    }

    private fun findTree(component: Component): JTree? {
        if (component is Tree) return component
        if (component is Container) {
            for (child in component.components) {
                val found = findTree(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun expandAndSelect(tree: JTree, vararg path: String) {
        val model = tree.model
        val root = model.root ?: return

        val nodes = mutableListOf(root)
        var current: Any = root
        for (name in path) {
            val child = findChild(model, current, name) ?: return
            nodes.add(child)
            current = child
        }

        // Expand each parent level first
        for (i in 1 until nodes.size) {
            tree.expandPath(TreePath(nodes.subList(0, i).toTypedArray()))
        }
        val treePath = TreePath(nodes.toTypedArray())
        tree.selectionPath = treePath
        tree.scrollPathToVisible(treePath)
    }
}

class OpenPluginSettingsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            "Turtle Commander",
        )
    }
}

class AboutAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        AboutDialog(e.project).show()
    }
}

class UserManualAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        BrowserUtil.browse("https://plugins.jetbrains.com/plugin/30863-turtle-commander/user-manual")
    }
}

private class AboutDialog(project: Project?) : DialogWrapper(project, false) {
    init {
        title = "About Turtle Commander"
        setOKButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val plugin = (AboutDialog::class.java.classLoader as? PluginAwareClassLoader)?.pluginDescriptor as? IdeaPluginDescriptor
        val version = plugin?.version ?: "unknown"

        val panel = JPanel(BorderLayout(JBUI.scale(12), 0))
        panel.border = JBUI.Borders.empty(10)

        val icon = IconLoader.getIcon("/META-INF/pluginIcon.svg", javaClass)
        val scaledIcon = IconUtil.scale(icon, null, 2.0f)
        val iconLabel = JLabel(scaledIcon)
        iconLabel.verticalAlignment = SwingConstants.TOP
        panel.add(iconLabel, BorderLayout.WEST)

        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)

        val nameLabel = JLabel("Turtle Commander")
        nameLabel.font = nameLabel.font.deriveFont(nameLabel.font.size2D + 4f)
        nameLabel.alignmentX = Component.LEFT_ALIGNMENT
        infoPanel.add(nameLabel)

        infoPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val versionLabel = JLabel("Version: $version")
        versionLabel.alignmentX = Component.LEFT_ALIGNMENT
        infoPanel.add(versionLabel)

        infoPanel.add(Box.createVerticalStrut(JBUI.scale(8)))

        val descLabel = JLabel("<html><body style='width:260px'>A dual-panel file manager for IntelliJ-based IDEs</body></html>")
        descLabel.alignmentX = Component.LEFT_ALIGNMENT
        infoPanel.add(descLabel)

        panel.add(infoPanel, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
}
