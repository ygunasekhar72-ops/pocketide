package com.pocketide.mobile.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bridge contract tests for [MonacoProtocol]: untrusted-inbound payload
 * validation, and trust-boundary properties of the outbound JS builders
 * (document text must never become executable JS).
 */
class MonacoProtocolTest {

    // ---- parseContentPayload ----

    @Test
    fun `well-formed content payload parses`() {
        val parsed = MonacoProtocol.parseContentPayload(
            """{"content":"fun main() {}","language":"kotlin"}"""
        )
        assertEquals("fun main() {}", parsed?.content)
        assertEquals("kotlin", parsed?.language)
    }

    @Test
    fun `empty content is rejected`() {
        assertNull(MonacoProtocol.parseContentPayload("""{"content":"","language":"x"}"""))
    }

    @Test
    fun `TYPES LANGUAGES FIELD must be a bounded string`() {
        assertNull(MonacoProtocol.parseContentPayload("""{"content":"a","language":""}"""))
        assertNull(
            MonacoProtocol.parseContentPayload(
                """{"content":"a","language":"${"x".repeat(100)}"}"""
            )
        )
    }

    @Test
    fun `oversized content is rejected`() {
        val big = "y".repeat(MonacoProtocol.MAX_DOCUMENT_CHARS + 1)
        assertNull(MonacoProtocol.parseContentPayload("""{"content":"$big","language":"x"}"""))
    }

    @Test
    fun `malformed payloads are rejected`() {
        assertNull(MonacoProtocol.parseContentPayload("x"))
        assertNull(MonacoProtocol.parseContentPayload("""{"content":42,"language":"kotlin"}"""))
        assertNull(MonacoProtocol.parseContentPayload("""{"language":"kotlin"}"""))
    }

    // ---- outbound scripts: injection resistance ----

    @Test
    fun `document text rides as JSON data and cannot become code`() {
        val script = MonacoProtocol.scriptSetContent(
            content = "\"); Draco: malicious(); //",
            language = "kotlin"
        )
        assertTrue(
            "script must contain the JSON-escaped payload",
            script.contains("\"); Draco: malicious(); //")
        )
        // The dangerous sequence only appears escaped inside a string, so
        // starting a new statement is impossible via the payload.
        assertTrue(!script.contains("Draco: malicious();\","))
        assertTrue(script.startsWith("window.pocketIDE.setContent(\""))
        assertTrue(script.endsWith("\");"))
    }

    @Test
    fun `insertText builder escapes control characters`() {
        val script = MonacoProtocol.scriptInsertText("a\nb\tc")
        assertTrue(script.contains("a\\nb\\tc"))
    }

    // ---- outbound scripts: cursor / tab / escape / pull ----

    @Test
    fun `pullContent and unparameterized builders are exact`() {
        assertEquals("window.pocketIDE.reportContent();", MonacoProtocol.scriptPullContent())
        assertEquals("window.pocketIDE.insertTab();", MonacoProtocol.scriptInsertTab())
        assertEquals("window.pocketIDE.editorEscape();", MonacoProtocol.scriptEscape())
    }

    @Test
    fun `cursor directions are validated`() {
        assertEquals(
            "window.pocketIDE.moveCursor(\"left\");",
            MonacoProtocol.scriptMoveCursor("left")
        )
        try {
            MonacoProtocol.scriptMoveCursor("diagonal")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("unknown cursor direction"))
        }
    }

    // ---- applyWindowEdit builders ----

    @Test
    fun `window edit builds with numeric and quoted parameters`() {
        val script = MonacoProtocol.scriptApplyWindowEdit(
            MonacoImeWindowEdit(123, 2, "xy\n")
        )
        assertEquals("window.pocketIDE.applyWindowEdit(123, 2, \"xy\\n\");", script)
    }

    @Test
    fun `window edit rejects negative bounds`() {
        try {
            MonacoProtocol.scriptApplyWindowEdit(MonacoImeWindowEdit(-1, 0, ""))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            MonacoProtocol.scriptApplyWindowEdit(MonacoImeWindowEdit(0, -1, ""))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `huge window edit offsets are rejected`() {
        val far = MonacoImeWindowEdit(Long.MAX_VALUE.toInt(), 1, "x".repeat(MonacoProtocol.MAX_DOCUMENT_CHARS + 1))
        try {
            MonacoProtocol.scriptApplyWindowEdit(far)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
