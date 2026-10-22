package com.pocketide.mobile.devicecheck

import com.pocketide.mobile.editor.MonacoJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit contract for the P3e validation report model: closed status set,
 * deterministic serialization, and JSON validity that survives a full parse.
 */
class ValidationReportTest {

    @Test
    fun `statuses are a closed vocabulary`() {
        assertEquals(
            setOf("PASS", "FAIL", "BLOCKED", "SKIP"),
            DeviceCheckResult.STATUSES
        )
    }

    @Test
    fun `invalid status is rejected`() {
        var rejected = false
        try {
            DeviceCheckResult("id", "title", "WEIRD", "evidence")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun `summary counts statuses correctly`() {
        val report = DeviceValidationReport(
            deviceTestedAtIso = "2026-10-22T10:00:00.000Z",
            facts = emptyMap(),
            groups = listOf(
                DeviceCheckGroup(
                    "G1", "Group one",
                    listOf(
                        DeviceCheckResult("a", "A", DeviceCheckResult.STATUS_PASS, "e1"),
                        DeviceCheckResult("b", "B", DeviceCheckResult.STATUS_FAIL, "e2")
                    )
                ),
                DeviceCheckGroup(
                    "G2", "Group two",
                    listOf(
                        DeviceCheckResult("c", "C", DeviceCheckResult.STATUS_BLOCKED, "e3"),
                        DeviceCheckResult("d", "D", DeviceCheckResult.STATUS_SKIP, "e4"),
                        DeviceCheckResult("e", "E", DeviceCheckResult.STATUS_PASS, "e5")
                    )
                )
            )
        )
        assertEquals("2 pass / 1 fail / 1 blocked / 1 skip", report.summaryLine())
    }

    @Test
    fun `serialization is byte-identical for equal reports`() {
        fun buildReport() = DeviceValidationReport(
            deviceTestedAtIso = "2026-10-22T10:00:00.000Z",
            facts = linkedMapOf(
                "device.model" to "moto g64 5G",
                "device.os" to "Android 15 (SDK 35)"
            ),
            groups = listOf(
                DeviceCheckGroup(
                    "A", "Airplane",
                    listOf(
                        DeviceCheckResult("A.roundtrip", "roundtrip", DeviceCheckResult.STATUS_PASS, "echo exact ✓ 'quote' \"dq\"")
                    )
                )
            )
        )
        assertEquals(buildReport().serialize(), buildReport().serialize())
    }

    @Test
    fun `serialized output is valid JSON and round-trips`() {
        val report = DeviceValidationReport(
            deviceTestedAtIso = "2026-10-22T10:00:00.000Z",
            facts = linkedMapOf(
                "device.model" to "moto g64 \"5G\"",
                "note" to "line1\nline2\ttab"
            ),
            groups = listOf(
                DeviceCheckGroup(
                    "PTY", "Native PTY",
                    listOf(
                        DeviceCheckResult("PTY.start", "start", DeviceCheckResult.STATUS_PASS, "session live")
                    )
                )
            )
        )
        val raw = report.serialize()
        val parsed = MonacoJson.parse(raw)
        assertNotNull("serialized report must parse as JSON", parsed)
        val obj = parsed as MonacoJson.Json.Obj
        assertEquals(MonacoJson.Json.Str("p3e-validation-1"), obj.entries["schema"])
        val facts = obj.entries["facts"] as MonacoJson.Json.Obj
        assertEquals(report.facts["device.model"], (facts.entries["device.model"] as MonacoJson.Json.Str).value)
    }
}
