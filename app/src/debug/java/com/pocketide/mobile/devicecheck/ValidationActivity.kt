package com.pocketide.mobile.devicecheck

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pocketide.mobile.editor.EditorScreen
import com.pocketide.mobile.editor.EditorUiState
import com.pocketide.mobile.editor.EditorViewModel
import com.pocketide.mobile.editor.MonacoEditorStore
import com.pocketide.mobile.terminal.PtyLinuxHandle
import com.pocketide.mobile.terminal.TerminalScreen
import com.pocketide.mobile.terminal.TerminalViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * P3e validation activity (debug-only, declared in app/src/debug).
 *
 * Hosts the production [EditorScreen] and [TerminalScreen] for manual
 * interaction plus a programmatic probe (real Monaco bridge WebView + real
 * native PTY) that walks the validation matrix and produces the report JSON
 * pushed to the Termux repository side via the RUN_COMMAND service transport.
 *
 * Isolation: the visible screens use a store/terminal workspace rooted under
 * the app's own files dir, so validation can never damage user documents.
 */
class ValidationActivity : ComponentActivity() {

    private val harnessScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var harnessRoot: File
    private lateinit var liveEditorViewModel: EditorViewModel
    private lateinit var liveTerminalViewModel: TerminalViewModel
    private lateinit var probeEngineHolder: MonacoProbeEngine

    private val imeSeen = MutableStateFlow(false)
    private val reportJson = MutableStateFlow<String?>(null)
    private val runStatus = MutableStateFlow("idle · fill instructions below")
    private val pushStatus = MutableStateFlow("not pushed yet")

    private var imeWatcherInstalled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.installCrashRecorder(applicationContext)
        Diagnostics.log(applicationContext, "activity", "onCreate ${Diagnostics.debugBuildInfo()}")
        installImeWatcher()
        prepareHarnessWorkspace()
        setContent {
            Surface(color = MaterialTheme.colorScheme.background) {
                HarnessScreen()
            }
        }
        harnessScope.launch {
            kotlinx.coroutines.delay(1_500)
            try {
                Diagnostics.log(applicationContext, "activity/auto", "auto-run start")
                val report = HeadlessAutoRunner.runNow(
                    this@ValidationActivity,
                    RunMode.ACTIVITY_AUTO,
                    attachedEngine = probeEngineHolder
                )
                Diagnostics.log(
                    applicationContext, "activity/auto",
                    report?.let { "auto-run done · ${it.summaryLine()}" } ?: "auto-run skipped (concurrent)"
                )
                if (report != null) {
                    reportJson.value = report.serialize()
                    runStatus.value = "auto-run done · ${report.summaryLine()}"
                }
            } catch (t: Throwable) {
                Diagnostics.log(
                    applicationContext, "activity/auto/fatal", "auto-run failed: " +
                        android.util.Log.getStackTraceString(t)
                )
                runStatus.value = "auto-run failed: ${t.message}"
            }
        }
    }

    private fun installImeWatcher() {
        if (imeWatcherInstalled) return
        val decor = window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(decor) { view: View, insets: WindowInsetsCompat ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (ime.bottom > 0) imeSeen.value = true
            insets
        }
        imeWatcherInstalled = true
    }

    private fun prepareHarnessWorkspace() {
        harnessRoot = File(filesDir, "p3e-validation").apply { mkdirs() }
        liveEditorViewModel = EditorViewModel(MonacoEditorStore(harnessRoot))
        liveTerminalViewModel =
            TerminalViewModel(harnessRoot, { PtyLinuxHandle() })
        probeEngineHolder = MonacoProbeEngine()
    }

    private fun liveSnapshot(): LiveSnapshot = object : LiveSnapshot {
        override fun visibleEditorDocument(): String? =
            (liveEditorViewModel.uiState.value as? EditorUiState.Ready)?.document?.content

        override fun visibleEditorDirty(): Boolean =
            (liveEditorViewModel.uiState.value as? EditorUiState.Ready)?.dirtyWithinSession == true

        override fun visibleEditorSaveMessage(): String? =
            (liveEditorViewModel.uiState.value as? EditorUiState.Ready)?.saveMessage

        override fun consumeImeSeen(): Boolean {
            val seen = imeSeen.value
            imeSeen.value = false
            return seen
        }

        override fun networkSample(): String =
            DeviceFacts.sampleNetworkState(this@ValidationActivity)
    }

    private fun runAllChecks() {
        runStatus.value = "running…"
        harnessScope.launch {
            try {
                Diagnostics.log(applicationContext, "activity/manual", "manual run start")
                val report = HeadlessAutoRunner.runNow(
                    this@ValidationActivity,
                    RunMode.MANUAL_BUTTON,
                    live = liveSnapshot(),
                    attachedEngine = probeEngineHolder
                )
                if (report == null) {
                    runStatus.value = "a run is already in flight"
                    return@launch
                }
                Diagnostics.log(applicationContext, "activity/manual", "manual run done · ${report.summaryLine()}")
                reportJson.value = report.serialize()
                runStatus.value = "done · ${report.summaryLine()}"
            } catch (t: Throwable) {
                Diagnostics.log(
                    applicationContext, "activity/manual/fatal", "manual run failed: " +
                        android.util.Log.getStackTraceString(t)
                )
                runStatus.value = "run failed: ${t.message}"
            }
        }
    }

    private fun pushReport() {
        val json = reportJson.value
        if (json == null) {
            pushStatus.value = "nothing to push — run the checks first"
            return
        }
        try {
            ReportPusher.push(this, json)
            Diagnostics.log(applicationContext, "activity/push", "manual push dispatched (${json.length} bytes)")
            pushStatus.value = "pushed ${json.length} bytes → ${ReportPusher.REPORT_PATH}"
        } catch (t: Throwable) {
            Diagnostics.log(
                applicationContext, "activity/push-error", "manual push failed: " +
                    android.util.Log.getStackTraceString(t)
            )
            pushStatus.value = "push failed: ${t.message}"
        }
    }

    override fun onDestroy() {
        harnessScope.cancel()
        if (this::probeEngineHolder.isInitialized) {
            probeEngineHolder.engine.detach()
        }
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun HarnessScreen() {
        val runState by runStatus.collectAsState()
        val pushState by pushStatus.collectAsState()
        val report by reportJson.collectAsState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "PocketIDE Dev Self-Check (P3e)",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Steps: 1) tap the editor canvas below · 2) type 'p3e-check' · " +
                    "3) tap Save in the editor · 4) Run all · 5) Send report",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { runAllChecks() }) {
                    Text("Run all checks")
                }
                OutlinedButton(onClick = { pushReport() }) {
                    Text("Send report")
                }
            }
            Text(text = "run: $runState")
            Text(text = "push: $pushState")
            report?.let { json ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        text = "report staged (${json.length} bytes) — Send publishes via Termux RUN_COMMAND",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            HorizontalDivider()
            Text("Probe engine (real Monaco, real bridge)", style = MaterialTheme.typography.titleSmall)
            AndroidView(
                factory = { ctx -> probeEngineHolder.createProbeWebView(ctx) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
            HorizontalDivider()
            Text("Live editor (real EditorScreen)", style = MaterialTheme.typography.titleSmall)
            EditorScreen(projectName = HARNESS_PROJECT, viewModel = liveEditorViewModel)
            HorizontalDivider()
            Text("Live terminal (real TerminalScreen)", style = MaterialTheme.typography.titleSmall)
            TerminalScreen(projectName = HARNESS_PROJECT, viewModel = liveTerminalViewModel)
        }
    }

    companion object {
        const val HARNESS_PROJECT = "p3e-validation"
    }
}
