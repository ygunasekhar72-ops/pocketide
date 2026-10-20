package com.pocketide.mobile.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural security audit: the T2 policy plane must contain NO execution
 * primitives. The approval gate and registry are pure decision code; actual
 * dispatch belongs to separately-reviewed transport milestones (T3+). If the
 * production sources gain an execution primitive, this test fails.
 *
 * The audit reads source text relative to the Gradle working directory of the
 * unit-test task (the app module directory on both local Gradle runs and CI).
 */
class AgentPolicyNoExecutionAuditTest {

    private val productionFiles = listOf(
        "app/src/main/java/com/pocketide/mobile/agent/AgentApprovalGate.kt",
        "app/src/main/java/com/pocketide/mobile/agent/AgentCommandRegistry.kt",
        "app/src/main/java/com/pocketide/mobile/agent/AgentDecisionLedger.kt",
        "app/src/main/java/com/pocketide/mobile/agent/AgentCommandSpec.kt",
        "app/src/main/java/com/pocketide/mobile/agent/AgentCommandResult.kt",
        "app/src/main/java/com/pocketide/mobile/agent/SensitiveOp.kt"
    )

    private val executionPrimitives = listOf(
        "ProcessBuilder",
        "Runtime.get",
        ".exec(",
        "execve",
        "pty_",
        "PtyBridge"
    )

    @Test
    fun `agent policy plane declares no execution primitives`() {
        val violations = mutableListOf<String>()
        for (path in productionFiles) {
            val source = loadSource(path)
            for (token in executionPrimitives) {
                if (source.contains(token)) violations += "$path contains '$token'"
            }
        }
        assertTrue(
            "execution primitives found in policy plane: $violations",
            violations.isEmpty()
        )
    }

    private fun loadSource(path: String): String {
        // Try the Gradle module working dir (app/) first, then repo root.
        val f = File(path)
        if (f.isFile) return f.readText()
        val fallback = File("../$path")
        if (fallback.isFile) return fallback.readText()
        throw AssertionError("cannot read production source for audit: $path")
    }
}
