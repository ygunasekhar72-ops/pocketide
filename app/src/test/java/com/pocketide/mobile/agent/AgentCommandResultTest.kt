package com.pocketide.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCommandResultTest {

    @Test
    fun `success not truncated when delivered matches original`() {
        val result = AgentCommandResult.Success(
            stdout = "hello\n",
            stderr = "",
            exitCode = 0,
            stdoutOriginalLength = 6,
            stderrOriginalLength = 0
        )
        assertFalse(result.stdoutTruncated)
        assertFalse(result.stderrTruncated)
        // Exit status is its own field, distinct from any failure kind.
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `success reports stdout truncation from original length`() {
        val result = AgentCommandResult.Success(
            stdout = "101KB",
            stderr = "warn",
            exitCode = 2,
            stdoutOriginalLength = 999_999,
            stderrOriginalLength = 4
        )
        assertTrue(result.stdoutTruncated)
        assertFalse(result.stderrTruncated)
        assertEquals(2, result.exitCode)
    }

    @Test
    fun `success allows null stderr with truncation false`() {
        val result = AgentCommandResult.Success(
            stdout = "transcript",
            stderr = null,
            exitCode = 0,
            stdoutOriginalLength = 10,
            stderrOriginalLength = 0
        )
        assertEquals(null, result.stderr)
        assertFalse(result.stderrTruncated)
    }

    @Test
    fun `success rejects invalid length metadata`() {
        // Negative original lengths.
        runCatching {
            AgentCommandResult.Success("a", null, 0, -1, 0)
        }.fold(
            onSuccess = { throw AssertionError("negative stdoutOriginalLength must be rejected") },
            onFailure = { assertTrue(it is IllegalArgumentException) }
        )
        // Delivered stdout longer than the reported original.
        runCatching {
            AgentCommandResult.Success("ab", null, 0, 1, 0)
        }.fold(
            onSuccess = { throw AssertionError("delivered stdout longer than original must be rejected") },
            onFailure = { assertTrue(it is IllegalArgumentException) }
        )
        // Delivered stderr longer than the reported original.
        runCatching {
            AgentCommandResult.Success("a", "warn", 0, 1, 0)
        }.fold(
            onSuccess = { throw AssertionError("delivered stderr longer than original must be rejected") },
            onFailure = { assertTrue(it is IllegalArgumentException) }
        )
    }

    @Test
    fun `failure taxonomy is pinned`() {
        val codes = setOf(
            AgentCommandResult.FailureCode.MISSING_APP,
            AgentCommandResult.FailureCode.PERMISSION_NEEDED,
            AgentCommandResult.FailureCode.POLICY_OFF,
            AgentCommandResult.FailureCode.INVALID_SPEC,
            AgentCommandResult.FailureCode.TIMEOUT,
            AgentCommandResult.FailureCode.TERMUX_INTERNAL,
            AgentCommandResult.FailureCode.RECEIPT_INVALID,
            AgentCommandResult.FailureCode.INTERNAL
        )
        assertEquals(codes, AgentCommandResult.FailureCode.entries.toSet())
        assertTrue(AgentCommandResult.Failure(AgentCommandResult.FailureCode.TIMEOUT, "lease expired").detail == "lease expired")
    }

    @Test
    fun `denial carries op and outcome`() {
        val denied = AgentCommandResult.Denied(
            op = SensitiveOp.PRIVILEGED_EXECUTION,
            outcome = AgentCommandResult.DenialOutcome.HARD_BLOCKED
        )
        assertEquals(SensitiveOp.PRIVILEGED_EXECUTION, denied.op)
        assertEquals(AgentCommandResult.DenialOutcome.HARD_BLOCKED, denied.outcome)
    }
}
