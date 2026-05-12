package io.github.jhspetersson.turtlecommander.dialog

import io.github.jhspetersson.turtlecommander.service.OverwritePolicy
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

/**
 * Maps the [OverwritePolicy] enum to user-facing labels for the Copy / Move / Extract
 * default-policy combo box. Centralized so the four call sites use the exact same
 * wording.
 */
internal object OverwritePolicyRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean,
    ): Component {
        val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        text = label(value as? OverwritePolicy)
        return c
    }

    fun label(policy: OverwritePolicy?): String = when (policy) {
        OverwritePolicy.ASK -> "Ask for each existing file"
        OverwritePolicy.OVERWRITE_ALL -> "Overwrite all"
        OverwritePolicy.SKIP_ALL -> "Skip all existing"
        OverwritePolicy.IF_OLDER -> "Overwrite if older"
        null -> ""
    }
}
