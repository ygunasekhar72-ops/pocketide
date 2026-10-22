package com.pocketide.mobile.devicecheck

import com.pocketide.mobile.terminal.PtyConnection
import com.pocketide.mobile.terminal.PtyHandle
import com.pocketide.mobile.terminal.PtyLinuxHandle
import com.pocketide.mobile.terminal.PtySpec
import com.pocketide.mobile.terminal.PtyStartException
import com.pocketide.mobile.terminal.TerminalViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Real-device PTY probe. Two production layers are exercised:
 *  1. the full TerminalViewModel → PtySession → native chain on the live
 *     terminal screen (start, echo, resize via stty, cancel);
 *  2. the raw PtyHandle → PtyConnection chain for exit-code and
 *     open/destroy cycle semantics (the same native lib, the same handle
 *     contract, no session orchestration).
 */
class HardwarePtyProbe(
    private val terminalViewModel: TerminalViewModel,
    private val workspaceRootDir: File,
    private val trace: (String) -> Unit = {}
) : PtyProbe {

    override suspend fun start(): String? {
        trace("start: request begin")
        terminalViewModel.start(PROJECT_NAME)
        trace("start: vm.start returned; awaiting live state")
        val live = withTimeoutOrNull(20_000) {
            terminalViewModel.uiState.first { it.live }
            true
        }
        trace("start: live await result=${live == true}")
        return if (live == true) null else "session did not reach live state"
    }

    override suspend fun isLive(): Boolean = terminalViewModel.uiState.value.live

    override suspend fun sendAndAwait(
        command: String,
        awaitContains: String,
        timeoutMs: Long
    ): Boolean {
        trace("sendAndAwait begin: $awaitContains")
        terminalViewModel.sendCommand(command)
        val result = withTimeoutOrNull(timeoutMs) {
            terminalViewModel.uiState.first { it.text.contains(awaitContains) }
            true
        } == true
        trace("sendAndAwait end: $awaitContains ok=$result")
        return result
    }

    override suspend fun resizeAndConfirm(rows: Int, cols: Int): String? {
        terminalViewModel.resize(rows, cols)
        val ok = sendAndAwait(
            "stty size",
            "$rows $cols",
            timeoutMs = 15_000
        )
        return if (ok) "$rows $cols" else null
    }

    override suspend fun exitCodeEcho(): Int? = withRawSession(workspaceRootDir) { conn ->
        trace("exitCodeEcho: raw session opened")
        withTimeoutOrNull(20_000) {
            conn.write("exit 3\n".encodeToByteArray())
            conn.requestExit()
            val code = conn.awaitExit()
            trace("exitCodeEcho: awaitExit=$code")
            code
        }
    }

    override suspend fun cancel(): Boolean {
        trace("cancel begin")
        terminalViewModel.stop()
        val ok = withTimeoutOrNull(10_000) {
            terminalViewModel.uiState.first { !it.live }
            true
        } == true
        trace("cancel end ok=$ok")
        return ok
    }

    override suspend fun openDestroyCycle(): Boolean = try {
        withTimeoutOrNull(15_000) {
            withRawSession(workspaceRootDir) { conn ->
                trace("openDestroyCycle: opened, destroying…")
                true
            }
        } == true
    } catch (_: PtyStartException) {
        false
    }

    override suspend fun badCwdStart(): String? = try {
        trace("badCwdStart begin")
        withRawSession(File("/nonexistent-void-p3e")) { "unexpectedly started" }
        null
    } catch (t: PtyStartException) {
        trace("badCwdStart: exception=${t::class.simpleName}")
        "refused: ${t.message}"
    }

    override fun notePauseResume(visibleGone: Boolean) {
        // Manual phase signals are recorded by the activity UI, not here.
    }

    /** Runs [block] against a real native connection; always destroys + joins. */
    private suspend fun <T> withRawSession(cwd: File, block: suspend (PtyConnection) -> T): T {
        val sessionJob: Job = Job()
        var conn: PtyConnection? = null
        try {
            conn = withContext(sessionJob + Dispatchers.IO) {
                val handle: PtyHandle = PtyLinuxHandle()
                handle.open(PtySpec(cwd = cwd, env = emptyMap()))
            }
            return block(conn)
        } finally {
            try {
                conn?.destroy()
            } finally {
                sessionJob.cancel()
            }
        }
    }

    companion object {
        const val PROJECT_NAME = "p3e-validation"
    }
}
