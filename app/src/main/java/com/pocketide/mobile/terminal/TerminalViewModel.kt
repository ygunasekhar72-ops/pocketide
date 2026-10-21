package com.pocketide.mobile.terminal

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Controller for the interactive terminal screen (P3 - M2 integration).
 *
 * Owns exactly ONE [PtySession] at a time on a dedicated supervised
 * coroutine scope. Tab switches never tear the session down (the ViewModel
 * is activity-scoped and the composable is merely re-rendered); leaving the
 * whole `Activity` cancels the scope, which triggers the engine's
 * guaranteed exactly-once `destroy()` cleanup.
 *
 * Output handling: session output streams through [Utf8StreamDecoder] and
 * is appended to a bounded text buffer (head-trimmed past
 * [MAX_BUFFER_CHARS]) — the P3 PoC renderer, not a terminal-emulator
 * parser. ANSI escape sequences are rendered verbatim at this phase; a
 * real vt100-style renderer is a later-phase concern (renderer rows/cols
 * are already propagated natively through [PtySession.resize]).
 *
 * Single-threaded protocol: every mutating call funnels through
 * [scope] so state reads/writes stay deterministic.
 */
class TerminalViewModel(
    private val workspaceRoot: File,
    private val newHandle: () -> PtyHandle,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : ViewModel() {

    private val decoder = Utf8StreamDecoder()
    private var session: PtySession? = null
    private var collectors: List<Job> = emptyList()

    private val _uiState = MutableStateFlow(TerminalUiState.notStarted())
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    /** Starts a fresh session for [projectName] (null = workspace root). */
    fun start(projectName: String?) {
        if (_uiState.value.live) return
        teardownCollectors()

        val spec = PtySpec(
            cwd = WorkspacePaths.resolveWorkingDirectory(workspaceRoot, projectName)
        )
        val created = PtySession(newHandle(), scope)
        session = created
        _uiState.update {
            TerminalUiState(
                text = "",
                statusLabel = "starting…",
                live = true,
                cwdLabel = spec.cwd.absolutePath
            )
        }
        collectors = listOf(
            scope.launch {
                created.output.collect { chunk -> append(chunk) }
            },
            scope.launch {
                created.state.collect { st -> reflect(st) }
            }
        )
        scope.launch { created.start(spec) }
    }

    /** Politely ends the live session (SIGHUP per native convention). */
    fun stop() {
        val target = session ?: return
        scope.launch { target.stop() }
    }

    /** Sends a command line; disabled states drop it silently. */
    fun sendCommand(text: String) {
        if (text.isEmpty() || !_uiState.value.live) return
        if (text.isEmpty()) return
        val payload = (if (text.endsWith("\n")) text else text + "\n").encodeToByteArray()
        scope.launch { session?.write(payload) }
    }

    /** Propagates the rendered viewport size to the pty (kernel SIGWINCH). */
    fun resize(rows: Int, cols: Int) {
        scope.launch { session?.resize(rows, cols) }
    }

    fun clear() {
        _uiState.update { it.copy(text = "") }
    }

    private fun teardownCollectors() {
        collectors.forEach { it.cancel() }
        collectors = emptyList()
    }

    private fun append(chunk: ByteArray) {
        val piece = decoder.decode(chunk)
        if (piece.isEmpty()) return
        _uiState.update { st ->
            val grown = st.text + piece
            if (grown.length > MAX_BUFFER_CHARS) {
                st.copy(text = grown.substring(grown.length - MAX_BUFFER_CHARS))
            } else {
                st.copy(text = grown)
            }
        }
    }

    private fun reflect(state: PtySessionState) {
        val label = when (state) {
            PtySessionState.NotStarted -> "not started"
            PtySessionState.Starting -> "starting…"
            PtySessionState.Running -> "running"
            PtySessionState.Stopping -> "stopping…"
            is PtySessionState.Terminated -> "exited (code ${state.exitCode})"
            is PtySessionState.Failed -> "failed: ${state.reason}"
        }
        val live = state === PtySessionState.Starting ||
            state === PtySessionState.Running ||
            state === PtySessionState.Stopping
        _uiState.update {
            it.copy(statusLabel = label, live = live)
        }
    }

    // Public on purpose: test harnesses drive the same cancellation path
    // without reflection; the lifecycle owner still calls it normally.
    public override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }

    companion object {
        /**
         * Bounded PoC renderer buffer: head-trimmed. Keeps memory flat for
         * chatty programs (cat of a big file, etc.).
         */
        const val MAX_BUFFER_CHARS = 262144
    }
}

/**
 * Render state for the terminal screen; [live] means the session state is
 * Starting/Running/Stopping: command input and the stop control target a
 * real process; start is available when the session is not live.
 */
data class TerminalUiState(
    val text: String,
    val statusLabel: String,
    val live: Boolean,
    val cwdLabel: String
) {
    companion object {
        fun notStarted(): TerminalUiState = TerminalUiState(
            text = "",
            statusLabel = "not started",
            live = false,
            cwdLabel = ""
        )
    }
}
