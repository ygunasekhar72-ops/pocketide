package com.pocketide.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCommandRegistryTest {

    private fun template(
        catalogId: String = "readme-echo",
        executable: String = "/bin/echo",
        templateArgs: List<String> = listOf("hello"),
        allowExtraArgs: Boolean = false
    ) = AgentCommandTemplate(catalogId, executable, templateArgs, allowExtraArgs)

    private fun proposal(
        catalogId: String = "readme-echo",
        executable: String = "/bin/echo",
        args: List<String> = listOf("hello")
    ) = AgentCommandSpec(
        executable = executable,
        args = args,
        catalogId = catalogId
    )

    @Test
    fun `registered template lookup returns registered entry`() {
        val registry = AgentCommandRegistry()
        val t = template()
        registry.register(t)
        // The registry stores a defensive snapshot: content-equal, but the
        // caller's instance must not be aliased into registry state.
        assertEquals(t, registry.lookup("readme-echo"))
    }

    @Test
    fun `lookup of unknown catalogId is null`() {
        val registry = AgentCommandRegistry()
        registry.register(template())
        assertNull(registry.lookup("anything-else"))
        assertNull(registry.lookup(""))
    }

    @Test
    fun `empty registry rejects any proposal as unknown`() {
        val decision = AgentCommandRegistry().validateProposal(proposal())
        assertTrue(decision is ProposalDecision.Invalid)
        decision as ProposalDecision.Invalid
        assertEquals(ProposalRejectionReason.UNKNOWN_COMMAND, decision.reason)
        assertTrue(decision.detail.isNotBlank())
    }

    @Test
    fun `valid proposal accepted with registered template attached`() {
        val registry = AgentCommandRegistry()
        val t = template()
        registry.register(t)
        val decision = registry.validateProposal(proposal())
        assertTrue(decision is ProposalDecision.Valid)
        decision as ProposalDecision.Valid
        assertEquals(t, decision.template)
        assertEquals(listOf("hello"), decision.spec.args)
    }

    @Test
    fun `proposal with unregistered catalogId rejected as unknown`() {
        val registry = AgentCommandRegistry()
        registry.register(template())
        val decision = registry.validateProposal(proposal(catalogId = "not-registered"))
        decision as ProposalDecision.Invalid
        assertEquals(ProposalRejectionReason.UNKNOWN_COMMAND, decision.reason)
    }

    @Test
    fun `proposal with mismatched executable rejected`() {
        val registry = AgentCommandRegistry()
        registry.register(template())
        val decision = registry.validateProposal(proposal(executable = "/bin/other"))
        decision as ProposalDecision.Invalid
        assertEquals(ProposalRejectionReason.EXECUTABLE_MISMATCH, decision.reason)
        assertTrue(decision.detail.contains("/bin/other"))
    }

    @Test
    fun `extra arguments rejected when template disallows tail args`() {
        val registry = AgentCommandRegistry()
        registry.register(template(allowExtraArgs = false))
        val decision = registry.validateProposal(proposal(args = listOf("hello", "extra")))
        decision as ProposalDecision.Invalid
        assertEquals(ProposalRejectionReason.ARGUMENT_MISMATCH, decision.reason)
    }

    @Test
    fun `exact argument order difference rejected when template is exact`() {
        val registry = AgentCommandRegistry()
        registry.register(template(templateArgs = listOf("a", "b")))
        val decision = registry.validateProposal(proposal(args = listOf("b", "a")))
        decision as ProposalDecision.Invalid
        assertEquals(ProposalRejectionReason.ARGUMENT_MISMATCH, decision.reason)
    }

    @Test
    fun `tail arguments accepted when template allows extras`() {
        val registry = AgentCommandRegistry()
        registry.register(template(templateArgs = listOf("hello"), allowExtraArgs = true))
        val decision = registry.validateProposal(proposal(args = listOf("hello", "world", "!")))
        assertTrue(decision is ProposalDecision.Valid)
    }

    @Test
    fun `arguments shorter than the registered prefix rejected even with tail allowance`() {
        val registry = AgentCommandRegistry()
        registry.register(template(templateArgs = listOf("hello", "world"), allowExtraArgs = true))
        val decision = registry.validateProposal(proposal(args = listOf("hello")))
        decision as ProposalDecision.Invalid
        assertEquals(ProposalRejectionReason.ARGUMENT_MISMATCH, decision.reason)
    }

    @Test
    fun `duplicate registration throws and keeps the first template`() {
        val registry = AgentCommandRegistry()
        val first = template()
        registry.register(first)
        // An identical and then a different template with the same id both fail,
        // and the original registration is never replaced.
        for (attempt in listOf({ template() }, { template(executable = "/bin/other") })) {
            try {
                registry.register(attempt())
                throw AssertionError("duplicate registration must throw")
            } catch (_: IllegalArgumentException) {
                // deterministic: always throws
            }
        }
        assertEquals(first, registry.lookup("readme-echo"))
    }

    @Test
    fun `mutating the original argument list after registration does not alter validation`() {
        val registry = AgentCommandRegistry()
        val backing = ArrayList<String>()
        backing.add("a")
        backing.add("b")
        registry.register(
            AgentCommandTemplate("mut-orig", "/bin/tool", backing)
        )
        // Hostile mutation of the input collection after the fact.
        backing.clear()
        backing.add("evil")
        // Registered behavior is still the snapshotted arg prefix.
        val originalShape = registry.validateProposal(
            AgentCommandSpec(executable = "/bin/tool", args = listOf("a", "b"), catalogId = "mut-orig")
        )
        assertTrue(originalShape is ProposalDecision.Valid)
        val mutatedShape = registry.validateProposal(
            AgentCommandSpec(executable = "/bin/tool", args = listOf("evil"), catalogId = "mut-orig")
        )
        mutatedShape as ProposalDecision.Invalid
        assertEquals(ProposalRejectionReason.ARGUMENT_MISMATCH, mutatedShape.reason)
    }

    @Test
    fun `mutating a looked-up template cannot alter stored behavior`() {
        val registry = AgentCommandRegistry()
        registry.register(template(templateArgs = listOf("a", "b")))
        val leaked = registry.lookup("readme-echo")!!
        // Attempt mutation of the returned snapshot. Depending on the Kotlin
        // runtime, the copied List may be immutable; either way the registry
        // must behave exactly as registered for both below validations.
        try {
            leaked.templateArgs.add("evil")
        } catch (_: UnsupportedOperationException) {
            // truly immutable snapshot copy: registry isolation trivially holds
        }
        val trueShape = registry.validateProposal(proposal(args = listOf("a", "b")))
        assertTrue(trueShape is ProposalDecision.Valid)
        val mutatedShape = registry.validateProposal(
            proposal(args = listOf("a", "b", "evil"))
        )
        mutatedShape as ProposalDecision.Invalid
        assertEquals(ProposalRejectionReason.ARGUMENT_MISMATCH, mutatedShape.reason)
    }

    @Test
    fun `mutating a valid-decision template cannot alter validation`() {
        val registry = AgentCommandRegistry()
        registry.register(template())
        val decision = registry.validateProposal(proposal())
        decision as ProposalDecision.Valid
        try {
            decision.template.templateArgs.add("evil")
        } catch (_: UnsupportedOperationException) {
            // immutable snapshot copy: nothing observable to mutate
        }
        val again = registry.validateProposal(proposal())
        assertTrue(again is ProposalDecision.Valid)
        // And the mutated shape remains a mismatch (exact-match policy).
        val mutated = registry.validateProposal(
            proposal(args = listOf("hello", "evil"))
        )
        mutated as ProposalDecision.Invalid
        assertEquals(ProposalRejectionReason.ARGUMENT_MISMATCH, mutated.reason)
    }

    @Test
    fun `malformed templates rejected at construction`() {
        for (bad in listOf(
            { AgentCommandTemplate(" ", "/bin/echo") },
            { AgentCommandTemplate("id", "") },
            { AgentCommandTemplate("id", "/bin/echo", listOf("bad\u0000")) }
        )) {
            try {
                bad()
                throw AssertionError("malformed template must be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `validation is pure and deterministic`() {
        val registry = AgentCommandRegistry()
        registry.register(template())
        val first = registry.validateProposal(proposal())
        val second = registry.validateProposal(proposal())
        assertEquals(first, second)
        assertEquals(1, registry.size)

        val firstInvalid = registry.validateProposal(proposal(catalogId = "gone"))
        val secondInvalid = registry.validateProposal(proposal(catalogId = "gone"))
        assertEquals(firstInvalid, secondInvalid)
        assertEquals(1, registry.size)
    }
}
