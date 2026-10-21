package com.pocketide.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1-T4: argument-level classification layer of the trusted approval policy.
 *
 * Security invariants under test:
 *  - a matched argument rule RAISES the verdict to approval-required with a
 *    named sensitive operation; it never approves or downgrades anything;
 *  - matching is exact-identity + exact positional prefix: shorter lists,
 *    different positions, or lookalike contents never satisfy a rule;
 *  - the first declared matching rule wins (deterministic precedence);
 *  - executable-level tables keep their T2 precedence: hard blocks are
 *    denied outright and sensitive executables mask argument rules;
 *  - model hints never override a matched rule (policy is stricter);
 *  - the ledger approval basis stays exact-shape: rules refine classification
 *    only; approvals still bind to executable + argument list + basis.
 */
class AgentArgumentPolicyTest {

    private fun spec(
        catalogId: String,
        executable: String,
        args: List<String> = emptyList(),
        hints: Set<SensitiveOp> = emptySet()
    ) = AgentCommandSpec(
        executable = executable,
        args = args,
        catalogId = catalogId,
        sensitiveOpsHints = hints
    )

    private fun argPolicy(
        argRules: List<SensitiveArgumentRule> = emptyList(),
        safe: Set<String> = emptySet(),
        sensitive: Map<String, SensitiveOp> = emptyMap(),
        hardBlocked: Map<String, SensitiveOp> = emptyMap()
    ) = ApprovalPolicy(
        safeExecutables = safe,
        sensitiveExecutables = sensitive,
        hardBlockedExecutables = hardBlocked,
        sensitiveArgumentRules = argRules
    )

    private val echoRule = SensitiveArgumentRule(
        executable = "/bin/echo",
        requiredArgsPrefix = listOf("--dangerous"),
        op = SensitiveOp.PROCESS_MANAGEMENT
    )

    @Test
    fun `matched argument rule raises a safe executable to approval-required`() {
        val gate = AgentApprovalGate(argPolicy(argRules = listOf(echoRule), safe = setOf("/bin/echo")))
        val decision = gate.evaluate(spec("id-1", "/bin/echo", args = listOf("--dangerous", "x")))
        decision as ApprovalDecision.ApprovalRequired
        assertEquals(ApprovalReason.Sensitive(SensitiveOp.PROCESS_MANAGEMENT), decision.reason)
    }

    @Test
    fun `matched argument rule never auto-approves even with no hints`() {
        val gate = AgentApprovalGate(argPolicy(argRules = listOf(echoRule), safe = setOf("/bin/echo")))
        val decision = gate.evaluate(spec("id-2", "/bin/echo", args = listOf("--dangerous")))
        assertFalse(decision is ApprovalDecision.Approved)
    }

    @Test
    fun `safe executable without a rule match stays policy-approved`() {
        val gate = AgentApprovalGate(argPolicy(argRules = listOf(echoRule), safe = setOf("/bin/echo")))
        val decision = gate.evaluate(spec("id-3", "/bin/echo", args = listOf("plain", "words")))
        decision as ApprovalDecision.Approved
        assertEquals(ApprovalOrigin.TRUSTED_POLICY, decision.origin)
    }

    @Test
    fun `model hints do not override a matched argument rule`() {
        val gate = AgentApprovalGate(
            argPolicy(argRules = listOf(echoRule), safe = setOf("/bin/echo"))
        )
        val decision = gate.evaluate(
            spec(
                "id-4", "/bin/echo", args = listOf("--dangerous"),
                hints = setOf(SensitiveOp.NETWORK_EGRESS)
            )
        )
        decision as ApprovalDecision.ApprovalRequired
        assertEquals(
            ApprovalReason.Sensitive(SensitiveOp.PROCESS_MANAGEMENT),
            decision.reason
        )
    }

    @Test
    fun `safe executable with hints and no rule match keeps hint gating`() {
        val gate = AgentApprovalGate(
            argPolicy(argRules = listOf(echoRule), safe = setOf("/bin/echo"))
        )
        val decision = gate.evaluate(
            spec(
                "id-5", "/bin/echo", args = listOf("plain"),
                hints = setOf(SensitiveOp.PRIVILEGED_EXECUTION)
            )
        )
        decision as ApprovalDecision.ApprovalRequired
        assertEquals(
            ApprovalReason.ModelHints(setOf(SensitiveOp.PRIVILEGED_EXECUTION)),
            decision.reason
        )
    }

    @Test
    fun `argument rule applies to unknown executables with a named operation`() {
        val gate = AgentApprovalGate(argPolicy(argRules = listOf(echoRule)))
        val decision = gate.evaluate(spec("id-6", "/bin/echo", args = listOf("--dangerous")))
        decision as ApprovalDecision.ApprovalRequired
        assertEquals(ApprovalReason.Sensitive(SensitiveOp.PROCESS_MANAGEMENT), decision.reason)
        assertFalse(decision.reason is ApprovalReason.AmbiguousExecutable)
    }

    @Test
    fun `unmatched unknown executable still fails closed to ambiguity`() {
        val gate = AgentApprovalGate(argPolicy(argRules = listOf(echoRule)))
        val decision = gate.evaluate(spec("id-7", "/bin/other"))
        decision as ApprovalDecision.ApprovalRequired
        assertEquals(ApprovalReason.AmbiguousExecutable, decision.reason)
    }

    @Test
    fun `first declared matching rule wins deterministically`() {
        val earlier = SensitiveArgumentRule(
            "/bin/say", listOf("push"), SensitiveOp.PROCESS_MANAGEMENT
        )
        val later = SensitiveArgumentRule(
            "/bin/say", listOf("push"), SensitiveOp.NETWORK_EGRESS
        )
        val gate = AgentApprovalGate(argPolicy(argRules = listOf(earlier, later)))
        val decision = gate.evaluate(spec("id-8", "/bin/say", args = listOf("push", "origin")))
        decision as ApprovalDecision.ApprovalRequired
        assertEquals(
            ApprovalReason.Sensitive(SensitiveOp.PROCESS_MANAGEMENT),
            decision.reason
        )
    }

