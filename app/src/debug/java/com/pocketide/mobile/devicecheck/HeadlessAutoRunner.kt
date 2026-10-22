package com.pocketide.mobile.devicecheck

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared auto-run entry point for the P3e harness. Every mode drives the
 * same production code paths (real Monaco probe engine WebView, real
 * MonacoEditorStore files, real native PTY chains); only the evidence
 * groups that REQUIRE a human hand (keyboard/IME B group) differ:
 *
 *  - HEADLESS_SERVICE / ACTIVITY_AUTO run without user touches; the B group
 *    is recorded as SKIP with an explicit reason so the report never fakes
 *    interactive evidence;
 *  - MANUAL_BUTTON is the interactive run (Run all button) where the user
 *    supplies the touch evidence.
 */
object HeadlessAutoRunner {

    const val LAST_REPORT_FILE = "last-report.json"

    private val inFlight = AtomicBoolean(false)

    fun isRunning(): Boolean = inFlight.get()

    suspend fun runNow(
        context: Context,
        mode: RunMode,
        live: LiveSnapshot? = null
    ): DeviceValidationReport? {
        if (!inFlight.compareAndSet(false, true)) return null
        try {
            return execute(context, mode, live)
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun execute(context: Context, mode: RunMode, live: LiveSnapshot?): DeviceValidationReport {
        Diagnostics.log(context, "runner", "execute start mode=$mode ${Diagnostics.debugBuildInfo()}")
        val root = File(context.filesDir, "p3e-validation")
        root.mkdirs()
        val manual = mode == RunMode.MANUAL_BUTTON

        val facts = withContext(Dispatchers.IO) {
            DeviceFacts.collect(context).toMutableMap()
        }
        facts["run.mode"] = mode.name
        Diagnostics.log(context, "runner", "facts collected: ${facts.entries.joinToString(",") { "${it.key}=${it.value}" }}")

        val editorProbe: EditorProbe? = try {
            Diagnostics.log(context, "runner", "creating Monaco probe engine…")
            val holder = MonacoProbeEngine()
            withContext(Dispatchers.Main) {
                holder.createProbeWebView(context)
            }
            Diagnostics.log(context, "runner", "Monaco probe engine created ok")
            MonacoAutoProbe(holder, root)
        } catch (t: Throwable) {
            Diagnostics.log(
                context, "runner/fatal", "Monaco probe creation failed: " +
                    android.util.Log.getStackTraceString(t)
            )
            null
        }

        val ptyProbe: PtyProbe? = try {
            HardwarePtyProbe(
                terminalViewModel = com.pocketide.mobile.terminal.TerminalViewModel(
                    root,
                    { com.pocketide.mobile.terminal.PtyLinuxHandle() },
                    kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
                ),
                workspaceRootDir = root
            )
        } catch (t: Throwable) {
            Diagnostics.log(
                context, "runner/fatal", "PTY probe creation failed: " +
                    android.util.Log.getStackTraceString(t)
            )
            null
        }

        val report: DeviceValidationReport
        val collectedSoFar = mutableListOf<DeviceCheckGroup>()
        try {
            report = DeviceValidationReport(
                deviceTestedAtIso = DeviceFacts.nowIso(),
                facts = facts,
                groups = runValidationMatrix(
                    editorProbe = editorProbe,
                    ptyProbe = ptyProbe,
                    snapshots = live ?: autoSnapshots(context),
                    facts = facts,
                    manualEvidence = manual,
                    storeProbe = editorProbe as? MonacoAutoProbe,
                    onGroup = { group ->
                        collectedSoFar.add(group)
                        Diagnostics.log(context, "runner", "group done: ${group.summaryMetrics()}")
                        // Incremental persistence: even a mid-matrix crash leaves every
                        // completed group on record.
                        withContext(Dispatchers.IO) {
                            val json = DeviceValidationReport(
                                deviceTestedAtIso = DeviceFacts.nowIso(),
                                facts = facts + mapOf("run.partial" to "true"),
                                groups = collectedSoFar.toList()
                            ).serialize()
                            File(root, LAST_REPORT_FILE).writeText(json)
                            json
                        }
                    }
                )
            )
        } catch (t: Throwable) {
            Diagnostics.log(
                context, "runner/fatal", "matrix aborted: " +
                    android.util.Log.getStackTraceString(t)
            )
            throw t
        }
        val json = report.serialize()
        Diagnostics.log(context, "runner", "matrix complete: ${report.summaryLine()} (json ${json.length} bytes)")
        withContext(Dispatchers.IO) {
            File(root, LAST_REPORT_FILE).writeText(json)
        }
        Diagnostics.log(context, "runner", "last-report persisted")
        try {
            ReportPusher.push(context, json)
            Diagnostics.log(context, "runner", "report push dispatched (${json.length} bytes)")
        } catch (t: Throwable) {
            Diagnostics.log(
                context, "runner/push-error", "report push failed: " +
                    android.util.Log.getStackTraceString(t)
            )
        }
        (editorProbe as? MonacoAutoProbe)?.detachEngine()
        Diagnostics.log(context, "runner", "execute end mode=$mode")
        return report
    }

    /** Auto snapshots: no live editor screen exists, so UI-dependent values are empty. */
    private fun autoSnapshots(context: Context): LiveSnapshot {
        return object : LiveSnapshot {
            override fun visibleEditorDocument(): String? = null
            override fun visibleEditorDirty(): Boolean = false
            override fun visibleEditorSaveMessage(): String? = null
            override fun consumeImeSeen(): Boolean = false
            override fun networkSample(): String = DeviceFacts.sampleNetworkState(context)
        }
    }
}

enum class RunMode { MANUAL_BUTTON, ACTIVITY_AUTO, HEADLESS_SERVICE }

/**
 * Editor probe for auto runs: reuses [MonacoEditorProbe] engine access and
 * adds the headless-capable store round trip (real files, real serializer).
 */
class MonacoAutoProbe(
    private val holder: MonacoProbeEngine,
    private val storeRoot: File
) : EditorProbe by MonacoEditorProbe(holder), StoreProbe {

    override suspend fun storeRoundTrip(content: String): String? {
        val store = com.pocketide.mobile.editor.MonacoEditorStore(storeRoot)
        store.save(
            HardwarePtyProbe.PROJECT_NAME,
            com.pocketide.mobile.editor.MonacoDocument(content, com.pocketide.mobile.editor.MonacoDocument.DEFAULT_LANGUAGE)
        )
        val loaded = store.load(HardwarePtyProbe.PROJECT_NAME) ?: return null
        return loaded.content
    }

    fun detachEngine() {
        holder.engine.detach()
    }
}
