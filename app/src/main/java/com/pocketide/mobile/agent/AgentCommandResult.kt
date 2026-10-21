package com.pocketide.mobile.agent

/**
 * Platform-independent result of one agent command. The sealed hierarchy has
 * exactly three top-level variants — [Success], [Denied], [Failure] — kept
 * disjoint so call sites must handle them separately:
 *
 *  - [Success]    the command ran to completion. Process exit status lives
 *    here ONLY; it is never conflated with integration failure, denial, or
 *    truncation. Delivered-vs-original output lengths are explicit so a
 *    caller can detect truncation (backends may cut oversized output).
 *  - [Denied]     a trusted, application-owned policy gate refused the
 *    command BEFORE execution. Model-suggested backends or reworded retries
 *    cannot turn a [Denied] into a [Success]; denial handling is policy.
 *  - [Failure]    the integration failed without reaching the tool
 *    (backend absent, configuration wrong, result never arrived, ...). A
 *    process's non-zero exit code is NOT a [Failure]; backends report tool
 *    failures as [Success] with the real exit code and output attached.
 *    TIMEOUT is represented by [FailureCode.TIMEOUT], not its own variant,
 *    so new integration failure causes stay additive.
 */
sealed class AgentCommandResult {

    data class Success(
        val stdout: String,
        val stderr: String?,
        val exitCode: Int,
        val stdoutOriginalLength: Int,
        val stderrOriginalLength: Int
    ) : AgentCommandResult() {
        init {
            require(stdoutOriginalLength >= 0) { "stdoutOriginalLength must be >= 0" }
            require(stderrOriginalLength >= 0) { "stderrOriginalLength must be >= 0" }
            require(stdout.length <= stdoutOriginalLength) {
                "delivered stdout must not exceed the reported original length"
            }
            require(stderrOriginalLength >= (stderr?.length ?: 0)) {
                "delivered stderr must not exceed the reported original length"
            }
        }

        /** True when the backend handed back less stdout than the tool produced. */
        val stdoutTruncated: Boolean
            get() = stdoutOriginalLength > stdout.length

        /** True when the backend handed back less stderr than the tool produced. */
        val stderrTruncated: Boolean
            get() = stderrOriginalLength > (stderr?.length ?: 0)
    }

    data class Denied(
        val op: SensitiveOp,
        val outcome: DenialOutcome
    ) : AgentCommandResult()

    data class Failure(
        val code: FailureCode,
        val detail: String? = null
    ) : AgentCommandResult()
}

/** Why the policy gate refused the command. Values are trusted-code facts. */
enum class DenialOutcome {
    /** The user explicitly declined the sensitive operation. */
    USER_DECLINED,

    /** Refused without asking: matches a hard non-negotiable deny rule. */
    HARD_BLOCKED
}

/** Integration-level failure taxonomy (kept additive: new causes, same type). */
enum class FailureCode {
    /** Termux is not installed. */
    MISSING_APP,

    /** Termux installed but the RUN_COMMAND permission was not granted to us. */
    PERMISSION_NEEDED,

    /** Termux installed but the user has not enabled allow-external-apps. */
    POLICY_OFF,

    /** Spec was rejected by pre-send validation (e.g. path not shared). */
    INVALID_SPEC,

    /** Execution exceeded the per-command budget; result lease expired. */
    TIMEOUT,

    /** Termux reported an internal error after accepting the command. */
    TERMUX_INTERNAL,

    /** Result delivery failed structurally (spoofing, mismatched receipt). */
    RECEIPT_INVALID,

    /** Bug-shaped local failure; always worth a crash report. */
    INTERNAL
}
