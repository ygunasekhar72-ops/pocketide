package com.pocketide.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentApprovalGateTest {

    // Tables in this test are trusted-code fixtures: constructing the policy
    // object is itself a trusted-code act, not model input.
    private val policy = ApprovalPolicy.DEFAULT.copy(
        safeExecutables = setOf("/bin/true", "/bin/echo"),
        sensitiveExecutables = mapOf(
            "/usr/bin/git" to SensitiveOp.NETWORK_EGRESS,
            "/data/data/com.termux/files/usr/bin/pip" to SensitiveOp.PACKAGE_MANAGEMENT
        )
    )

    private fun spec(
        catalogId: String,
        executable: String,
        args: List<String> = emptyList(),
        hints: Set<SensitiveOp> = emptySet(),
        stdin: String? = null,
        workdir: File? = null
    ) = AgentCommandSpec(
        executable = executable,
        args = args,
        stdin = stdin,
        workdir = workdir,
        catalogId = catalogId,
        sensitiveOpsHints = hints
    )

    @Test
    fun `policy known safe executable is approved by trusted policy`() {
        val gate = AgentApprovalGate(policy)
        val decision = gate.evaluate(spec("safe-1", "/bin/true"))
        assertTrue(decision is ApprovalDecision.Approved)
        decision as ApprovalDecision.Approved
        assertEquals(ApprovalOrigin.TRUSTED_POLICY, decision.origin)
    }

    @Test
    fun `sensitive executable requires real approval and is not approved`() {
        val gate = AgentApprovalGate(policy)
        val decision = gate.evaluate(spec("git-1", "/usr/bin/git"))
        assertTrue(decision is ApprovalDecision.ApprovalRequired)
        assertFalse(decision is ApprovalDecision.Approved) // "required" is never "approved"
        decision as ApprovalDecision.ApprovalRequired
        assertEquals(
            ApprovalReason.Sensitive(SensitiveOp.NETWORK_EGRESS),
            decision.reason
        )
    }

    @Test
    fun `unknown executable fails closed to ambiguous approval`() {
        val gate = AgentApprovalGate(policy)
        val decision = gate.evaluate(spec("curl-1", "/system/bin/curl"))
        assertTrue(decision is ApprovalDecision.ApprovalRequired)
        decision as ApprovalDecision.ApprovalRequired
        assertTrue(decision.reason is ApprovalReason.AmbiguousExecutable)
    }

    @Test
    fun `hard blocked executable denies and records the denial`() {
        val ledger = AgentDecisionLedger()
        val gate = AgentApprovalGate(policy, ledger)
        val decision = gate.evaluate(spec("su-1", "/system/bin/su"))
        assertTrue(decision is ApprovalDecision.Denied)
        decision as ApprovalDecision.Denied
        assertEquals(SensitiveOp.PRIVILEGED_EXECUTION, decision.denial.op)
        assertEquals(DenialOutcome.HARD_BLOCKED, decision.denial.outcome)
        // The denial is recorded write-once, so it stays final for this catalogId.
        assertEquals(
            DecisionRecord.Denied(SensitiveOp.PRIVILEGED_EXECUTION, DenialOutcome.HARD_BLOCKED),
            ledger.lookup("su-1")
        )
    }

    @Test
    fun `bare name su proposal is hard blocked by policy`() {
        val gate = AgentApprovalGate(policy)
        val decision = gate.evaluate(spec("su-bare", "su"))
        assertTrue(decision is ApprovalDecision.Denied)
    }

    @Test
    fun `model hints escalate an otherwise safe command`() {
        val gate = AgentApprovalGate(policy)
        val decision = gate.evaluate(
            spec("spoof-1", "/bin/true", hints = setOf(SensitiveOp.NETWORK_EGRESS))
        )
        assertTrue(decision is ApprovalDecision.ApprovalRequired)
        assertFalse(decision is ApprovalDecision.Approved) // the model can never self-certify safety
        decision as ApprovalDecision.ApprovalRequired
        assertTrue(decision.reason is ApprovalReason.ModelHints)
    }

    @Test
    fun `model hints do not mask the trusted sensitive classification`() {
        val gate = AgentApprovalGate(policy)
        val decision = gate.evaluate(
            spec("git-hints", "/usr/bin/git", hints = setOf(SensitiveOp.WRITE_PROJECT_FILES))
        )
        decision as ApprovalDecision.ApprovalRequired
        // Policy's own reason wins deterministically; hints never downgrade it.
        assertEquals(
            ApprovalReason.Sensitive(SensitiveOp.NETWORK_EGRESS),
            decision.reason
        )
    }

    @Test
    fun `explicit user approval is recorded and re-evaluated`() {
        val gate = AgentApprovalGate(policy)
        val s = spec("decide-1", "/usr/bin/git")
        val recorded = gate.recordUserDecision(s, approved = true)
        assertTrue(recorded is ApprovalDecision.Approved)
        recorded as ApprovalDecision.Approved
        assertEquals(ApprovalOrigin.USER_DECISION, recorded.origin)
        // Later evaluations (any backend) see the recorded user grant — but
        // only for the exact operation the user actually decided on.
        val exact = gate.evaluate(spec("decide-1", "/usr/bin/git"))
        assertEquals(recorded, exact)
        // A modified arg list under the same id requires fresh evaluation.
        assertTrue(gate.evaluate(spec("decide-1", "/usr/bin/git", args = listOf("status")))
            is ApprovalDecision.ApprovalRequired)
    }

    @Test
    fun `user refusal produces a final user declined denial`() {
        val gate = AgentApprovalGate(policy)
        val denied = gate.recordUserDecision(spec("decide-2", "/usr/bin/git"), approved = false)
        denied as ApprovalDecision.Denied
        assertEquals(DenialOutcome.USER_DECLINED, denied.denial.outcome)
        assertEquals(SensitiveOp.NETWORK_EGRESS, denied.denial.op)
        val later = gate.evaluate(spec("decide-2", "/data/data/com.termux/files/usr/bin/pip"))
        assertEquals(denied, later) // same denial, same op, regardless of later spec shape
    }

    @Test
    fun `denial is final across an attempted backend-style change`() {
        val gate = AgentApprovalGate(policy)
        val denied = gate.evaluate(spec("bypass-1", "/system/bin/su"))
        assertTrue(denied is ApprovalDecision.Denied)
        // Same catalogId, completely different executable/args: exactly what a
        // reworded retry through another transport would look like.
        val bypass = gate.evaluate(
            spec("bypass-1", "/bin/echo", args = listOf("please", "allow"), stdin = "rephrase")
        )
        assertEquals(denied, bypass)
        assertTrue(bypass is ApprovalDecision.Denied)
    }

    @Test
    fun `recordUserDecision refuses decisions that were never required`() {
        val gate = AgentApprovalGate(policy) // safe executable and hard-blocked one
        for (bad in listOf(
            Triple(spec("never-1", "/bin/true"), true, "safe approved"),
            Triple(spec("never-2", "/bin/true"), false, "safe denied"),
            Triple(spec("never-3", "/system/bin/su"), true, "hard blocked granted")
        )) {
            try {
                gate.recordUserDecision(bad.first, bad.second)
                throw AssertionError("recording must refuse: ${bad.third}")
            } catch (_: IllegalArgumentException) {
                // trusted seam rejects non-pending decisions
            }
        }
    }

    @Test
    fun `evaluation is deterministic for repeated equivalent inputs`() {
        val gate = AgentApprovalGate(policy)
        val a = spec("det-1", "/usr/bin/git", stdin = null)
        val b = spec("det-1", "/usr/bin/git", stdin = "ignored payload")
        assertEquals(gate.evaluate(a), gate.evaluate(b))
        assertTrue(gate.evaluate(a) is ApprovalDecision.ApprovalRequired)

        val deniedGate = AgentApprovalGate(policy)
        val d1 = deniedGate.evaluate(spec("det-2", "/system/bin/su"))
        val s2 = AgentCommandSpec(
            executable = "/system/bin/su",
            args = listOf("-c", "different"),
            catalogId = "det-2"
        )
        val d2 = deniedGate.evaluate(s2)
        assertEquals(d1, d2)
    }

    @Test
    fun `evaluate never mutates the analyzed spec`() {
        val gate = AgentApprovalGate(policy)
        val s = spec("pure-1", "/usr/bin/git", args = listOf("status"), stdin = "payload")
        val snapshot = s.copy()
        gate.evaluate(s)
        assertEquals(snapshot, s)
    }

    @Test
    fun `shipped default policy approves nothing`() {
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        // No safe set exists in DEFAULT, so even conventionally harmless
        // binaries fail closed to a real user decision.
        val decision = gate.evaluate(spec("dflt-1", "/bin/true"))
        assertTrue(decision is ApprovalDecision.ApprovalRequired)
        decision as ApprovalDecision.ApprovalRequired
        assertTrue(decision.reason is ApprovalReason.AmbiguousExecutable)
    }

    @Test
    fun `policy tables must be disjoint`() {
        try {
            ApprovalPolicy(
                safeExecutables = setOf("/bin/true"),
                sensitiveExecutables = mapOf("/bin/true" to SensitiveOp.NETWORK_EGRESS)
            )
            throw AssertionError("overlapping tables must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            ApprovalPolicy(
                sensitiveExecutables = mapOf("/x" to SensitiveOp.PACKAGE_MANAGEMENT),
                hardBlockedExecutables = mapOf("/x" to SensitiveOp.PRIVILEGED_EXECUTION)
            )
            throw AssertionError("overlapping tables must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `recorded approval does not authorize a changed executable`() {
        val gate = AgentApprovalGate(policy)
        gate.recordUserDecision(spec("bind-1", "/usr/bin/git"), approved = true)
        // Same catalogId, different executable (id substitution / backend-style retry):
        // must NOT be covered by the approval. An unknown executable fails closed.
        val substitution = gate.evaluate(spec("bind-1", "/system/bin/curl"))
        assertTrue(substitution is ApprovalDecision.ApprovalRequired)
        substitution as ApprovalDecision.ApprovalRequired
        assertTrue(substitution.reason is ApprovalReason.AmbiguousExecutable)
        // And a hard-blocked substitute is denied outright, not approved.
        assertTrue(gate.evaluate(spec("bind-1", "/system/bin/su")) is ApprovalDecision.Denied)
    }

    @Test
    fun `recorded approval does not authorize changed arguments`() {
        val gate = AgentApprovalGate(policy)
        gate.recordUserDecision(spec("bind-2", "/usr/bin/git"), approved = true)
        val modified = gate.evaluate(spec("bind-2", "/usr/bin/git", args = listOf("push", "origin")))
        assertTrue(modified is ApprovalDecision.ApprovalRequired)
        assertFalse(modified is ApprovalDecision.Approved)
        modified as ApprovalDecision.ApprovalRequired
        assertEquals(ApprovalReason.Sensitive(SensitiveOp.NETWORK_EGRESS), modified.reason)
    }

    @Test
    fun `policy reclassification restarts evaluation instead of replaying approval`() {
        val ledger = AgentDecisionLedger()
        val gate = AgentApprovalGate(policy, ledger)
        gate.recordUserDecision(spec("bind-3", "/usr/bin/git"), approved = true)

        // Policy hardens: git is now hard-blocked. The old approval must not apply.
        val hardenedPolicy = policy.copy(
            sensitiveExecutables = mapOf(),
            hardBlockedExecutables = ApprovalPolicy.DEFAULT.hardBlockedExecutables +
                ("/usr/bin/git" to SensitiveOp.NETWORK_EGRESS)
        )
        val hardenedGate = AgentApprovalGate(hardenedPolicy, ledger)
        val hardened = hardenedGate.evaluate(spec("bind-3", "/usr/bin/git"))
        assertTrue(hardened is ApprovalDecision.Denied)

        // Policy reclassifies to a different named op: fresh approval required.
        val reclassifiedPolicy = policy.copy(
            sensitiveExecutables = mapOf("/usr/bin/git" to SensitiveOp.PACKAGE_MANAGEMENT)
        )
        val reclassifiedGate = AgentApprovalGate(reclassifiedPolicy, ledger)
        val reclassified = reclassifiedGate.evaluate(spec("bind-3", "/usr/bin/git"))
        reclassified as ApprovalDecision.ApprovalRequired
        assertEquals(
            ApprovalReason.Sensitive(SensitiveOp.PACKAGE_MANAGEMENT),
            reclassified.reason
        )
    }

    @Test
    fun `conflicting ledger records are rejected without overwriting the first`() {
        val ledger = AgentDecisionLedger()
        val approved = DecisionRecord.Approved(
            "/usr/bin/git",
            listOf(),
            ApprovalReason.Sensitive(SensitiveOp.NETWORK_EGRESS)
        )
        ledger.record("conflict-1", approved)
        try {
            ledger.record("conflict-1", DecisionRecord.Denied(SensitiveOp.PRIVILEGED_EXECUTION, DenialOutcome.HARD_BLOCKED))
            throw AssertionError("conflicting record must throw")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(approved, ledger.lookup("conflict-1"))

        val denied = DecisionRecord.Denied(SensitiveOp.PACKAGE_MANAGEMENT, DenialOutcome.USER_DECLINED)
        ledger.record("conflict-2", denied)
        try {
            ledger.record("conflict-2", DecisionRecord.Approved("/x", listOf(), ApprovalReason.AmbiguousExecutable))
            throw AssertionError("conflicting record must throw")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(denied, ledger.lookup("conflict-2"))
    }

    @Test
    fun `mutable argument collection cannot alter a recorded approval`() {
        val gate = AgentApprovalGate(policy)
        val backing = ArrayList<String>()
        backing.add("status")
        val s = AgentCommandSpec(
            executable = "/usr/bin/git",
            args = backing,
            catalogId = "mutable-1"
        )
        gate.recordUserDecision(s, approved = true)
        // Hostile mutation of the original collection after approval.
        backing.clear()
        backing.add("evil")
        // The recorded approval still stands for the EXACT operation that was
        // validated when the user decided, and the mutated shape is NOT
        // covered by it.
        assertTrue(
            gate.evaluate(spec("mutable-1", "/usr/bin/git", args = listOf("status")))
                is ApprovalDecision.Approved
        )
        assertTrue(
            gate.evaluate(spec("mutable-1", "/usr/bin/git", args = listOf("evil")))
                is ApprovalDecision.ApprovalRequired
        )
    }

    @Test
    fun `recordUserDecision after a recorded denial is refused without overwrite`() {
        val ledger = AgentDecisionLedger()
        val gate = AgentApprovalGate(policy, ledger)
        gate.recordUserDecision(spec("repeat-1", "/usr/bin/git"), approved = false)
        val before = ledger.lookup("repeat-1")
        try {
            gate.recordUserDecision(spec("repeat-1", "/usr/bin/git"), approved = true)
            throw AssertionError("repeat approval recording must be refused")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(before, ledger.lookup("repeat-1"))
        // Same outcome for an approval-based repeat (pending approval already granted).
        gate.recordUserDecision(spec("repeat-2", "/usr/bin/git"), approved = true)
        try {
            gate.recordUserDecision(spec("repeat-2", "/usr/bin/git"), approved = false)
            throw AssertionError("repeat denial recording must be refused")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(ledger.lookup("repeat-2") is DecisionRecord.Approved)
    }
}
