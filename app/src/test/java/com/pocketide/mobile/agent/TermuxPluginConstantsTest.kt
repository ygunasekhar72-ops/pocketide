package com.pocketide.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parity gate: pins the hand-declared Termux string surface byte-for-byte
 * against the values captured from termux/termux-app 0.118.x. A failing
 * assertion here means the constants drift from the pinned upstream surface.
 */
class TermuxPluginConstantsTest {

    @Test
    fun `action and permission strings match pinned Termux surface`() {
        assertEquals("com.termux", TermuxPluginConstants.TERMUX_PACKAGE_NAME)
        assertEquals("com.termux.RUN_COMMAND", TermuxPluginConstants.ACTION_RUN_COMMAND)
        assertEquals(
            "com.termux.permission.RUN_COMMAND",
            TermuxPluginConstants.PERMISSION_RUN_COMMAND
        )
    }

    @Test
    fun `RUN_COMMAND intent extra keys match pinned surface`() {
        val expected = mapOf(
            "com.termux.RUN_COMMAND_PATH" to TermuxPluginConstants.EXTRA_RUN_COMMAND_PATH,
            "com.termux.RUN_COMMAND_ARGUMENTS" to TermuxPluginConstants.EXTRA_RUN_COMMAND_ARGUMENTS,
            "com.termux.RUN_COMMAND_STDIN" to TermuxPluginConstants.EXTRA_RUN_COMMAND_STDIN,
            "com.termux.RUN_COMMAND_WORKDIR" to TermuxPluginConstants.EXTRA_RUN_COMMAND_WORKDIR,
            "com.termux.RUN_COMMAND_BACKGROUND" to TermuxPluginConstants.EXTRA_RUN_COMMAND_BACKGROUND,
            "com.termux.RUN_COMMAND_SESSION_ACTION" to TermuxPluginConstants.EXTRA_RUN_COMMAND_SESSION_ACTION,
            "com.termux.RUN_COMMAND_COMMAND_LABEL" to TermuxPluginConstants.EXTRA_RUN_COMMAND_COMMAND_LABEL,
            "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION" to TermuxPluginConstants.EXTRA_RUN_COMMAND_COMMAND_DESCRIPTION,
            "com.termux.RUN_COMMAND_COMMAND_HELP" to TermuxPluginConstants.EXTRA_RUN_COMMAND_COMMAND_HELP,
            "com.termux.RUN_COMMAND_PENDING_INTENT" to TermuxPluginConstants.EXTRA_RUN_COMMAND_PENDING_INTENT
        )
        expected.forEach { (upstream, ours) ->
            assertEquals(upstream, ours)
        }
    }

    @Test
    fun `result bundle keys match pinned surface`() {
        val expected = mapOf(
            "result" to TermuxPluginConstants.RESULT_BUNDLE_KEY_IS_BUNDLE,
            "exitCode" to TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE,
            "stdout" to TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT,
            "stderr" to TermuxPluginConstants.RESULT_BUNDLE_KEY_STDERR,
            "stdout_original_length" to TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT_ORIGINAL_LENGTH,
            "stderr_original_length" to TermuxPluginConstants.RESULT_BUNDLE_KEY_STDERR_ORIGINAL_LENGTH,
            "err" to TermuxPluginConstants.RESULT_BUNDLE_KEY_ERR,
            "errmsg" to TermuxPluginConstants.RESULT_BUNDLE_KEY_ERRMSG
        )
        expected.forEach { (upstream, ours) ->
            assertEquals(upstream, ours)
        }
    }
}
