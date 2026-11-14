package io.github.jhspetersson.turtlecommander.integration

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.jhspetersson.turtlecommander.settings.ColorRuleEditDialog
import java.awt.Font

class ColorRulePreviewIntegrationTest : BasePlatformTestCase() {

    private fun withDialog(block: (ColorRuleEditDialog) -> Unit) {
        val dialog = ColorRuleEditDialog(project, null)
        try {
            block(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun testClearingTheSizeOverrideRestoresThePreviewSize() = withDialog { dialog ->
        val original = dialog.previewLabel.font.size

        dialog.fontSizeSpinner.value = original + 20
        assertEquals(original + 20, dialog.previewLabel.font.size)
        dialog.fontSizeSpinner.value = 0

        assertEquals(original, dialog.previewLabel.font.size)
    }

    fun testClearingTheStyleOverrideRestoresThePreviewStyle() = withDialog { dialog ->
        val original = dialog.previewLabel.font.style

        dialog.fontStyleCombo.selectedIndex = 4
        assertEquals(Font.BOLD or Font.ITALIC, dialog.previewLabel.font.style)
        dialog.fontStyleCombo.selectedIndex = 0

        assertEquals(original, dialog.previewLabel.font.style)
    }
}
