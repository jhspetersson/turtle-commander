package io.github.jhspetersson.turtlecommander.filetype

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextLikeFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object TctThemeFileType : FileType, PlainTextLikeFileType {
    private val turtleIcon: Icon by lazy {
        IconLoader.getIcon("/icons/turtleCommander.svg", TctThemeFileType::class.java)
    }

    override fun getName(): String = "Turtle Commander Theme"

    override fun getDescription(): String = "Turtle Commander theme"

    override fun getDefaultExtension(): String = "tctheme"

    override fun getIcon(): Icon = turtleIcon

    override fun isBinary(): Boolean = false
}
