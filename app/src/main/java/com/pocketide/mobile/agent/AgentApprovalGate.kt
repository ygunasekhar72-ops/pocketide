package com.pocketide.mobile.agent

/**
 * Trusted application-side approval gate for agent-proposed commands.
 *
 * SECURITY MODEL
 *  - Classification derives from the trusted [ApprovalPolicy] tables via
 *    deterministic executable-identity rules (exact string equality). No
 *    model-supplied material ([AgentCommandSpec.sensitiveOpsHints], args,
 *    stdin, labels, claims) is ever used as a classification source.
 *  - Hints can only RAISE the bar: hints attached to a safe executable force
 *    approval, so the model can never declare its own command safe. Hints
 *    never downgrade a sensitive or unclassified verdict.
 *  - Unknown executables fail closed: they require a REAL user decision and
 *    are never silently approved.
 *  - "Approval required" is a distinct sealed outcome from "approved"; the
 *    gate never converts one into the other.
 *  - The gate holds NO execution capability of any kind: evaluate() yields
 *    decisions only. Command dispatch/transport is not in this milestone.
 *  - Evaluate-time hard blocks and user decisions (via [recordUserDecision],
 *    a trusted-code seam; T2 wires no UI) are recorded in the
 *    [AgentDecisionLedger], making denials final for the catalogId across
 *    every later evaluation, regardless of backend. Recorded APPROVALS,
 *    by contrast, bind to the exact validated operation and its
 *    classification basis — a changed executable, changed arguments, or a
 *    reclassified policy restarts evaluation and fails closed.
 *  - Executable-identity classification is NOT sufficient to guard future
 *    arbitrary command execution: argument-level classification (e.g.
 *    `git status` vs `git push`) must precede any real dispatch, or
 *    every non-safe command keeps requiring a real user decision. T4
 *    implements that precondition: trusted [ApprovalPolicy] tables classify
 *    (executable + positional argument prefix) at argument level when no
 *    executable-table reason already applies. Rules can only RAISE a
 *    verdict to approval-required; they never approve, never weaken the
 *    executable tables, and always mask advisory model hints.
 *  - Argument rules refine classification but cannot change approval
 *    binding: a recorded approval still binds to the exact operation
 *    (executable + argument list) and the classification basis it was
 *    decided on; any divergence — including a re-armed rule — restarts
 *    evaluation and fails closed.
 *
 * Reason precedence (deterministic): hard block (denied, not "required") >
 * sensitive-table classification > safe-exempt-with-model-hints >
 * AmbiguousExecutable (unknown identity).
 *
 * NOTE: classification is executable-identity plus argument-level (T4): see
 * the reason precedence in [determineRequiredReason] and the rule semantics
 * on [SensitiveArgumentRule].
 */
