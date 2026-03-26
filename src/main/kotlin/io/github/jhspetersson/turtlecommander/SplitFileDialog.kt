package io.github.jhspetersson.turtlecommander

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class SplitFileDialog(
    project: Project,
    private val fileName: String,
    private val fileSize: Long,
    targetDirectory: String,
) : DialogWrapper(project) {

    private val targetDirField = JBTextField(targetDirectory)
    private val splitBySizeRadio = JBRadioButton("Split by size", true)
    private val splitByPartsRadio = JBRadioButton("Split by number of parts")

    private val sizeCombo = ComboBox(DefaultComboBoxModel(PRESET_SIZES.map { it.first }.toTypedArray())).apply {
        isEditable = true
        selectedIndex = 3
    }

    private val partsSpinner = JSpinner(SpinnerNumberModel(2, 2, 9999, 1)).apply {
        isEnabled = false
    }

    val targetDirectory: String get() = targetDirField.text.trim()
    val isSplitBySize: Boolean get() = splitBySizeRadio.isSelected

    val chunkSize: Long
        get() {
            val selectedText = sizeCombo.editor.item.toString().trim()
            val preset = PRESET_SIZES.find { it.first == selectedText }
            if (preset != null) return preset.second
            return parseSize(selectedText)
        }

    val numberOfParts: Int get() = (partsSpinner.value as Number).toInt()

    init {
        title = "Split File"
        setOKButtonText("Split")
        init()

        val group = ButtonGroup()
        group.add(splitBySizeRadio)
        group.add(splitByPartsRadio)

        splitBySizeRadio.addActionListener {
            sizeCombo.isEnabled = true
            partsSpinner.isEnabled = false
        }
        splitByPartsRadio.addActionListener {
            sizeCombo.isEnabled = false
            partsSpinner.isEnabled = true
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            minimumSize = Dimension(500, 240)
            preferredSize = Dimension(500, 240)
        }

        panel.add(JBLabel("Split \"$fileName\" (${formatSize(fileSize)})").apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(8)
        })

        panel.add(JBLabel("Target directory:").apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        panel.add(targetDirField.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        })

        panel.add(JPanel().apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false

            add(splitBySizeRadio.apply { alignmentX = JComponent.LEFT_ALIGNMENT })

            add(JPanel(GridBagLayout()).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                isOpaque = false
                val gbc = GridBagConstraints().apply {
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insets(2, 20, 2, 4)
                }
                gbc.gridx = 0; add(JBLabel("Chunk size:"), gbc)
                gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
                add(sizeCombo, gbc)
            })

            add(splitByPartsRadio.apply { alignmentX = JComponent.LEFT_ALIGNMENT })

            add(JPanel(GridBagLayout()).apply {
                alignmentX = JComponent.LEFT_ALIGNMENT
                isOpaque = false
                val gbc = GridBagConstraints().apply {
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insets(2, 20, 2, 4)
                }
                gbc.gridx = 0; add(JBLabel("Number of parts:"), gbc)
                gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
                add(partsSpinner, gbc)
            })
        })

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (targetDirectory.isEmpty()) {
            return ValidationInfo("Target directory cannot be empty", targetDirField)
        }
        if (isSplitBySize) {
            val size = try { chunkSize } catch (_: Exception) { 0L }
            if (size <= 0) {
                return ValidationInfo("Invalid chunk size", sizeCombo)
            }
        }
        return null
    }

    private fun parseSize(text: String): Long {
        val trimmed = text.trim().uppercase()
        val multipliers = listOf(
            "TB" to 1L * 1024 * 1024 * 1024 * 1024,
            "GB" to 1L * 1024 * 1024 * 1024,
            "MB" to 1L * 1024 * 1024,
            "KB" to 1024L,
        )
        for ((suffix, multiplier) in multipliers) {
            if (trimmed.endsWith(suffix)) {
                val num = trimmed.removeSuffix(suffix).trim().toDoubleOrNull() ?: return 0L
                return (num * multiplier).toLong()
            }
        }
        return trimmed.toLongOrNull() ?: 0L
    }

    companion object {
        val PRESET_SIZES = listOf(
            "1.44 MB (Floppy)" to 1_457_664L,
            "100 MB (Zip)" to 100L * 1024 * 1024,
            "650 MB (CD)" to 650L * 1024 * 1024,
            "700 MB (CD)" to 700L * 1024 * 1024,
            "1 GB" to 1L * 1024 * 1024 * 1024,
            "2 GB" to 2L * 1024 * 1024 * 1024,
            "4 GB (FAT32)" to 4L * 1024 * 1024 * 1024 - 1,
            "4.7 GB (DVD)" to (4.7 * 1024 * 1024 * 1024).toLong(),
            "8.5 GB (DVD DL)" to (8.5 * 1024 * 1024 * 1024).toLong(),
            "25 GB (Blu-ray)" to 25L * 1024 * 1024 * 1024,
        )
    }
}
