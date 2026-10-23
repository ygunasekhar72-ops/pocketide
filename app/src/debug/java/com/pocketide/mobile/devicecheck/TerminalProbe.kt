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
        val status = terminalViewModel.uiState.value
        trace(
            "start: live await result=${live == true} " +
                "status='${status.statusLabel}' textChars=${status.text.length}"
        )
        // Give the session a short settle window and record whether ANY output
        // ever arrives (a live-but-silent session is itself a real finding).
        withTimeoutOrNull(6_000) {
            terminalViewModel.uiState.first { it.text.isNotEmpty() }
        }
        val settle = terminalViewModel.uiState.value
        trace(
            "start: settle status='${settle.statusLabel}' textChars=${settle.text.length}"
        )
        // PASS requires evidence of actual shell output, not merely the
        // VM's synchronous live flag (a live-but-silent session is a real
        // PTY defect, not a start success).
        return if (live != true) {
            "session did not reach live state (status '${status.statusLabel}')"
        } else if (settle.text.isEmpty()) {
            "shell started (status '${settle.statusLabel}') but produced no output within 6s"
        } else {
            null
        }
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

    override suspend fun exitCodeEcho(): Int? = withRawSession(workspaceRootDir, "exit-status") { conn ->
        trace("exitCodeEcho: raw session opened")
        PtyGuard.run(8_000, trace) { conn.write("exit 3\n".encodeToByteArray()) }
        trace("exitCodeEcho: stdin written")
        PtyGuard.run(8_000, trace) { conn.requestExit() }
        trace("exitCodeEcho: exit requested; awaiting reaper")
        val code = PtyGuard.run(10_000, trace) { conn.awaitExit() }
        trace("exitCodeEcho: awaitExit=$code")
        code
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
        withTimeoutOrNull(30_000) {
            withRawSession(workspaceRootDir, "reopen-cycle") { _ -> true }
        } == true
    } catch (_: PtyStartException) {
        trace("openDestroyCycle: open or setup refused/wedged")
        false
    }

    override suspend fun badCwdStart(): String? = try {
        trace("badCwdStart begin")
        withRawSession(File("/nonexistent-void-p3e"), "bad-cwd") { "unexpectedly started" }
        null
    } catch (t: PtyStartException) {
        trace("badCwdStart: exception=${t::class.simpleName}:${t.message}")
        "refused: ${t.message}"
    }

    override fun notePauseResume(visibleGone: Boolean) {
        // Manual phase signals are recorded by the activity UI, not here.
    }

    /** Runs [block] against a real native connection; watchdog-bounded open. */
    private suspend fun <T> withRawSession(cwd: File, tag: String, block: suspend (PtyConnection) -> T): T {
        val sessionJob: Job = Job()
        var conn: PtyConnection? = null
        try {
            trace("withRawSession[$tag]: open begin")
            conn = withContext(Dispatchers.IO) {
                PtyGuard.run(10_000, { m -> trace("withRawSession[$tag]: $m") }) {
                    val handle: PtyHandle = PtyLinuxHandle()
                    handle.open(PtySpec(cwd = cwd, env = emptyMap()))
                }
            }
            trace("withRawSession[$tag]: open complete handle=${conn.output != null}")
            return block(conn)
        } finally {
            try {
                conn?.let { c ->
                    PtyGuard.run(8_000, { m -> trace("withRawSession[$tag]: $m") }) {
                        c.destroy()
                    }
                }
            } finally {
                sessionJob.cancel()
            }
        }
    }

    companion object {
        const val PROJECT_NAME = "p3e-validation"
    }
}