class AgentApprovalGate(
    private val policy: ApprovalPolicy,
    private val ledger: AgentDecisionLedger = AgentDecisionLedger()
) {

    /**
     * Loads the approval decision for one proposed command. Pure and
     * deterministic: equivalent inputs yield equal decisions; the only side
     * effects are write-once policy records (hard blocks), never execution.
     *
     * A stored approval is honored only when the exact operation (executable
     * + args) still matches AND the current policy classification still
     * equals the basis the approval was decided on; any divergence restarts
     * evaluation from scratch and fails closed.
     */
    fun evaluate(spec: AgentCommandSpec): ApprovalDecision {
        val record = ledger.lookup(spec.catalogId)
        if (record is DecisionRecord.Denied) {
            // Denials are catalogId-scoped and final: shape changes cannot bypass them.
            return ApprovalDecision.Denied(AgentCommandResult.Denied(record.op, record.outcome))
        }
        if (
            record is DecisionRecord.Approved &&
            record.executable == spec.executable &&
            record.args == spec.args &&
            spec.executable !in policy.hardBlockedExecutables
        ) {
            val currentReason = determineRequiredReason(spec)
            if (currentReason == null || currentReason == record.reasonAtApproval) {
                return ApprovalDecision.Approved(ApprovalOrigin.USER_DECISION)
            }
            // Current classification diverges from the approval basis: fall
            // through to a fresh, fail-closed evaluation below.
        }

        // Trusted hard block precedes any model hint. The ledger slot may
        // already hold a (now-superseded) approval record; write-once rules
        // forbid overwriting it, so only record when the slot is free.
        policy.hardBlockedExecutables[spec.executable]?.let { op ->
            if (record == null) {
                ledger.record(spec.catalogId, DecisionRecord.Denied(op, DenialOutcome.HARD_BLOCKED))
            }
            return ApprovalDecision.Denied(AgentCommandResult.Denied(op, DenialOutcome.HARD_BLOCKED))
        }

        val reason = determineRequiredReason(spec)
        return if (reason != null) {
            ApprovalDecision.ApprovalRequired(reason)
        } else {
            ApprovalDecision.Approved(ApprovalOrigin.TRUSTED_POLICY)
        }
    }

    /**
     * Records a REAL user decision for a currently approval-required
     * proposal. Trusted seam only (no UI wiring exists in T2). The spec is
     * re-classified deterministically, so the operation recorded in denial
     * records comes from the gate's own classification: a spoofed spec can
     * never grant approval to itself, and nothing is grantable for a
     * hard-blocked or already-recorded catalogId.
     */
    fun recordUserDecision(spec: AgentCommandSpec, approved: Boolean): ApprovalDecision {
        val pending = evaluate(spec)
        val subject = pending as? ApprovalDecision.ApprovalRequired
            ?: throw IllegalArgumentException(
                "no approval decision is pending for catalogId='${spec.catalogId}'"
            )
        // Canonical op for records: the named sensitive op, or the most
        // restrictive available category when the classifier cannot name one.
        val op = (subject.reason as? ApprovalReason.Sensitive)?.op
            ?: SensitiveOp.PRIVILEGED_EXECUTION

        return if (approved) {
            ledger.record(
                spec.catalogId,
                DecisionRecord.Approved(
                    executable = spec.executable,
                    // Snapshot: the record must own its argument list so a
                    // mutable backing collection can never redefine what the
                    // user actually approved.
                    args = spec.args.toList(),
                    reasonAtApproval = subject.reason
                )
            )
            ApprovalDecision.Approved(ApprovalOrigin.USER_DECISION)
        } else {
            ledger.record(spec.catalogId, DecisionRecord.Denied(op, DenialOutcome.USER_DECLINED))
            ApprovalDecision.Denied(AgentCommandResult.Denied(op, DenialOutcome.USER_DECLINED))
        }
    }

    /** Deterministically computes the reason approval is required, or null if not. */
    private fun determineRequiredReason(spec: AgentCommandSpec): ApprovalReason? {
        val hintReason = spec.sensitiveOpsHints
            .takeIf { it.isNotEmpty() }
            ?.let { ApprovalReason.ModelHints(it) }
        val sensitiveOp = policy.sensitiveExecutables[spec.executable]

        return when {
            spec.executable in policy.hardBlockedExecutables ->
                null // hard blocks are denied by policy, not put up for approval

            sensitiveOp != null ->
                ApprovalReason.Sensitive(sensitiveOp) // own policy reason masks argument rules and hints

            spec.executable in policy.safeExecutables ->
                argumentReason(spec) ?: hintReason // argument policy above advisory hints

            else ->
                argumentReason(spec)
                    ?: hintReason ?: ApprovalReason.AmbiguousExecutable // unknown identity: fail closed
        }
    }

    /**
     * Argument-level classification (T4 verdict refinement, not approval):
     * the first rule whose exact (executable, positional argument prefix)
     * match succeeds wins, mirroring the registry's prefix policy shape.
     * No parsing, no shell semantics, no substring matching.
     */
    private fun argumentReason(spec: AgentCommandSpec): ApprovalReason? =
        policy.sensitiveArgumentRules
            .firstOrNull { it.matches(spec.executable, spec.args) }
            ?.let { ApprovalReason.Sensitive(it.op) }
}

enum class ApprovalOrigin {
    /** Approved because the trusted policy declares the executable safe (no hints). */
    TRUSTED_POLICY,

    /** Approved because a real user decision was recorded for the catalogId. */
    USER_DECISION
}

sealed interface ApprovalReason {
    /** The trusted policy classifies this executable as a named sensitive operation. */
    data class Sensitive(val op: SensitiveOp) : ApprovalReason

    /** The executable's identity is unknown to the trusted policy: fail-closed ambiguity. */
    object AmbiguousExecutable : ApprovalReason

    /** Model-supplied hints raise the bar on an otherwise-safe executable. */
    data class ModelHints(val ops: Set<SensitiveOp>) : ApprovalReason
}

sealed interface ApprovalDecision {

    /** Permitted to proceed. Origin states why: trusted policy or a real user decision. */
    data class Approved(
        val origin: ApprovalOrigin
    ) : ApprovalDecision

