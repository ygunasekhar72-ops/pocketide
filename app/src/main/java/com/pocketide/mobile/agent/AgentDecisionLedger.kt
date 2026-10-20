package com.pocketide.mobile.agent

/**
 * Process-lifetime record of explicit approval decisions, keyed by the
 * trusted [AgentCommandSpec.catalogId] fingerprint. Consequences:
 *
 *  - a recorded DENIAL is scoped to the catalogId (broad grain, deliberately):
 *    any later evaluation returns the same denial regardless of how the
 *    proposed command shape changed, so retrying through a different backend
 *    or reusing the id with a different command cannot bypass it;
 *  - a recorded APPROVAL is scoped to the EXACT validated operation it
 *    authorizes (executable + argument list + the classification basis the
 *    user actually decided on). Replaying the catalogId with a different
 *    executable, different arguments, or under a reclassified policy does not
 *    honor the approval — evaluation starts fresh and fails closed;
 *  - the ledger is write-once per catalogId: conflicting records are
 *    rejected deterministically and a prior decision is never silently
 *    overwritten;
 *  - an approval record exists only through a real user decision recorded by
 *    trusted code; T2 wires no UI, so no approval record can exist "behind
 *    the user's back";
 *  - no model-facing code path can read or write decisions here; model input
 *    (AgentCommandSpec content) is never an input to this type.
 *
 * LIFECYCLE: the ledger is IN-MEMORY ONLY. Every recorded decision is lost on
 * process death, so no durability claim is made: after a restart the gate
 * re-asks (fail closed). Cross-restart denial enforcement requires a
 * persistence design that is OUT OF SCOPE for T2.
 *
 * CONCURRENCY: this ledger is NOT thread-safe and makes no atomicity promise.
 * It is designed for single-threaded (Android main-thread) use inside one
 * gate instance. Concurrent evaluation/writes are unsupported: conflicting
 * concurrent decisions are UNDEFINED behavior, not a supported denial
 * override — any multi-thread use must serialize access itself.
 */
sealed interface DecisionRecord {

    /** A rejection by user decision or hard policy rule. Broad grain: the catalogId. */
    data class Denied(
        val op: SensitiveOp,
        val outcome: DenialOutcome
    ) : DecisionRecord

    /**
     * A grant by explicit user decision. Narrow grain: bound to the exact
     * validated operation — the executable, the argument list, and the
     * approval reason that the user decided on. A later spec that differs in
     * any of these (or whose current classification diverges from
     * [reasonAtApproval]) is not covered.
     */
    data class Approved(
        val executable: String,
        val args: List<String>,
        val reasonAtApproval: ApprovalReason
    ) : DecisionRecord
}

class AgentDecisionLedger {

    private val records = HashMap<String, DecisionRecord>()

    /** Returns the recorded decision for a catalogId, or null if none exists. */
    fun lookup(catalogId: String): DecisionRecord? = records[catalogId]

    /** Records one write-once decision. Throws if a decision already exists. */
    fun record(catalogId: String, record: DecisionRecord) {
        require(catalogId.isNotBlank()) { "catalogId must be non-blank" }
        require(records.putIfAbsent(catalogId, record) == null) {
            "decision already recorded for catalogId='${catalogId}'"
        }
    }
}
