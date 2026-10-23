package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.extensions.ExtensionPointName

interface OpenTargetResolver {
    fun resolve(e: AnActionEvent): OpenTarget?

    companion object {
        val EP: ExtensionPointName<OpenTargetResolver> =
            ExtensionPointName("io.github.jhspetersson.turtlecommander.openTargetResolver")
    }
}