    @Test
    fun `shorter argument lists never satisfy the prefix`() {
        val gate = AgentApprovalGate(argPolicy(argRules = listOf(echoRule), safe = setOf("/bin/echo")))
        val decision = gate.evaluate(spec("id-9", "/bin/echo", args = listOf("--dangerou")))
        decision as ApprovalDecision.Approved
        assertEquals(ApprovalOrigin.TRUSTED_POLICY, decision.origin)
    }

    @Test
    fun `argument prefix must match positionally, not as a set`() {
        val gate = AgentApprovalGate(argPolicy(argRules = listOf(echoRule), safe = setOf("/bin/echo")))
        val decision = gate.evaluate(
            spec("id-10", "/bin/echo", args = listOf("noise", "--dangerous"))
        )
        decision as ApprovalDecision.Approved
        assertEquals(ApprovalOrigin.TRUSTED_POLICY, decision.origin)
    }

    @Test
    fun `sensitive executable table masks argument rules`() {
        val gitRule = SensitiveArgumentRule(
            executable = "/usr/bin/git",
            requiredArgsPrefix = listOf("status"),
            op = SensitiveOp.PROCESS_MANAGEMENT
        )
        val gate = AgentApprovalGate(
            argPolicy(
                argRules = listOf(gitRule),
                sensitive = mapOf("/usr/bin/git" to SensitiveOp.NETWORK_EGRESS)
            )
        )
        val decision = gate.evaluate(spec("id-11", "/usr/bin/git", args = listOf("status")))
        decision as ApprovalDecision.ApprovalRequired
        assertEquals(
            ApprovalReason.Sensitive(SensitiveOp.NETWORK_EGRESS),
            decision.reason
        )
    }

    @Test
    fun `hard block precedes argument rules and denies outright`() {
        val suRule = SensitiveArgumentRule(
            executable = "su",
            requiredArgsPrefix = listOf("--help"),
            op = SensitiveOp.PROCESS_MANAGEMENT
        )
        val gate = AgentApprovalGate(
            argPolicy(
                argRules = listOf(suRule),
                hardBlocked = mapOf("su" to SensitiveOp.PRIVILEGED_EXECUTION)
            )
        )
        val decision = gate.evaluate(spec("id-12", "su", args = listOf("--help")))
        decision as ApprovalDecision.Denied
        assertEquals(
            AgentCommandResult.Denied(SensitiveOp.PRIVILEGED_EXECUTION, DenialOutcome.HARD_BLOCKED),
            decision.denial
        )
    }

    @Test
    fun `ledger approval honors the exact shape when the rule basis still matches`() {
        val gate = AgentApprovalGate(argPolicy(argRules = listOf(echoRule), safe = setOf("/bin/echo")))
        val decided = spec("id-13", "/bin/echo", args = listOf("--dangerous"))
        gate.recordUserDecision(decided, approved = true)

        // Same executable, same argument list, same classification basis.
        val replay = gate.evaluate(spec("id-13", "/bin/echo", args = listOf("--dangerous")))
        replay as ApprovalDecision.Approved
        assertEquals(ApprovalOrigin.USER_DECISION, replay.origin)

        // Any other argument shape is a DIFFERENT operation: fail closed.
        val diverged = gate.evaluate(spec("id-13", "/bin/echo", args = listOf("--dangerous", "y")))
        diverged as ApprovalDecision.ApprovalRequired

        // And a rule-mismatched shape of the same executable stays policy-safe.
        val other = gate.evaluate(spec("id-13c", "/bin/echo", args = listOf("quiet")))
        other as ApprovalDecision.Approved
        assertEquals(ApprovalOrigin.TRUSTED_POLICY, other.origin)
    }

    @Test
    fun `default policy ships no argument rules and keeps hard blocks`() {
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        val unknown = gate.evaluate(spec("id-14", "/bin/whatever", args = listOf("--dangerous")))
        unknown as ApprovalDecision.ApprovalRequired
        assertEquals(ApprovalReason.AmbiguousExecutable, unknown.reason)

        val su = gate.evaluate(spec("id-15", "su"))
        su as ApprovalDecision.Denied
        assertEquals(
            AgentCommandResult.Denied(SensitiveOp.PRIVILEGED_EXECUTION, DenialOutcome.HARD_BLOCKED),
            su.denial
        )
        assertEquals(0, ApprovalPolicy.DEFAULT.sensitiveArgumentRules.size)
    }

    @Test
    fun `rule construction invariants reject malformed rules`() {
        val blankExecutable = runCatching {
            SensitiveArgumentRule("", listOf("a"), SensitiveOp.NETWORK_EGRESS)
        }
        assertTrue(blankExecutable.isFailure)
        assertTrue(blankExecutable.exceptionOrNull() is IllegalArgumentException)

        val emptyPrefix = runCatching {
            SensitiveArgumentRule("/bin/echo", emptyList(), SensitiveOp.NETWORK_EGRESS)
        }
        assertTrue(emptyPrefix.isFailure)

        val nulInExecutable = runCatching {
            SensitiveArgumentRule("/bin/\u0000", listOf("a"), SensitiveOp.NETWORK_EGRESS)
        }
        assertTrue(nulInExecutable.isFailure)

        val nulInPrefix = runCatching {
            SensitiveArgumentRule("/bin/echo", listOf("\u0000"), SensitiveOp.NETWORK_EGRESS)
        }
        assertTrue(nulInPrefix.isFailure)
    }
}
