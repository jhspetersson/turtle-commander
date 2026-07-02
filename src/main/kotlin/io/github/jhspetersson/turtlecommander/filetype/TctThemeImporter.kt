package io.github.jhspetersson.turtlecommander.filetype

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import io.github.jhspetersson.turtlecommander.settings.ThemeManager
import io.github.jhspetersson.turtlecommander.settings.TurtleCommanderSettings

object TctThemeImporter {

    fun importFromFile(project: Project?, file: VirtualFile) {
        val text = try {
            VfsUtilCore.loadText(file)
        } catch (e: Exception) {
            Messages.showWarningDialog(project, "Could not read theme file: ${e.message}", "Import Theme")
            return
        }

        val theme = ThemeManager.importTheme(text)
        if (theme == null) {
            Messages.showWarningDialog(project, "Invalid theme file.", "Import Theme")
            return
        }

        val state = TurtleCommanderSettings.getInstance().state
        val allNames = ThemeManager.getAllThemes(state).map { it.name }.toSet()
        val overwrite = theme.name in allNames && when (Messages.showYesNoCancelDialog(
            project,
            "A theme named \"${theme.name}\" already exists. Overwrite it?",
            "Import Theme", "Overwrite", "Rename", "Cancel", null,
        )) {
            Messages.YES -> true
            Messages.NO -> false
            else -> return
        }

        val (result, effectiveName) = ThemeManager.importThemeToState(state, theme, overwrite)
        val message = when (result) {
            ThemeManager.ImportResult.Replaced -> "Theme \"$effectiveName\" was updated."
            ThemeManager.ImportResult.Renamed -> "Theme imported as \"$effectiveName\" to avoid a name conflict."
            ThemeManager.ImportResult.Imported -> "Theme \"$effectiveName\" was imported."
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Turtle Commander")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}
