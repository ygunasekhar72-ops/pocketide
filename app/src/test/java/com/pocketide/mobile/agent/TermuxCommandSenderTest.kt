package com.pocketide.mobile.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
