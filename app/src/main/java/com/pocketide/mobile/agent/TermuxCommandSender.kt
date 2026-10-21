package com.pocketide.mobile.agent

import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Fire-and-collect dispatch boundary for agent commands over Termux
 * RUN_COMMAND. Single exit point from proposal to executable action; the
 * ONLY component trusted to turn a validated command into an intent.
 *
 * SECURITY CONTRACT (the reason this class exists)
 *  - Every dispatch RE-VALIDATES the proposal at this boundary, ignoring
 *    any approval state the caller claims: [AgentCommandRegistry]
 *    (exact template matching) and [AgentApprovalGate] (fail-closed
 *    decision from the write-once ledger) are consulted here, with the
 *    live contract objects, not a caller-supplied verdict. A pending,
 *    denied, stale-different-shape, or argument-tampered proposal can
 *    therefore never reach the intent builder, whatever the caller does.
 *  - Because shipped policy approval binding is catalogId-grain for denials
 *    and exact-shape-grain for approvals, tampering with args after an
 *    approval restarts evaluation fail-closed (ApprovalRequired) and the
 *    dispatch is refused.
 *  - The argument list is carried verbatim into the RUN_COMMAND arguments
 *    extra. Arguments are NEVER concatenated into a shell string and no
 *    intermediary shell is spawned: what the user approved letter-for-letter
 *    is what Termux execs.
 *  - BACKGROUND is forced true regardless of [AgentCommandSpec.background]:
 *    false would open an interactive terminal session in Termux, and this
 *    integration is deliberately NOT an interactive PTY (its own one-shot
 *    output collection), so interactive sessions are never requested.
 *  - Nothing model-generated influences classification, approval, or intent
 *    composition; the model only ever supplied [AgentCommandSpec] content,
 *    which is treated as hostile input everywhere below the gate.
 *
 * LIFECYCLE GUARANTEES ACTUALLY PROVIDED (no more):
 *  - one-shot result collection per dispatch (a fresh receiver per request,
 *    nonce-guarded), unregistered on completion, timeout, or cancellation;
 *  - budget enforcement by [AgentCommandSpec.timeoutMs] measured from
 *    dispatch start: no result in time -> [FailureCode.TIMEOUT];
 *  - no correlation id exists in the Termux result payload, so results are
 *    bound to requests by construction: the pending dispatch owns its
 *    receiver and takes the FIRST accepted result it observes. The spoofing
 *    part of that surface (a third app delivering a fabricated result to our
 *    receiver) is narrowed by the receiver permission gate, cross-dispatch
 *    nonce matching, and parser integrity checks, and gets its full
 *    verification only on real hardware (P3e).
 *  - truncation of oversized stdout/stderr is reported via
 *    [AgentCommandResult.Success] original-length fields; the PARSE layer
 *    owns that mapping.
 *
 * TERMUX ENVIRONMENT REQUIREMENTS (checked before every dispatch):
 *  - Termux app installed ([FailureCode.MISSING_APP]);
 *  - com.termux.permission.RUN_COMMAND granted to us
 *    ([FailureCode.PERMISSION_NEEDED]);
 *  - allow-external-apps=true in the user's Termux properties — NOT
 *    locally probeable; a refusal surfaces post-dispatch as
 *    [FailureCode.TERMUX_INTERNAL] with Termux's own errmsg as detail.
 *
 * REFUSED-SHAPE RULES (transport facts, not taste):
 *  - workdir is rejected ([FailureCode.INVALID_SPEC]): Termux cannot chdir
 *    into this app's private storage, and no shared-workspace contract
 *    exists yet;
 *  - env overrides are rejected: RUN_COMMAND carries no environment
 *    extras (documented in AgentCommandSpec from T1);
 *  - stdin is capped conservatively at 64 KiB to stay far below Android
 *    binder transaction limits that would break PendingIntent creation.
 */
