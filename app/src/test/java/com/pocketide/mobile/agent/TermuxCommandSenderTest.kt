package com.pocketide.mobile.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dispatch-boundary security contract, executed against the REAL registry,
 * gate, and ledger. Approval state enters only through the trusted decision
 * recorder; there is no caller-supplied approval concept in the API — these
 * tests prove that claim: pending, denied, tampered, and registry-rejected
 * proposals never reach the transport.
 */
class TermuxCommandSenderTest {

    private class FakeEnvironment(
        private val installed: Boolean = true,
        private val granted: Boolean = true
    ) : TermuxEnvironment {
        override fun isTermuxInstalled(): Boolean = installed
        override fun hasRunCommandPermission(): Boolean = granted
    }

    private class FakeTransport : TermuxTransport {
        val dispatches = ArrayList<RunCommandRequest>()
        var throwOnDispatch: RuntimeException? = null
        var respondWith: TermuxResultView = NeverArrivingView

        override suspend fun dispatch(request: RunCommandRequest): TermuxResultView {
            dispatches.add(request)
            throwOnDispatch?.let { throw it }
            return respondWith
        }
    }

    /** Suspends forever; used with the sender's own timeout budget. */
    private class HangingTransport : TermuxTransport {
        override suspend fun dispatch(request: RunCommandRequest): TermuxResultView =
            CompletableDeferred<TermuxResultView>().await()
    }

    private object NeverArrivingView : TermuxResultView {
        override fun stringOrNull(key: String): String? = null
        override fun intOrNull(key: String): Int? = null
        override fun innerBundle(key: String): TermuxResultView? = null
    }

    private class MapView(private val map: Map<String, Any?>) : TermuxResultView {
        override fun stringOrNull(key: String): String? = map[key] as? String
        override fun intOrNull(key: String): Int? = map[key] as? Int
        override fun innerBundle(key: String): TermuxResultView? {
            val nested = map[key] as? Map<*, *> ?: return null
            return MapView(nested.entries.associate { (k, v) -> k.toString() to v })
        }
    }

    private fun payloadView(inner: Map<String, Any?>): MapView = MapView(
        mapOf(TermuxPluginConstants.RESULT_BUNDLE_KEY_IS_BUNDLE to inner)
    )

    private fun newSender(
        env: TermuxEnvironment = FakeEnvironment(),
        registry: AgentCommandRegistry = AgentCommandRegistry(),
        transport: TermuxTransport = FakeTransport()
    ): TermuxCommandSender = TermuxCommandSender(
        env,
        registry,
        AgentApprovalGate(ApprovalPolicy.DEFAULT),
        transport
    )

    private fun echoSpec(
        catalogId: String = "tpl-echo",
        args: List<String> = listOf("hello"),
        executable: String = "/bin/echo"
    ): AgentCommandSpec = AgentCommandSpec(
        executable = executable,
        args = args,
        catalogId = catalogId
    )

    private fun registerEcho(
        registry: AgentCommandRegistry,
        args: List<String> = listOf("hello")
    ) {
        registry.register(AgentCommandTemplate("tpl-echo", "/bin/echo", args))
    }

    private fun asFailure(result: AgentCommandResult): AgentCommandResult.Failure =
        result as AgentCommandResult.Failure

