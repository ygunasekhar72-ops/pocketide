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
        val root = File(context.filesDir, "p3e-validation")
        root.mkdirs()
        val manual = mode == RunMode.MANUAL_BUTTON

        val facts = withContext(Dispatchers.IO) {
            DeviceFacts.collect(context).toMutableMap()
        }
        facts["run.mode"] = mode.name

        val editorProbe: EditorProbe? = try {
            val holder = MonacoProbeEngine()
            withContext(Dispatchers.Main) {
                holder.createProbeWebView(context)
            }
            MonacoAutoProbe(holder, root)
        } catch (t: Throwable) {
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
            null
        }

        val report = DeviceValidationReport(
            deviceTestedAtIso = DeviceFacts.nowIso(),
            facts = facts,
            groups = runValidationMatrix(
                editorProbe = editorProbe,
                ptyProbe = ptyProbe,
                snapshots = live ?: autoSnapshots(context),
                facts = facts,
                manualEvidence = manual,
                storeProbe = editorProbe as? MonacoAutoProbe
            )
        )
        val json = report.serialize()
        withContext(Dispatchers.IO) {
            File(root, LAST_REPORT_FILE).writeText(json)
        }
        ReportPusher.push(context, json)
        (editorProbe as? MonacoAutoProbe)?.detachEngine()
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
