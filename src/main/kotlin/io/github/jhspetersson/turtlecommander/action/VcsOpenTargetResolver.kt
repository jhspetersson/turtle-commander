package io.github.jhspetersson.turtlecommander.action

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangesUtil

class VcsOpenTargetResolver : OpenTargetResolver {
    override fun resolve(e: AnActionEvent): OpenTarget? =
        e.getData(VcsDataKeys.VIRTUAL_FILES)?.firstOrNull()?.let { OpenTarget.of(it) }
            ?: e.getData(VcsDataKeys.FILE_PATH)?.let { resolve(it) }
            ?: e.getData(VcsDataKeys.CHANGES)?.firstOrNull()?.let { resolve(ChangesUtil.getFilePath(it)) }

    private fun resolve(filePath: FilePath): OpenTarget? {
        if (filePath.isNonLocal) return null
        return filePath.virtualFile?.let { OpenTarget.of(it) } ?: OpenTarget.ofPath(filePath.ioFile.toPath())
    }
}