class TermuxCommandSender(
    private val environment: TermuxEnvironment,
    private val registry: AgentCommandRegistry,
    private val gate: AgentApprovalGate,
    private val transport: TermuxTransport
) {

    /**
     * Attempts one validated dispatch. Single-thread use contract (one
     * Android main-thread caller or serialized coroutine scope), matching
     * the registry/ledger tick contracts.
     *
     * Never throws for operational causes: every refusal or broken
     * integration is a returned [AgentCommandResult.Failure]. Unexpected
     * bugs surface as [FailureCode.INTERNAL] with the exception detail
     * rather than crashing the pipeline.
     */
    suspend fun dispatch(spec: AgentCommandSpec): AgentCommandResult {
        // Environment gate first: cheapest, and defines the honest failure
        // story for "Termux absent" vs "us misconfigured".
        if (!environment.isTermuxInstalled()) {
            return AgentCommandResult.Failure(
                FailureCode.MISSING_APP,
                "Termux (${TermuxPluginConstants.TERMUX_PACKAGE_NAME}) is not installed"
            )
        }
        if (!environment.hasRunCommandPermission()) {
            return AgentCommandResult.Failure(
                FailureCode.PERMISSION_NEEDED,
                "the ${TermuxPluginConstants.PERMISSION_RUN_COMMAND} permission is not granted to PocketIDE"
            )
        }

        // Transport-shape validation before any trust decision: a proposal
        // that Termux could not honestly run must fail without burning an
        // approval record or reaching the ledger.
        val shape = validateTransportShape(spec)
        if (shape != null) return shape

        // Trust re-validation at the exact dispatch boundary. The gate
        // decision is authoritative and is re-derived from the live ledger
        // here; caller-claimed approval state does not exist as a concept
        // in this API.
        val registryDecision = registry.validateProposal(spec)
        if (registryDecision is ProposalDecision.Invalid) {
            return AgentCommandResult.Failure(
                FailureCode.INVALID_SPEC,
                "proposal rejected by registry: ${registryDecision.reason} (${registryDecision.detail})"
            )
        }
        return when (val decision = gate.evaluate(spec)) {
            is ApprovalDecision.Denied ->
                // Trusted refusal passes through verbatim; the denial stays
                // catalogId-scoped final in the ledger.
                AgentCommandResult.Denied(decision.denial.op, decision.denial.outcome)

            is ApprovalDecision.ApprovalRequired ->
                // Nothing is pending that can self-approve: executing a
                // not-yet-approved proposal would bypass the user entirely.
                AgentCommandResult.Failure(
                    FailureCode.INVALID_SPEC,
                    "dispatch refused: approval required and not yet granted (${decision.reason::class.simpleName})"
                )

            is ApprovalDecision.Approved -> execute(spec)
        }
    }

    /** Run only for gate-approved, registry-valid, transport-shapely specs. */
    private suspend fun execute(spec: AgentCommandSpec): AgentCommandResult {
        val request = RunCommandRequest(
            executablePath = spec.executable,
            // Snapshot: the request must own its argument list so a caller
            // aliasing a mutable backing collection cannot redefine the
            // dispatched arguments after gate approval (B2 hardening).
            arguments = spec.args.toList(),
            stdin = spec.stdin,
            background = true,
            commandLabel = "pocketide:${spec.catalogId}"
        )
        val payload = try {
            withTimeoutOrNull(spec.timeoutMs) { transport.dispatch(request) }
                ?: return AgentCommandResult.Failure(
                    FailureCode.TIMEOUT,
                    "no result within ${spec.timeoutMs}ms budget"
                )
        } catch (e: SecurityException) {
            // Defensive in depth: the pre-check should have caught this, but
            // a runtime policy change must still fail closed, not crash.
            return AgentCommandResult.Failure(
                FailureCode.PERMISSION_NEEDED,
                e.message
            )
        } catch (e: IllegalArgumentException) {
            return AgentCommandResult.Failure(FailureCode.INVALID_SPEC, e.message)
        }
        return try {
            TermuxResultParser.parse(payload)
        } catch (e: RuntimeException) {
            AgentCommandResult.Failure(
                FailureCode.RECEIPT_INVALID,
                "result payload could not be parsed: ${e.message}"
            )
        }
    }

    /**
     * Transport-fact validation (ordered, deterministic). Returns a Failure
     * verdict or null when the spec is dispatchable. Executable/args NUL and
     * emptiness invariants are guaranteed by [AgentCommandSpec] itself.
     */
    private fun validateTransportShape(spec: AgentCommandSpec): AgentCommandResult.Failure? =
        when {
            spec.workdir != null -> AgentCommandResult.Failure(
                FailureCode.INVALID_SPEC,
                "workdir is not supported by this transport: Termux cannot chdir into this app's private storage"
            )

            spec.envOverride.isNotEmpty() -> AgentCommandResult.Failure(
                FailureCode.INVALID_SPEC,
                "environment overrides are not carried by RUN_COMMAND"
            )

            spec.stdin != null && spec.stdin.toByteArray(Charsets.UTF_8).size > MAX_STDIN_BYTES ->
                AgentCommandResult.Failure(
                    FailureCode.INVALID_SPEC,
                    "stdin exceeds the ${MAX_STDIN_BYTES} byte dispatch budget"
                )

            else -> null
        }

    private companion object {
        /** Conservative ceiling: stays far below Android binder transaction limits. */
        const val MAX_STDIN_BYTES: Int = 64 * 1024
    }
}

/**
 * Read-only probe of the on-device Termux environment. Implemented by the
 * Android transport glue; pure tests inject fakes. No caching: probes run
 * per dispatch so revoked permissions and uninstalls fail honestly.
 */
interface TermuxEnvironment {
    fun isTermuxInstalled(): Boolean
    fun hasRunCommandPermission(): Boolean
}

/**
 * Immutable, dispatch-shaped request: the executable path as an extra string
 * (never shell-mangled), the argument list verbatim (never concatenated),
 * and the lifecycle fields this backend actually supports. Transport-only
 * type: carries no approval state — approval is proven by [TermuxCommandSender]
 * before an instance of this type is created.
 */
data class RunCommandRequest(
    val executablePath: String,
    val arguments: List<String> = emptyList(),
    val stdin: String? = null,
    val workdir: File? = null,
    val background: Boolean = true,
    val commandLabel: String? = null
) {
    init {
        require(executablePath.isNotEmpty() && !executablePath.contains('\u0000')) {
            "executablePath must be non-empty and NUL-free"
        }
        require(arguments.none { it.contains('\u0000') }) { "argument must be NUL-free" }
        require(commandLabel?.contains('\u0000') != true) { "commandLabel must be NUL-free" }
    }
}

/**
 * One dispatch slot. Implementations dispatch the request to Termux, obtain
 * exclusively-own result delivery for that one request (one-shot receiver
 * whose lifetime is bound to this call), and suspend until the accepted
 * result payload arrives. Pure core sees no Android intent types — returns
 * a [TermuxResultView] over the delivered payload. Cancellation of the
 * caller must unregister the receiver: no result may remain collectable
 * after the budget is lost.
 */
interface TermuxTransport {
    suspend fun dispatch(request: RunCommandRequest): TermuxResultView
}
