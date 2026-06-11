package io.github.jhspetersson.turtlecommander.ui

import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * Shared quick-filter bar (Ctrl-S) used by [FileTab] and [SearchResultsPanel]: a platform
 * [SearchTextField] (standard look, embedded find icon, inline clear-text button). Starts
 * hidden; the owner shows it via [activate] and decides what filtering means through the
 * callbacks.
 *
 * Key handling matches the historical hand-built bars: Esc hides the bar, Up/Down move the
 * owner's selection without leaving the field, Enter moves focus to the owner's view while
 * keeping the filter applied.
 */
internal class QuickFilterBar(
    /** Fired on every text change with the current (untrimmed) text. */
    private val onFilterChanged: (String) -> Unit,
    /** Esc: the owner clears the filter, hides the bar, and refocuses its view. */
    private val onHideRequested: () -> Unit,
    /** Up/Down delegated from the field; [offset] is +1 / -1. */
    private val onMoveSelection: (offset: Int) -> Unit,
    /** Enter: the owner moves focus to its view, keeping the filter applied. */
    private val onCommit: () -> Unit,
) : JPanel(BorderLayout(4, 0)) {

    private val searchField = SearchTextField(false)

    val text: String get() = searchField.text

    init {
        isVisible = false
        border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        add(searchField, BorderLayout.CENTER)

        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ESCAPE -> {
                        onHideRequested()
                        e.consume()
                    }
                    KeyEvent.VK_UP, KeyEvent.VK_DOWN -> {
                        onMoveSelection(if (e.keyCode == KeyEvent.VK_DOWN) 1 else -1)
                        e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        onCommit()
                        e.consume()
                    }
                }
            }
        })

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onFilterChanged(searchField.text)
        })
    }

    /**
     * Show the bar empty and focus the field; just refocus when already visible. The owner
     * is responsible for revalidating its layout afterwards.
     */
    fun activate() {
        if (isVisible) {
            searchField.textEditor.requestFocusInWindow()
            return
        }
        isVisible = true
        searchField.text = ""
        searchField.textEditor.requestFocusInWindow()
    }

    /**
     * Clear the text and hide the bar. Clearing a non-empty field fires [onFilterChanged];
     * owners that must re-apply unconditionally (the empty→empty case fires no event) call
     * their filter routine explicitly afterwards.
     */
    fun deactivate() {
        searchField.text = ""
        isVisible = false
    }

    fun hasFieldFocus(): Boolean = searchField.textEditor.hasFocus()

    /**
     * Toggle the platform error outline on the editor — the standard way to flag invalid
     * input (here: a glob that doesn't parse), instead of hand-tinting colors the LAF can
     * override during paint.
     */
    fun setError(error: Boolean) {
        val newValue = if (error) "error" else null
        if (searchField.textEditor.getClientProperty(OUTLINE_PROPERTY) != newValue) {
            searchField.textEditor.putClientProperty(OUTLINE_PROPERTY, newValue)
            searchField.textEditor.repaint()
        }
    }

    private companion object {
        const val OUTLINE_PROPERTY = "JComponent.outline"
    }
}
