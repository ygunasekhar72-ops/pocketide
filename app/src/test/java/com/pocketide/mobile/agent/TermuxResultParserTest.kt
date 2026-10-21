package com.pocketide.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure result-payload contract: exit codes, Termux internal errors,
 * truncation lengths, and malformed receipts. Every malformed shape must
 * surface as Failure(RECEIPT_INVALID), never a thrown exception.
 */
class TermuxResultParserTest {

    /** Minimal in-memory [TermuxResultView] built from a nested map. */
    private class MapView(private val map: Map<String, Any?>) : TermuxResultView {
        override fun stringOrNull(key: String): String? = map[key] as? String
        override fun intOrNull(key: String): Int? = map[key] as? Int
        override fun innerBundle(key: String): TermuxResultView? {
            val nested = map[key] as? Map<*, *> ?: return null
            return MapView(nested.entries.associate { (k, v) -> k.toString() to v })
        }
    }

    private fun payload(vararg inner: Pair<String, Any?>) = MapView(
        mapIf(TermuxPluginConstants.RESULT_BUNDLE_KEY_IS_BUNDLE to mapOf(*inner))
    )

    private fun mapIf(vararg pairs: Pair<String, Any?>) = mapOf(*pairs)

    private fun raw(rootOnly: Map<String, Any?>) = MapView(rootOnly)

    private fun successResult(vararg inner: Pair<String, Any?>): AgentCommandResult {
        val result = TermuxResultParser.parse(payload(*inner))
        return result as AgentCommandResult.Success
    }

    private fun failureOf(vararg inner: Pair<String, Any?>): AgentCommandResult.Failure {
        val result = TermuxResultParser.parse(payload(*inner))
        return result as AgentCommandResult.Failure
    }

    private fun failureOfRoot(root: Map<String, Any?>): AgentCommandResult.Failure =
        TermuxResultParser.parse(raw(root)) as AgentCommandResult.Failure

    @Test
    fun `a success payload yields a result usable under the sealed parent type`() {
        // Verifies runtime subtype and variant discrimination. The
        // compile-time inheritance check lives where it belongs: the
        // AgentCommandResult.Success declaration and the parser's parent
        // return type.
        val underParent: AgentCommandResult = successResult(
            TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "ok"
        )
        assertTrue(underParent is AgentCommandResult.Success)
        assertTrue(underParent !is AgentCommandResult.Failure)
        assertTrue(underParent !is AgentCommandResult.Denied)
    }

    @Test
    fun `clean exit payload parses as success without truncation`() {
        val success = successResult(
            TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "hello",
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDERR to null
        )
        assertEquals(0, success.exitCode)
        assertEquals("hello", success.stdout)
        assertNull(success.stderr)
        assertFalse(success.stdoutTruncated)
        assertFalse(success.stderrTruncated)
        assertEquals(5, success.stdoutOriginalLength)
    }

    @Test
    fun `non-zero process exit is still success with stderr attached`() {
        val success = successResult(
            TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 1,
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "",
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDERR to "boom"
        )
        assertEquals(1, success.exitCode)
        assertEquals("boom", success.stderr)
    }

    @Test
    fun `truncation lengths are carried through`() {
        val success = successResult(
            TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "he",
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT_ORIGINAL_LENGTH to 5,
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDERR to "bo",
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDERR_ORIGINAL_LENGTH to 4
        )
        assertTrue(success.stdoutTruncated)
        assertTrue(success.stderrTruncated)
        assertEquals(5, success.stdoutOriginalLength)
        assertEquals(4, success.stderrOriginalLength)
    }

    @Test
    fun `termux internal error maps to TERMUX_INTERNAL with errmsg detail`() {
        val failure = failureOf(
            TermuxPluginConstants.RESULT_BUNDLE_KEY_ERR to 2,
            TermuxPluginConstants.RESULT_BUNDLE_KEY_ERRMSG to "not allowed"
        )
        assertEquals(FailureCode.TERMUX_INTERNAL, failure.code)
        assertEquals("not allowed", failure.detail)
    }

    @Test
    fun `err equals minus one means no internal error`() {
        val success = successResult(
            TermuxPluginConstants.RESULT_BUNDLE_KEY_ERR to -1,
            TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "ok"
        )
        assertEquals(0, success.exitCode)
    }

    @Test
    fun `internal error without errmsg falls back to the code`() {
        val failure = failureOf(TermuxPluginConstants.RESULT_BUNDLE_KEY_ERR to 3)
        assertEquals(FailureCode.TERMUX_INTERNAL, failure.code)
        assertEquals("Termux internal error code 3 without an errmsg", failure.detail)
    }

    @Test
    fun `empty stdout maps to success`() {
        val success = successResult(
            TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "",
        )
        assertEquals(0, success.exitCode)
        assertEquals("", success.stdout)
    }

    @Test
    fun `payload without nested result view is a receipt failure`() {
        val failure = failureOfRoot(mapOf("unrelated" to 1))
        assertEquals(FailureCode.RECEIPT_INVALID, failure.code)
    }

    @Test
    fun `payload without an exit code is a receipt failure`() {
        val failure = failureOf(
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "hello"
        )
        assertEquals(FailureCode.RECEIPT_INVALID, failure.code)
    }

    @Test
    fun `claimed original length smaller than delivered is a receipt failure`() {
        val failure = failureOf(
            TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "abcdef",
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT_ORIGINAL_LENGTH to 4
        )
        assertEquals(FailureCode.RECEIPT_INVALID, failure.code)
    }

    @Test
    fun `stderr without a declared stderr original length counts itself`() {
        val success = successResult(
            TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDERR to "boom"
        )
        assertFalse(success.stderrTruncated)
    }
}
