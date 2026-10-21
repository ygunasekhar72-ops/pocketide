package com.pocketide.mobile.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the IME-window bridge ([MonacoImeWindow]): the pure
 * window-text diff + the untrusted-payload decoding that both sides of
 * the editor bridge depend on for editing correctness.
 */
class MonacoImeWindowTest {

    // ---- extractWindowEdit ----

    @Test
    fun `insertion at the end of the window anchors at the tail`() {
        val edit = MonacoImeWindow.extractWindowEdit("ab", "abx", 100)
        assertEquals(MonacoImeWindowEdit(102, 0, "x"), edit)
    }

    @Test
    fun `insertion in the middle anchors after the common prefix`() {
        val edit = MonacoImeWindow.extractWindowEdit("abc", "abXYc", 50)
        assertEquals(MonacoImeWindowEdit(52, 0, "XY"), edit)
    }

    @Test
    fun `deletion in the middle replaces correctly`() {
        val edit = MonacoImeWindow.extractWindowEdit("abXYc", "abc", 50)
        assertEquals(MonacoImeWindowEdit(52, 2, ""), edit)
    }

    @Test
    fun `replacement covers the maximal subpart`() {
        val edit = MonacoImeWindow.extractWindowEdit("abXYc", "abZwfWc", 7)
        assertEquals(MonacoImeWindowEdit(9, 2, "Zwf"), edit)
    }

    @Test
    fun `full replacement of longs anchors at zero`() {
        val edit = MonacoImeWindow.extractWindowEdit("aaa", "zzz", 0)
        assertEquals(MonacoImeWindowEdit(0, 3, "zzz"), edit)
    }

    @Test
    fun `full window rewrite anchors at the window start`() {
        val edit = MonacoImeWindow.extractWindowEdit("a|", "|a", 5)
        assertEquals(MonacoImeWindowEdit(5, 2, "|a"), edit)
    }

    @Test
    fun `identical strings produce no edit`() {
        assertNull(MonacoImeWindow.extractWindowEdit("same", "same", 0))
    }

    @Test
    fun `negative window offset produces no edit`() {
        assertNull(MonacoImeWindow.extractWindowEdit("a", "ax", -1))
    }

    @Test
    fun `multi-char composing blocks are one edit`() {
        val edit = MonacoImeWindow.extractWindowEdit("void", "void main(", 0)
        assertEquals(MonacoImeWindowEdit(4, 0, " main("), edit)
    }

    // ---- parseWindow ----

    @Test
    fun `well-formed window payload parses`() {
        val window = MonacoImeWindow.parseWindow(
            """{"windowStartOffset":1234,"cursorInWindow":10,"text":"hello world"}"""
        )
        assertEquals(
            MonacoImeWindow(1234, 10, "hello world"),
            window
        )
    }

    @Test
    fun `missing fields are rejected`() {
        assertNull(MonacoImeWindow.parseWindow("""{"cursorInWindow":3,"text":"x"}"""))
        assertNull(MonacoImeWindow.parseWindow("""{"windowStartOffset":3,"text":"x"}"""))
        assertNull(MonacoImeWindow.parseWindow("""{"windowStartOffset":3,"cursorInWindow":3}"""))
    }

    @Test
    fun `string fields typed as strings are rejected`() {
        assertNull(
            MonacoImeWindow.parseWindow(
                """{"windowStartOffset":"3","cursorInWindow":3,"text":"x"}"""
            )
        )
    }

    @Test
    fun `negative offsets are rejected`() {
        assertNull(
            MonacoImeWindow.parseWindow(
                """{"windowStartOffset":-1,"cursorInWindow":3,"text":"x"}"""
            ) 
        )
        assertNull(
            MonacoImeWindow.parseWindow(
                """{"windowStartOffset":0,"cursorInWindow":-3,"text":"x"}"""
            )
        )
    }

    @Test
    fun `payloads larger than the window bound are rejected`() {
        val big = "x".repeat(MonacoImeWindow.MAX_WINDOW_CHARS + 1)
        assertNull(
            MonacoImeWindow.parseWindow(
                """{"windowStartOffset":0,"cursorInWindow":0,"text":"$big"}"""
            )
        )
        val ok = "y".repeat(MonacoImeWindow.MAX_WINDOW_CHARS)
        assertTrue(
            MonacoImeWindow.parseWindow(
                """{"windowStartOffset":0,"cursorInWindow":0,"text":"$ok"}"""
            ) != null
        )
    }

    @Test
    fun `malformed JSON is rejected`() {
        assertNull(MonacoImeWindow.parseWindow("not json"))
        assertNull(MonacoImeWindow.parseWindow(""))
        assertNull(MonacoImeWindow.parseWindow("""[1,2]"""))
    }
}
