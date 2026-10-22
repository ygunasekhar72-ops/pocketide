package com.pocketide.mobile.devicecheck

import com.pocketide.mobile.editor.MonacoJson

/**
 * Structured result model for the P3e on-device validation harness.
 *
 * Pure Kotlin, no Android types: reports are built by the validation runner
 * and serialized here so they can travel through the Termux RUN_COMMAND
 * transport (extra as a string) into a file the repository side can read.
 *
 * Statuses are closed-vocabulary strings; unknown values fail serialization
 * so a broken harness can never ship silently mislabeled evidence.
 */
data class DeviceCheckResult(
    val id: String,
    val title: String,
    val status: String,
    val evidence: String
) {
    init {
        require(status in STATUSES) { "check status must be one of $STATUSES: $status" }
    }

    companion object {
        const val STATUS_PASS = "PASS"
        const val STATUS_FAIL = "FAIL"
        const val STATUS_BLOCKED = "BLOCKED"
        const val STATUS_SKIP = "SKIP"
        val STATUSES = setOf(STATUS_PASS, STATUS_FAIL, STATUS_BLOCKED, STATUS_SKIP)
    }
}

/** One scenario group (editor criteria A..F or the PTY behavior matrix). */
data class DeviceCheckGroup(
    val id: String,
    val title: String,
    val results: List<DeviceCheckResult>
)

/**
 * A complete P3e validation snapshot. [facts] carries environment identity
 * (device, OS, ABI, app build and installed-APK sha256); [groups] carry the
 * run results. Serialized deterministically (map order == insertion order)
 * so two runs of the same inputs produce byte-identical payloads.
 */
data class DeviceValidationReport(
    val deviceTestedAtIso: String,
    val facts: Map<String, String>,
    val groups: List<DeviceCheckGroup>
) {
    fun summaryLine(): String {
        var pass = 0
        var fail = 0
        var blocked = 0
        var skip = 0
        groups.forEach { group ->
            group.results.forEach { result ->
                when (result.status) {
                    DeviceCheckResult.STATUS_PASS -> pass++
                    DeviceCheckResult.STATUS_FAIL -> fail++
                    DeviceCheckResult.STATUS_BLOCKED -> blocked++
                    else -> skip++
                }
            }
        }
        return "$pass pass / $fail fail / $blocked blocked / $skip skip"
    }

    fun serialize(): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"schema\":\"p3e-validation-1\",")
        sb.append("\"deviceTestedAt\":").append(jsonStr(deviceTestedAtIso)).append(',')
        sb.append("\"facts\":{")
        facts.entries.forEachIndexed { fi, (k, v) ->
            if (fi > 0) sb.append(',')
            sb.append(jsonStr(k)).append(':').append(jsonStr(v))
        }
        sb.append("},")
        sb.append("\"summary\":").append(jsonStr(summaryLine())).append(',')
        sb.append("\"groups\":[")
        groups.forEachIndexed { gi, group ->
            if (gi > 0) sb.append(',')
            sb.append('{')
                .append(jsonStr("id")).append(':').append(jsonStr(group.id)).append(',')
                .append(jsonStr("title")).append(':').append(jsonStr(group.title)).append(',')
                .append("\"results\":[")
            group.results.forEachIndexed { ri, result ->
                if (ri > 0) sb.append(',')
                sb.append('{')
                    .append(jsonStr("id")).append(':').append(jsonStr(result.id)).append(',')
                    .append(jsonStr("title")).append(':').append(jsonStr(result.title)).append(',')
                    .append(jsonStr("status")).append(':').append(jsonStr(result.status)).append(',')
                    .append(jsonStr("evidence")).append(':').append(jsonStr(result.evidence))
                sb.append('}')
            }
            sb.append("]}")
        }
        sb.append(']')
        sb.append('}')
        return sb.toString()
    }
}

/** JSON string encoding via the editor codec (which adds its own quotes). */
private fun jsonStr(value: String): String = MonacoJson.encodeString(value)
