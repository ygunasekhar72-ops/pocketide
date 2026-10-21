package com.pocketide.mobile.agent

import java.io.File

/**
 * Platform-independent description of ONE agent-proposed command, deliberately
 * structured (executable + argument list) so that no backend ever needs to
 * concatenate arguments into a shell string. This type is handed to future
 * execution backends (e.g. a Termux RUN_COMMAND sender); it cannot start a
 * process by itself.
 *
 * Provenance of each member (trusted application code = PocketIDE's own
 * non-model code paths):
 *
 *  - [executable]  model/advisor-supplied candidate. Must be non-blank and
 *    NUL-free here; whether it is an allowed absolute path (or an approved
 *    `$PREFIX/`/`~/` expansion token) is validated by trusted code later, when
 *    a concrete backend is chosen. T1 does NOT enforce absoluteness. The
 *    first chosen backend (Termux RUN_COMMAND) now does: see
 *    [TermuxExecutablePath], enforced at the dispatch boundary by
 *    [TermuxCommandSender].
 *  - [args]        model/advisor-supplied. Kept verbatim: spaces, quotes and
 *    newlines inside arguments are legitimate values and are not restricted.
 *    NUL bytes are structurally invalid for every real exec path, so they are
 *    rejected as a type invariant, wherever they appear.
 *  - [workdir]     model-supplied extent, nullable (null = backend default).
 *    Reachability across app sandboxes is checked by trusted code per backend.
 *  - [stdin]       model-supplied payload, nullable. One-shot by contract.
 *  - [envOverride] advisory only. The first planned backend (Termux
 *    RUN_COMMAND) carries no env-override extras at all, so entries must not
 *    be relied upon for correctness; keys are structurally validated only.
 *  - [background]  trusted-code decision (backend capability, not model).
 *  - [timeoutMs]   derived by trusted code from user preferences; must be > 0.
 *  - [catalogId]   derived by trusted code as a stable fingerprint of the
 *    proposed command (used later for approval caching); never model input.
 *  - [sensitiveOpsHints] advisory (see [SensitiveOp]).
 */
data class AgentCommandSpec(
    val executable: String,
    val args: List<String> = emptyList(),
    val workdir: File? = null,
    val stdin: String? = null,
    val envOverride: Map<String, String> = emptyMap(),
    val background: Boolean = true,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val catalogId: String,
    val sensitiveOpsHints: Set<SensitiveOp> = emptySet()
) {

    init {
        require(executable.isNotEmpty() && !executable.contains(NUL)) {
            "executable must be non-empty and NUL-free"
        }
        require(catalogId.isNotBlank()) { "catalogId must be a non-blank trusted fingerprint" }
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        require(args.none { it.contains(NUL) }) { "argument must be NUL-free" }
        envOverride.forEach { (key, value) ->
            require(key.isNotBlank() && !key.contains(NUL) && !key.contains('=')) {
                "environment override key must be non-blank, NUL-free and contain no '='"
            }
            require(!value.contains(NUL)) { "environment override value must be NUL-free" }
        }
    }

    companion object {
        /** Default agent-command budget; overridable per spec, enforced > 0. */
        const val DEFAULT_TIMEOUT_MS: Long = 300_000L

        private const val NUL = '\u0000'
    }
}
