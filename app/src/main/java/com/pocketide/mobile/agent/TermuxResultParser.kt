package com.pocketide.mobile.agent

/**
 * Pure parser for one Termux RUN_COMMAND result payload delivered through a
 * [TermuxCommandSender]-managed PendingIntent.
 *
 * SOURCE OF TRUTH for keys: [TermuxPluginConstants] (parity-gated by
 * TermuxPluginConstantsTest). The parser owns the mapping decision:
 *
 *  - a result payload whose `result` nested view is missing entirely is not
 *    a usable receipt -> [FailureCode.RECEIPT_INVALID];
 *  - `err != -1` (and present) is a Termux-internal failure (e.g. rejected
 *    request); `errmsg` is carried verbatim as failure detail. NOTE: the
 *    integration cannot remotely distinguish allow-external-apps=false from
 *    other service rejections, so ALL of those surface as
 *    [FailureCode.TERMUX_INTERNAL] with detail — the user-visible remedy is
 *    enabling allow-external-apps, documented at the caller layer;
 *  - otherwise the payload is a process-completion report: exit code and
 *    delivered stdout/stderr live in [AgentCommandResult.Success], with
 *    original-length fields carried through so truncation remains
 *    detectable. A reported original length SMALLER than the delivered
 *    content violates the backend's own integrity claim and is treated as
 *    [FailureCode.RECEIPT_INVALID];
 *  - absent stderr is an ordinary "no stderr" case (null), not an error;
 *    absent original-length keys mean "not truncated" (original = delivered).
 *
 * The parser never trusts fields it was not given and never throws: every
 * malformed shape becomes an [AgentCommandResult.Failure].
 */
object TermuxResultParser {

    fun parse(payload: TermuxResultView): AgentCommandResult {
        val inner = payload.innerBundle(TermuxPluginConstants.RESULT_BUNDLE_KEY_IS_BUNDLE)
            ?: return AgentCommandResult.Failure(
                FailureCode.RECEIPT_INVALID,
                "result payload is missing the '${TermuxPluginConstants.RESULT_BUNDLE_KEY_IS_BUNDLE}' nested view"
            )

        val errCode = inner.intOrNull(TermuxPluginConstants.RESULT_BUNDLE_KEY_ERR)
        if (errCode != null && errCode != -1) {
            val errmsg = inner.stringOrNull(TermuxPluginConstants.RESULT_BUNDLE_KEY_ERRMSG)
            return AgentCommandResult.Failure(
                FailureCode.TERMUX_INTERNAL,
                errmsg?.takeIf { it.isNotBlank() }
                    ?: "Termux internal error code $errCode without an errmsg"
            )
        }

        val exitCode = inner.intOrNull(TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE)
            ?: return AgentCommandResult.Failure(
                FailureCode.RECEIPT_INVALID,
                "result payload carries no exit code"
            )

        val stdout = inner.stringOrNull(TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT) ?: ""
        val stderr = inner.stringOrNull(TermuxPluginConstants.RESULT_BUNDLE_KEY_STDERR)
        val stdoutOriginal = inner.intOrNull(
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT_ORIGINAL_LENGTH
        ) ?: stdout.length
        val stderrOriginal = inner.intOrNull(
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDERR_ORIGINAL_LENGTH
        ) ?: (stderr?.length ?: 0)

        if (stdout.length > stdoutOriginal) {
            return AgentCommandResult.Failure(
                FailureCode.RECEIPT_INVALID,
                "delivered stdout exceeds the reported original length"
            )
        }
        if ((stderr?.length ?: 0) > stderrOriginal) {
            return AgentCommandResult.Failure(
                FailureCode.RECEIPT_INVALID,
                "delivered stderr exceeds the reported original length"
            )
        }

        return AgentCommandResult.Success(
            stdout = stdout,
            stderr = stderr,
            exitCode = exitCode,
            stdoutOriginalLength = stdoutOriginal,
            stderrOriginalLength = stderrOriginal
        )
    }
}

/**
 * Read-only, dependency-free accessor over the delivered result payload.
 * Implemented by the transport glue over an android Bundle; the pure core
 * types see no Android classes. All accessors are best-effort: null means
 * "absent", never an exception surface.
 */
interface TermuxResultView {

    /** Value of a string-valued payload key, or null when absent. */
    fun stringOrNull(key: String): String?

    /** Value of an int-valued payload key, or null when absent. */
    fun intOrNull(key: String): Int?

    /** A nested view stored under [key], or null when absent. */
    fun innerBundle(key: String): TermuxResultView?
}
