package com.pocketide.mobile.devicecheck

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import com.pocketide.mobile.terminal.PtyStartException

/**
 * Watchdog for native PTY calls on-device. JNI calls that deadlock (observed:
 * a PTY open in this app process can wedge beyond all native-internal
 * deadlines) would suspend a coroutine forever — cancellation cannot preempt
 * a blocking native frame. Each call therefore runs on a dedicated daemon
 * thread and the probe observes a hard deadline; a timed-out call is surfaced
 * as [PtyStartException] so the validation matrix always completes, and the
 * lease thread is leaked deliberately (debug-only; each leaked thread parks
 * in exactly one native frame).
 */
object PtyGuard {

    private val executor =
        Executors.newCachedThreadPool { r ->
            Thread(r).apply { isDaemon = true; name = "p3e-pty-watchdog" }
        }

    suspend fun <T> run(
        deadlineMs: Long,
        onWatchdog: (String) -> Unit,
        block: suspend () -> T
    ): T {
        val future = executor.submit(Callable { runBlocking { block() } })
        return try {
            future.get(deadlineMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            onWatchdog("native call exceeded ${deadlineMs}ms; treated as wedged")
            throw PtyStartException("native PTY call wedged beyond ${deadlineMs}ms deadline")
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause ?: e
            if (cause is PtyStartException) throw cause
            throw PtyStartException(cause.message ?: cause.javaClass.simpleName, cause)
        } finally {
            future.cancel(true)
        }
    }
}
