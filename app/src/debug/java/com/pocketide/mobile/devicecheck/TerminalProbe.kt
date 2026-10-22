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
    private val workspaceRootDir: File
) : PtyProbe {

    override suspend fun start(): String? {
        terminalViewModel.start(PROJECT_NAME)
        val live = withTimeoutOrNull(20_000) {
            terminalViewModel.uiState.first { it.live }
            true
        }
        return if (live == true) null else "session did not reach live state"
    }

    override suspend fun isLive(): Boolean = terminalViewModel.uiState.value.live

    override suspend fun sendAndAwait(
        command: String,
        awaitContains: String,
        timeoutMs: Long
    ): Boolean {
        terminalViewModel.sendCommand(command)
        return withTimeoutOrNull(timeoutMs) {
            terminalViewModel.uiState.first { it.text.contains(awaitContains) }
            true
        } == true
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
        conn.write("exit 3\n".encodeToByteArray())
        conn.requestExit()
        conn.awaitExit()
    }

    override suspend fun cancel(): Boolean {
        terminalViewModel.stop()
        return withTimeoutOrNull(10_000) {
            terminalViewModel.uiState.first { !it.live }
            true
        } == true
    }

    override suspend fun openDestroyCycle(): Boolean = try {
        withRawSession(workspaceRootDir) { true }
        true
    } catch (_: PtyStartException) {
        false
    }

    override suspend fun badCwdStart(): String? = try {
        withRawSession(File("/nonexistent-void-p3e")) { "unexpectedly started" }
        null
    } catch (t: PtyStartException) {
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