    @Test
    fun `pending unapproved proposal never reaches the transport`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry)
        val transport = FakeTransport()
        val sender = newSender(registry = registry, transport = transport)

        val result = sender.dispatch(echoSpec())

        assertTrue(result is AgentCommandResult.Failure)
        assertEquals(FailureCode.INVALID_SPEC, asFailure(result).code)
        assertEquals(0, transport.dispatches.size)
    }

    @Test
    fun `catalog denied proposals are refused at the boundary`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry)
        val transport = FakeTransport()
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        val sender = TermuxCommandSender(FakeEnvironment(), registry, gate, transport)

        gate.recordUserDecision(echoSpec(), approved = false)

        val result = sender.dispatch(echoSpec())
        assertTrue(result is AgentCommandResult.Denied)
        val denied = result as AgentCommandResult.Denied
        assertEquals(DenialOutcome.USER_DECLINED, denied.outcome)
        assertEquals(0, transport.dispatches.size)
    }

    @Test
    fun `argument tampering after recorded approval fails closed`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry)
        val transport = FakeTransport()
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        val sender = TermuxCommandSender(FakeEnvironment(), registry, gate, transport)

        gate.recordUserDecision(echoSpec(), approved = true)

        val tampered = sender.dispatch(echoSpec(args = listOf("hello", "world")))
        assertTrue(tampered is AgentCommandResult.Failure)
        assertEquals(FailureCode.INVALID_SPEC, asFailure(tampered).code)
        assertEquals(0, transport.dispatches.size)
    }

    @Test
    fun `executable swap after recorded approval fails closed`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry)
        val transport = FakeTransport()
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        val sender = TermuxCommandSender(FakeEnvironment(), registry, gate, transport)

        gate.recordUserDecision(echoSpec(), approved = true)

        val swapped = sender.dispatch(echoSpec(executable = "/bin/sh"))
        assertTrue(swapped is AgentCommandResult.Failure)
        assertEquals(FailureCode.INVALID_SPEC, asFailure(swapped).code)
        assertEquals(0, transport.dispatches.size)
    }

    @Test
    fun `registry-unknown catalogIds are refused without contacting the transport`() = runTest {
        val transport = FakeTransport()
        val sender = newSender(transport = transport)

        val result = sender.dispatch(echoSpec(catalogId = "never-registered"))
        assertTrue(result is AgentCommandResult.Failure)
        assertEquals(FailureCode.INVALID_SPEC, asFailure(result).code)
        assertEquals(0, transport.dispatches.size)
    }

    @Test
    fun `absent Termux app is a MISSING_APP failure`() = runTest {
        val transport = FakeTransport()
        val sender = newSender(env = FakeEnvironment(installed = false), transport = transport)
        val result = sender.dispatch(echoSpec())
        assertTrue(result is AgentCommandResult.Failure)
        assertEquals(FailureCode.MISSING_APP, asFailure(result).code)
        assertEquals(0, transport.dispatches.size)
    }

    @Test
    fun `ungranted RUN_COMMAND permission is a PERMISSION_NEEDED failure`() = runTest {
        val transport = FakeTransport()
        val sender = newSender(env = FakeEnvironment(granted = false), transport = transport)
        val result = sender.dispatch(echoSpec())
        assertTrue(result is AgentCommandResult.Failure)
        assertEquals(FailureCode.PERMISSION_NEEDED, asFailure(result).code)
        assertEquals(0, transport.dispatches.size)
    }

    @Test
    fun `non-null workdir is rejected at the dispatch boundary`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry)
        val transport = FakeTransport()
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        val sender = TermuxCommandSender(FakeEnvironment(), registry, gate, transport)
        val spec = echoSpec()
        gate.recordUserDecision(spec, approved = true)

        val withWorkdir = spec.copy(workdir = java.io.File("/tmp"))
        val result = sender.dispatch(withWorkdir)
        assertTrue(result is AgentCommandResult.Failure)
        assertEquals(FailureCode.INVALID_SPEC, asFailure(result).code)
        assertEquals(0, transport.dispatches.size)
    }

    @Test
    fun `env-override carrying proposals are rejected`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry)
        val transport = FakeTransport()
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        val sender = TermuxCommandSender(FakeEnvironment(), registry, gate, transport)
        val spec = echoSpec()
        gate.recordUserDecision(spec, approved = true)

        val withOverride = spec.copy(envOverride = mapOf("PATH" to "/x"))
        val result = sender.dispatch(withOverride)
        assertTrue(result is AgentCommandResult.Failure)
        assertEquals(FailureCode.INVALID_SPEC, asFailure(result).code)
        assertEquals(0, transport.dispatches.size)
    }

    @Test
    fun `approved dispatch runs the exact approved shape with forced background`() = runTest {
        val registry = AgentCommandRegistry()
        // The approval binds to the registered template shape, so the
        // template itself must carry the exact arguments under verification;
        // production validateProposal rejects any other shape (by design).
        registerEcho(registry, args = listOf("hello world\n", "c\nd"))
        val transport = FakeTransport()
        transport.respondWith = payloadView(
            mapOf(
                TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
                TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "ok"
            )
        )
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        val sender = TermuxCommandSender(FakeEnvironment(), registry, gate, transport)
        val spec = AgentCommandSpec(
            executable = "/bin/echo",
            args = listOf("hello world\n", "c\nd"),
            catalogId = "tpl-echo"
        )
        gate.recordUserDecision(spec, approved = true)

        val result = sender.dispatch(spec)

        assertTrue(result is AgentCommandResult.Success)
        assertEquals(0, (result as AgentCommandResult.Success).exitCode)
        val sent = transport.dispatches.single()
        assertEquals("/bin/echo", sent.executablePath)
        assertEquals(listOf("hello world\n", "c\nd"), sent.arguments)
        assertEquals(true, sent.background)
        assertEquals(null, sent.stdin)
        assertEquals(null, sent.workdir)
        assertEquals("pocketide:${spec.catalogId}", sent.commandLabel)
    }

    @Test
    fun `timeout budget surfaces as TIMEOUT failure`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry)
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        val sender = TermuxCommandSender(
            FakeEnvironment(), registry, gate, HangingTransport()
        )
        val spec = echoSpec()
        gate.recordUserDecision(spec, approved = true)

        val result = sender.dispatch(spec.copy(timeoutMs = 1_000L))
        assertTrue(result is AgentCommandResult.Failure)
        assertEquals(FailureCode.TIMEOUT, asFailure(result).code)
    }

    @Test
    fun `dispatched request owns a snapshot immune to caller-owned list mutation`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry)
        val transport = FakeTransport()
        transport.respondWith = payloadView(
            mapOf(
                TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
                TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "ok"
            )
        )
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        val sender = TermuxCommandSender(FakeEnvironment(), registry, gate, transport)
        // A caller-owned MUTABLE backing list is aliased into the spec.
        val backing = ArrayList<String>()
        backing.add("hello")
        val spec = AgentCommandSpec(
            executable = "/bin/echo",
            args = backing,
            catalogId = "tpl-echo"
        )
        gate.recordUserDecision(spec, approved = true)

        val result = sender.dispatch(spec)
        // The approval bound to the exact shape evaluates clean here,
        // because the snapshot request carries the approved list.
        assertTrue(result is AgentCommandResult.Success)
        // Now mutate the caller-owned list after the fact.
        backing.clear()
        backing.add("evil")
        // The outgoing request must have captured the approved arguments,
        // not the caller's mutable alias. The spec itself (aliasing the
        // backing list) is deliberately NOT asserted as list(["hello"]):
        // after mutation it reads ["evil"] in place.
        val sent = transport.dispatches.single()
        assertEquals(listOf("hello"), sent.arguments)
        assertEquals("evil", spec.args.single())
        // And the registry-registered template shape is still authoritative.
        assertTrue(registry.validateProposal(echoSpec()) is ProposalDecision.Valid)
    }

    @Test
    fun `termux-internal error payloads map to TERMUX_INTERNAL`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry)
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        val transport = FakeTransport().apply {
            respondWith = payloadView(
                mapOf(
                    TermuxPluginConstants.RESULT_BUNDLE_KEY_ERR to 2,
                    TermuxPluginConstants.RESULT_BUNDLE_KEY_ERRMSG to "not allowed"
                )
            )
        }
        val sender = TermuxCommandSender(FakeEnvironment(), registry, gate, transport)
        val spec = echoSpec()
        gate.recordUserDecision(spec, approved = true)

        val result = sender.dispatch(spec)
        val failure = asFailure(result)
        assertEquals(FailureCode.TERMUX_INTERNAL, failure.code)
        assertEquals("not allowed", failure.detail)
    }

    @Test
    fun `malformed result payloads map to RECEIPT_INVALID`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry)
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        val sender = TermuxCommandSender(
            FakeEnvironment(), registry, gate, FakeTransport()
        )
        val spec = echoSpec()
        gate.recordUserDecision(spec, approved = true)

        val result = sender.dispatch(spec)
        assertTrue(result is AgentCommandResult.Failure)
        assertEquals(FailureCode.RECEIPT_INVALID, asFailure(result).code)
    }

    @Test
    fun `security exceptions raised at dispatch collapse to PERMISSION_NEEDED`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry)
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        val transport = FakeTransport().apply {
            throwOnDispatch = SecurityException("permission revoked since probe")
        }
        val sender = TermuxCommandSender(FakeEnvironment(), registry, gate, transport)
        val spec = echoSpec()
        gate.recordUserDecision(spec, approved = true)

        val result = sender.dispatch(spec)
        assertTrue(result is AgentCommandResult.Failure)
        assertEquals(FailureCode.PERMISSION_NEEDED, asFailure(result).code)
        assertTrue(
            "failed dispatch must not leave a phantom request record",
            transport.dispatches.single().executablePath == "/bin/echo"
        )
    }

    /**
     * M1-T4: argument-level classification refines WHY approval is needed and
     * how decisions bind, but the dispatch boundary contract is unchanged:
     * nothing unapproved reaches the transport, and an approved exact shape
     * still runs the verbatim argument list.
     */
    @Test
    fun `argument rule requires approval and dispatch is refused before it is granted`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry, args = listOf("--dangerous"))
        val transport = FakeTransport()
        val gate = AgentApprovalGate(
            ApprovalPolicy(
                sensitiveArgumentRules = listOf(
                    SensitiveArgumentRule("/bin/echo", listOf("--dangerous"), SensitiveOp.PROCESS_MANAGEMENT)
                )
            )
        )
        val sender = TermuxCommandSender(FakeEnvironment(), registry, gate, transport)
        val spec = echoSpec(args = listOf("--dangerous"))

        val refused = sender.dispatch(spec)
        assertTrue(refused is AgentCommandResult.Failure)
        assertEquals(FailureCode.INVALID_SPEC, asFailure(refused).code)
        assertEquals(0, transport.dispatches.size)

        gate.recordUserDecision(spec, approved = true)
        transport.respondWith = payloadView(
            mapOf(
                TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
                TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "ok"
            )
        )
        val approvedRun = sender.dispatch(spec)
        assertTrue(approvedRun is AgentCommandResult.Success)
        val sent = transport.dispatches.single()
        assertEquals(listOf("--dangerous"), sent.arguments)
    }

    @Test
    fun `argument rule basis divergence restarts approval after tuning`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry, args = listOf("--dangerous"))
        val transport = FakeTransport()
        val rule = SensitiveArgumentRule("/bin/echo", listOf("--dangerous"), SensitiveOp.PROCESS_MANAGEMENT)
        val ledger = AgentDecisionLedger()
        val gate = AgentApprovalGate(ApprovalPolicy(sensitiveArgumentRules = listOf(rule)), ledger)
        val sender = TermuxCommandSender(FakeEnvironment(), registry, gate, transport)
        val spec = echoSpec(args = listOf("--dangerous"))
        gate.recordUserDecision(spec, approved = true)

        // Replay under unchanged policy: the exact shape and the exact basis
        // still match, so the recorded approval is honored.
        val replay = gate.evaluate(spec)
        replay as ApprovalDecision.Approved
        assertEquals(ApprovalOrigin.USER_DECISION, replay.origin)

        // Trusted policy is retuned: a new rule is declared BEFORE the old
        // one for the same shape, so the first-match classification basis the
        // user decided on diverges. The approval must restart evaluation.
        val gate2 = AgentApprovalGate(
            ApprovalPolicy(
                sensitiveArgumentRules = listOf(
                    SensitiveArgumentRule("/bin/echo", listOf("--dangerous"), SensitiveOp.NETWORK_EGRESS),
                    rule
                )
            ),
            ledger
        )
        val replay2 = gate2.evaluate(spec)
        replay2 as ApprovalDecision.ApprovalRequired
        assertEquals(
            ApprovalReason.Sensitive(SensitiveOp.NETWORK_EGRESS),
            replay2.reason
        )

        val sender2 = TermuxCommandSender(FakeEnvironment(), registry, gate2, transport)
        val refused = sender2.dispatch(spec)
        assertTrue(refused is AgentCommandResult.Failure)
        assertEquals(FailureCode.INVALID_SPEC, asFailure(refused).code)
        assertEquals(0, transport.dispatches.size)
    }

    /**
     * M1-T5: the backend executable-path policy ([TermuxExecutablePath]) is
     * part of transport-shape validation — the stage that must fail WITHOUT
     * burning an approval record, touching the gate, or reaching the
     * transport. A relative path would be resolved inside Termux's own app
     * context in under-documented ways and is rejected fail-closed.
     *
     * The spec below violates BOTH the path policy AND the registry (its
     * catalogId is never registered), so whichever validation ran FIRST
     * owns the failure detail. The detail must name the path policy,
     * proving path-shape validation precedes the trust tiers.
     */
    @Test
    fun `relative executable path is rejected by the backend path policy before any trust decision`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry)
        val transport = FakeTransport()
        val ledger = AgentDecisionLedger()
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT, ledger)
        val sender = TermuxCommandSender(FakeEnvironment(), registry, gate, transport)

        val result = sender.dispatch(
            echoSpec(catalogId = "tpl-never-registered", executable = "echo")
        )

        assertTrue(result is AgentCommandResult.Failure)
        val failure = asFailure(result)
        assertEquals(FailureCode.INVALID_SPEC, failure.code)
        val detail = failure.detail
        assertTrue("failure must carry a detail", detail != null)
        assertTrue(
            "path policy must be the FIRST validator to fire: $detail",
            detail!!.contains("path policy")
        )
        assertEquals(0, transport.dispatches.size)
        assertNull(
            "a path-policy rejection must not burn a ledger record",
            ledger.lookup("tpl-never-registered")
        )
    }

    @Test
    fun `path traversal out of an expansion root is rejected at the boundary`() = runTest {
        val registry = AgentCommandRegistry()
        registerEcho(registry)
        val transport = FakeTransport()
        val sender = newSender(registry = registry, transport = transport)

        val result = sender.dispatch(
            echoSpec(executable = "${'$'}PREFIX/../usr/bin/ls")
        )

        assertTrue(result is AgentCommandResult.Failure)
        assertEquals(FailureCode.INVALID_SPEC, asFailure(result).code)
        assertEquals(0, transport.dispatches.size)
    }

    @Test
    fun `approved expansion-token path dispatches verbatim into the request`() = runTest {
        val registry = AgentCommandRegistry()
        // The registry binds catalogId <-> executable <-> args exactly, so
        // the template must carry the exact expansion-token form under test.
        registry.register(AgentCommandTemplate("tpl-prefix-echo", "${'$'}PREFIX/bin/echo", listOf("hello")))
        val transport = FakeTransport()
        transport.respondWith = payloadView(
            mapOf(
                TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
                TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "ok"
            )
        )
        val gate = AgentApprovalGate(ApprovalPolicy.DEFAULT)
        val sender = TermuxCommandSender(FakeEnvironment(), registry, gate, transport)
        val spec = echoSpec(
            catalogId = "tpl-prefix-echo",
            executable = "${'$'}PREFIX/bin/echo"
        )
        gate.recordUserDecision(spec, approved = true)

        val result = sender.dispatch(spec)

        assertTrue(result is AgentCommandResult.Success)
        assertEquals(0, (result as AgentCommandResult.Success).exitCode)
        val sent = transport.dispatches.single()
        assertEquals("\$PREFIX/bin/echo", sent.executablePath)
        assertEquals(listOf("hello"), sent.arguments)
    }
}
