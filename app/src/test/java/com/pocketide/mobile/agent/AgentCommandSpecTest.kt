package com.pocketide.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentCommandSpecTest {

    @Test
    fun `nullable workdir and stdin build a valid spec`() {
        val spec = AgentCommandSpec(
            executable = "/data/data/com.termux/files/usr/bin/ls",
            args = listOf("-1"),
            workdir = null,
            stdin = null,
            catalogId = "ls|a"
        )
        assertEquals(listOf("-1"), spec.args)
        assertTrue(spec.sensitiveOpsHints.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank executable rejected`() {
        AgentCommandSpec(executable = "", catalogId = "x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `NUL byte in executable rejected`() {
        AgentCommandSpec(executable = "a\u0000b", catalogId = "x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `NUL byte in an argument rejected`() {
        AgentCommandSpec(
            executable = "/usr/bin/env",
            args = listOf("ok", "bad\u0000"),
            catalogId = "x"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive timeout rejected`() {
        AgentCommandSpec(executable = "/usr/bin/env", timeoutMs = 0L, catalogId = "x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank env-override key rejected`() {
        AgentCommandSpec(
            executable = "/usr/bin/env",
            envOverride = mapOf("" to "1"),
            catalogId = "x"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `env-override key containing = rejected`() {
        AgentCommandSpec(
            executable = "/usr/bin/env",
            envOverride = mapOf("A=B" to "1"),
            catalogId = "x"
        )
    }

    @Test
    fun `shell-like arguments preserved verbatim`() {
        val weird = listOf("a b", "with'quote", "line\nbreak")
        val spec = AgentCommandSpec(
            executable = "/usr/bin/printf",
            args = weird,
            workdir = File("/tmp"),
            catalogId = "x"
        )
        assertEquals(weird, spec.args)
    }

    @Test
    fun `advisory hints are carried without being enforced`() {
        val hints = setOf(SensitiveOp.WRITE_PROJECT_FILES, SensitiveOp.NETWORK_EGRESS)
        val spec = AgentCommandSpec(
            executable = "/usr/bin/env",
            sensitiveOpsHints = hints,
            catalogId = "x"
        )
        assertEquals(hints, spec.sensitiveOpsHints)
    }
}
