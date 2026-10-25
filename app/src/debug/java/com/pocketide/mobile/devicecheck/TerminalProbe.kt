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
) : com.pocketide.mobile.devicecheck.DiagCapablePtyProbe {

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

    /**
     * Root-cause diagnostic (P3f): performs TWO raw native opens through the
     * debug-only phase-traced JNI entry, snapshots the spawned child via
     * /proc (shell cmdline, state, wchan, fds), and probes whether stdin
     * written to the master ever comes back over the native read path. All
     * bounded by the watchdog; the evidence string is the whole payload.
     */
    override suspend fun diagReport(): String? {
        val cwd = File(workspaceRootDir, "p3e-diag").apply { mkdirs() }
        val envp = arrayOf<String>()
        val report = StringBuilder()
        val bridge = com.pocketide.mobile.terminal.PtyBridge

        // --- session 1: fresh-process open, phase-traced ---
        val open1 = PtyGuard.run(12_000, { m -> trace("diag: open1 $m") }) {
            PtyDiagBridge.nativeOpenDiag(cwd.absolutePath, envp)
        }
        val h1 = open1.substringBefore('|').removePrefix("H=").split(' ').first().toLongOrNull() ?: -2
        report.append("open1=").append(open1.take(460))
        report.append(" childPid1=").append(bridge.nativeLastChildPid())

        if (h1 < 0) {
            report.append(" state=open1-failed")
            return report.toString()
        }

        // --- stdin->stdout round trip on the raw handle (proves output path) ---
        val nonce = "DIAG_${System.nanoTime()}"
        val written = PtyGuard.run(6_000, { m -> trace("diag: write $m") }) {
            bridge.nativeWrite(h1, "echo $nonce\n".encodeToByteArray())
        }
        report.append(" write=").append(written)
        val chunk = try {
            PtyGuard.run(5_000, { m -> trace("diag: read $m") }) {
                val buf = ByteArray(2048)
                val n = bridge.nativeRead(h1, buf)
                if (n > 0) buf.copyOf(n).decodeToString() else "<n=$n>"
            }
        } catch (e: Exception) {
            "<read-wedged:${e.message}>"
        }
        val echoed = chunk.contains(nonce)
        report.append(" read=").append(chunk.take(220).ifEmpty { "<empty>" })
        report.append(" echoed=").append(echoed)
        // Post-round-trip child state: did the shell get into a read syscall?
        report.append(' ').append(procSantry(bridge.nativeLastChildPid()))
        report.append(' ')
        report.append("postSnapshot=").append(procSnapshot(bridge.nativeLastChildPid()).take(360))

        // destroy session 1 (bounded)
        report.append(" destroy1=").append(
            try {
                PtyGuard.run(8_000, { m -> trace("diag: destroy1 $m") }) { bridge.nativeDestroy(h1) }
            } catch (e: Exception) { "wedged:${e.message}" }
        )

        // --- session 2: second open in this process (wedge localization) ---
        val open2 = try {
            PtyGuard.run(12_000, { m -> trace("diag: open2 $m") }) {
                PtyDiagBridge.nativeOpenDiag(cwd.absolutePath, envp)
            }
        } catch (e: Exception) {
            if (e.message?.contains("wedged beyond") == true) "<OPEN2-WEDGED>" else "<open2-exc:${e.message}>"
        }
        report.append(" open2=").append(open2.take(400))
        if (open2.startsWith("H=") && !open2.contains("H=-1")) {
            val h2 = open2.substringBefore('|').removePrefix("H=").split(' ').first().toLongOrNull() ?: -3
            report.append(" childPid2=").append(bridge.nativeLastChildPid())
            if (h2 > 0) {
                report.append(" destroy2=").append(
                    try {
                        PtyGuard.run(8_000, { m -> trace("diag: destroy2 $m") }) { bridge.nativeDestroy(h2) }
                    } catch (e: Exception) { "wedged:${e.message}" }
                )
            }
        }
        return report.toString()
    }

    /** Reads selected /proc/<pid> fields readable by the parent (same-uid child). */
    private fun procSnapshot(pid: Long): String {
        if (pid <= 0) return "pid-invalid"
        val parts = StringBuilder("pid=$pid")
        fun readTxt(file: String, cap: Int): String =
            try { File("/proc/$pid/$file").readText().trim().take(cap) } catch (e: Exception) { "?${e::class.simpleName}" }

        parts.append(" cmd=").append(readTxt("cmdline", 120))
        val kept = listOf("State", "PPid", "Uid", "Tracer", "NSpid")
        val status = try { File("/proc/$pid/status").readLines().filter { l -> kept.any { l.startsWith(it) } }.joinToString(";") } catch (e: Exception) { "?${e::class.simpleName}" }
        parts.append(" status=").append(status)
        parts.append(" wchan=").append(readTxt("wchan", 80))
        val fds = try {
            File("/proc/$pid/fd").listFiles()?.take(10)?.joinToString(",") { f ->
                val target = try { File(f.absolutePath).canonicalFile.path.substringAfterLast('/') } catch (e: Exception) { "?" }
                "${f.name}→$target"
            }
        } catch (e: Exception) { "?${e::class.simpleName}" }
        parts.append(" fds=").append(fds)
        return parts.toString()
    }

    private fun procSantry(pid: Long): String =
        try { "wchan=" + File("/proc/$pid/wchan").readText().trim() } catch (e: Exception) { "" }

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
