package io.github.jhspetersson.turtlecommander.util

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Writes [text] to the system clipboard.
 *
 * Windows `OpenClipboard` rejects with `IllegalStateException` ("cannot open
 * system clipboard") whenever another process — clipboard manager, password
 * manager, browser — holds the clipboard at that instant. The contention window
 * is typically <100ms, so a short backoff loop turns a hard failure into a
 * silent retry; this same situation also makes copy-related unit tests flaky on
 * dev machines.
 *
 * The retry loop is intentionally synchronous (rather than dispatched off the
 * EDT) so callers — and tests — can read the clipboard back immediately after
 * the call returns. The worst-case stall is ~300ms and only occurs under real
 * contention, which the uncontended common case (first attempt succeeds) avoids
 * entirely.
 */
fun copyToClipboard(text: String) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val contents = StringSelection(text)
    var lastError: IllegalStateException? = null
    var sleep = 20L
    repeat(5) {
        try {
            clipboard.setContents(contents, null)
            return
        } catch (e: IllegalStateException) {
            lastError = e
            try {
                Thread.sleep(sleep)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            sleep *= 2
        }
    }
    // Final attempt — let the exception surface if it still fails so callers can
    // log or notify rather than silently dropping the user's copy.
    if (lastError != null) {
        clipboard.setContents(contents, null)
    }
}
