package io.github.jhspetersson.turtlecommander.settings

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * Curated set of stable, public [AllIcons] constants offered as per-rule icon overrides
 * in the colorization rules editor.
 *
 * Rules persist the [Entry.key] — a stable string we own — never a platform icon resource
 * path. That decouples saved settings and exported `.tcrules` files from IDE internals:
 * across an IDE upgrade an unknown key simply resolves to `null` and the entry falls back
 * to its default file-type/folder icon, rather than crashing or showing a broken image.
 * Keys are therefore an append-only contract: add freely, rename with care.
 *
 * Only public `com.intellij.icons.AllIcons` fields are referenced here (no reflection, no
 * internal `IconLoader` paths), so the set is theme-aware and survives platform updates.
 */
object RuleIcons {

    data class Entry(val key: String, val label: String, val category: String, val icon: Icon)

    val ENTRIES: List<Entry> = listOf(
        // Marks & status
        Entry("star", "Star", "Marks", AllIcons.Nodes.Favorite),
        Entry("check", "Checkmark", "Marks", AllIcons.Actions.Checked),
        Entry("ok", "Inspections OK", "Marks", AllIcons.General.InspectionsOK),
        Entry("add", "Plus", "Marks", AllIcons.General.Add),
        Entry("remove", "Minus", "Marks", AllIcons.General.Remove),
        Entry("error", "Error", "Marks", AllIcons.General.Error),
        Entry("warning", "Warning", "Marks", AllIcons.General.Warning),
        Entry("info", "Info", "Marks", AllIcons.General.Information),
        Entry("todo", "To-do", "Marks", AllIcons.General.TodoDefault),
        Entry("todo-important", "To-do (important)", "Marks", AllIcons.General.TodoImportant),
        Entry("bulb", "Idea", "Marks", AllIcons.Actions.IntentionBulb),
        Entry("filter", "Filter", "Marks", AllIcons.General.Filter),
        Entry("settings", "Settings", "Marks", AllIcons.General.Settings),
        Entry("pin", "Pin", "Marks", AllIcons.General.Pin_tab),
        Entry("user", "User", "Marks", AllIcons.General.User),
        Entry("web", "Web", "Marks", AllIcons.General.Web),

        // Actions
        Entry("run", "Run", "Actions", AllIcons.Actions.Execute),
        Entry("stop", "Stop", "Actions", AllIcons.Actions.Suspend),
        Entry("restart", "Restart", "Actions", AllIcons.Actions.Restart),
        Entry("refresh", "Refresh", "Actions", AllIcons.Actions.Refresh),
        Entry("find", "Find", "Actions", AllIcons.Actions.Find),
        Entry("edit", "Edit", "Actions", AllIcons.Actions.Edit),
        Entry("copy", "Copy", "Actions", AllIcons.Actions.Copy),
        Entry("annotate", "Annotate", "Actions", AllIcons.Actions.Annotate),
        Entry("lightning", "Lightning", "Actions", AllIcons.Actions.Lightning),
        Entry("trash", "Trash", "Actions", AllIcons.Actions.GC),
        Entry("cancel", "Cancel", "Actions", AllIcons.Actions.Cancel),
        Entry("close", "Close", "Actions", AllIcons.Actions.Close),
        Entry("download", "Download", "Actions", AllIcons.Actions.Download),
        Entry("upload", "Upload", "Actions", AllIcons.Actions.Upload),
        Entry("back", "Back", "Actions", AllIcons.Actions.Back),
        Entry("forward", "Forward", "Actions", AllIcons.Actions.Forward),

        // Nodes
        Entry("folder", "Folder", "Nodes", AllIcons.Nodes.Folder),
        Entry("module", "Module", "Nodes", AllIcons.Nodes.Module),
        Entry("plugin", "Plugin", "Nodes", AllIcons.Nodes.Plugin),
        Entry("home", "Home", "Nodes", AllIcons.Nodes.HomeFolder),
        Entry("class", "Class", "Nodes", AllIcons.Nodes.Class),
        Entry("interface", "Interface", "Nodes", AllIcons.Nodes.Interface),
        Entry("enum", "Enum", "Nodes", AllIcons.Nodes.Enum),
        Entry("method", "Method", "Nodes", AllIcons.Nodes.Method),
        Entry("field", "Field", "Nodes", AllIcons.Nodes.Field),
        Entry("function", "Function", "Nodes", AllIcons.Nodes.Function),
        Entry("variable", "Variable", "Nodes", AllIcons.Nodes.Variable),
        Entry("constant", "Constant", "Nodes", AllIcons.Nodes.Constant),
        Entry("property", "Property", "Nodes", AllIcons.Nodes.Property),
        Entry("package", "Package", "Nodes", AllIcons.Nodes.Package),
        Entry("tag", "Tag", "Nodes", AllIcons.Nodes.Tag),
        Entry("console", "Console", "Nodes", AllIcons.Nodes.Console),

        // File types
        Entry("text", "Text", "File types", AllIcons.FileTypes.Text),
        Entry("archive", "Archive", "File types", AllIcons.FileTypes.Archive),
        Entry("java", "Java", "File types", AllIcons.FileTypes.Java),
        Entry("javascript", "JavaScript", "File types", AllIcons.FileTypes.JavaScript),
        Entry("xml", "XML", "File types", AllIcons.FileTypes.Xml),
        Entry("json", "JSON", "File types", AllIcons.FileTypes.Json),
        Entry("html", "HTML", "File types", AllIcons.FileTypes.Html),
        Entry("css", "CSS", "File types", AllIcons.FileTypes.Css),
        Entry("yaml", "YAML", "File types", AllIcons.FileTypes.Yaml),
        Entry("config", "Config", "File types", AllIcons.FileTypes.Config),
        Entry("any", "Generic file", "File types", AllIcons.FileTypes.Any_type),

        // Version control
        Entry("branch", "Branch", "Version control", AllIcons.Vcs.Branch),
        Entry("history", "History", "Version control", AllIcons.Vcs.History),
    )

    private val byKey: Map<String, Entry> = ENTRIES.associateBy { it.key }

    /** The icon for [key], or `null` for the empty key or any key not in the current set. */
    fun resolve(key: String): Icon? = if (key.isEmpty()) null else byKey[key]?.icon

    /** Human-readable label for [key], or `null` if unknown. */
    fun label(key: String): String? = byKey[key]?.label
}
