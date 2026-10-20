package com.pocketide.mobile.agent

/**
 * Hand-declared, verbatim string surface of the Termux plugin API needed to
 * build a RUN_COMMAND request and read back a plugin result.
 *
 * Provenance and pinned revision:
 *  - action, permission, intent extras and result-bundle keys were taken
 *    verbatim from `termux/termux-app` (`TermuxConstants.java` defaults and
 *    the RUN_COMMAND-Intent wiki page at Termux 0.118.x).
 *  - These are strings, not a dependency: no termux-shared, no Termux code of
 *    any license is compiled into PocketIDE (maintains the no-GPL-in-APK
 *    posture of decision record 0001).
 *  - COMPATIBILITY CAVEAT: declaring these constants does NOT guarantee
 *    compatibility with every Termux version or fork. First-send code must
 *    probe the installed Termux (app identity + feature expectation) and
 *    degrade in a declared, user-visible way; extras supported only by newer
 *    Termux releases must stay behind runtime capability checks (later
 *    phases), never sent unconditionally.
 *
 * Values are asserted byte-for-byte by TermuxPluginConstantsTest, which
 * doubles as the parity gate guarding against accidental drift in this file.
 */
object TermuxPluginConstants {

    const val TERMUX_PACKAGE_NAME: String = "com.termux"

    const val ACTION_RUN_COMMAND: String = "com.termux.RUN_COMMAND"

    const val PERMISSION_RUN_COMMAND: String = "com.termux.permission.RUN_COMMAND"

    const val EXTRA_RUN_COMMAND_PATH: String = "com.termux.RUN_COMMAND_PATH"

    const val EXTRA_RUN_COMMAND_ARGUMENTS: String = "com.termux.RUN_COMMAND_ARGUMENTS"

    const val EXTRA_RUN_COMMAND_STDIN: String = "com.termux.RUN_COMMAND_STDIN"

    const val EXTRA_RUN_COMMAND_WORKDIR: String = "com.termux.RUN_COMMAND_WORKDIR"

    const val EXTRA_RUN_COMMAND_BACKGROUND: String = "com.termux.RUN_COMMAND_BACKGROUND"

    const val EXTRA_RUN_COMMAND_SESSION_ACTION: String = "com.termux.RUN_COMMAND_SESSION_ACTION"

    const val EXTRA_RUN_COMMAND_COMMAND_LABEL: String = "com.termux.RUN_COMMAND_COMMAND_LABEL"

    const val EXTRA_RUN_COMMAND_COMMAND_DESCRIPTION: String =
        "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"

    const val EXTRA_RUN_COMMAND_COMMAND_HELP: String = "com.termux.RUN_COMMAND_COMMAND_HELP"

    const val EXTRA_RUN_COMMAND_PENDING_INTENT: String = "com.termux.RUN_COMMAND_PENDING_INTENT"

    /** Plugin result Bundle payload keys used by TermuxService's result sender. */
    const val RESULT_BUNDLE_KEY_IS_BUNDLE: String = "result"

    /** Bundle key carrying the process exit status. */
    const val RESULT_BUNDLE_KEY_EXIT_CODE: String = "exitCode"

    const val RESULT_BUNDLE_KEY_STDOUT: String = "stdout"

    const val RESULT_BUNDLE_KEY_STDERR: String = "stderr"

    const val RESULT_BUNDLE_KEY_STDOUT_ORIGINAL_LENGTH: String = "stdout_original_length"

    const val RESULT_BUNDLE_KEY_STDERR_ORIGINAL_LENGTH: String = "stderr_original_length"

    /** Bundle key for Termux internal error number (`-1` = no internal error). */
    const val RESULT_BUNDLE_KEY_ERR: String = "err"

    const val RESULT_BUNDLE_KEY_ERRMSG: String = "errmsg"
}