    /** A real user decision is required before dispatch. NOT an approval. */
    data class ApprovalRequired(
        val reason: ApprovalReason
    ) : ApprovalDecision

    /** Final for this catalogId until replaced; transport changes cannot bypass it. */
    data class Denied(
        val denial: AgentCommandResult.Denied
    ) : ApprovalDecision
}

/**
 * Trusted argument-level classification rule (M1-T4). Created by trusted
 * application code only — never derived from model text, the registry, or
 * any model-supplied spec content:
 *
 *  - matching is EXACT: the executable identity by string equality and the
 *    positional argument prefix by list equality ([matches]) — no parsing,
 *    no globbing, no concatenation, no substring logic; a rule can never be
 *    satisfied by argument contents that only "look like" the prefix;
 *  - a matched rule raises the verdict to [ApprovalReason.Sensitive] with
 *    the rule's named operation: it can make commands REQUIRE approval,
 *    never the reverse — it cannot approve, exempt, or downgrade anything;
 *  - rules are deterministic and ordered: the first matching rule in the
 *    policy's declared order wins when several could apply;
 *  - an executable-table classification (sensitive or hard-blocked) always
 *    precedes rule matching, preserving the T2 fail-closed ordering; model
 *    hints never override a matched rule.
 */
data class SensitiveArgumentRule(
    val executable: String,
    val requiredArgsPrefix: List<String>,
    val op: SensitiveOp
) {
    /** Exact positional prefix match; shorter argument lists never match. */
    fun matches(executable: String, args: List<String>): Boolean =
        executable == this.executable &&
            args.size >= requiredArgsPrefix.size &&
            args.subList(0, requiredArgsPrefix.size) == requiredArgsPrefix

    init {
        require(executable.isNotEmpty() && !executable.contains(NUL)) {
            "argument rule executable must be non-empty and NUL-free"
        }
        require(requiredArgsPrefix.isNotEmpty()) {
            "argument rule must require at least one argument"
        }
        require(requiredArgsPrefix.none { it.contains(NUL) }) {
            "argument rule prefix must be NUL-free"
        }
    }

    private companion object {
        const val NUL = '\u0000'
    }
}

/**
 * Trusted classification tables. Keys are executable identity strings
 * (matched exactly, absolute paths preferred; a bare name such as "su" also
 * matches bare-name proposals deterministically). The tables must be
 * disjoint: an executable is either safe, sensitive (with one named
 * operation), or hard-blocked — never several. Argument-level rules
 * ([sensitiveArgumentRules]) may reference any executable identity; the
 * executable tables keep precedence over them per [SensitiveArgumentRule].
 */
data class ApprovalPolicy(
    val safeExecutables: Set<String> = emptySet(),
    val sensitiveExecutables: Map<String, SensitiveOp> = emptyMap(),
    val hardBlockedExecutables: Map<String, SensitiveOp> = emptyMap(),
    val sensitiveArgumentRules: List<SensitiveArgumentRule> = emptyList()
) {
    init {
        val sensitiveSafe = sensitiveExecutables.keys intersect safeExecutables
        val hardSafe = hardBlockedExecutables.keys intersect safeExecutables
        val sensitiveHard = sensitiveExecutables.keys intersect hardBlockedExecutables.keys
        val overlap: Set<String> = sensitiveSafe + hardSafe + sensitiveHard
        require(overlap.isEmpty()) {
            "an executable cannot be in more than one policy table: $overlap"
        }
        // Defensive: every rule carries its own construction invariants; the
        // list itself holds no cross-entry constraints beyond declaration
        // order (first-match-wins is the documented deterministic policy).
    }

    companion object {
        /**
         * Shipped default, deliberately conservative to match actual shipped
         * capability: NO executable is auto-approved and the argument-rule
         * table is EMPTY until trusted code registers rules as real backend
         * capabilities materialize; only genuinely privileged binaries are
         * hard-blocked. The tables are extended solely by trusted code.
         */
        val DEFAULT = ApprovalPolicy(
            safeExecutables = emptySet(),
            sensitiveExecutables = emptyMap(),
            hardBlockedExecutables = mapOf(
                "/system/bin/su" to SensitiveOp.PRIVILEGED_EXECUTION,
                "/system/xbin/su" to SensitiveOp.PRIVILEGED_EXECUTION,
                "su" to SensitiveOp.PRIVILEGED_EXECUTION,
                "pm" to SensitiveOp.PRIVILEGED_EXECUTION
            ),
            sensitiveArgumentRules = emptyList()
        )
    }
}
