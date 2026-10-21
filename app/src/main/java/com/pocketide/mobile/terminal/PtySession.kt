package com.pocketide.mobile.terminal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Engine driving exactly ONE session lifecycle on top of a [PtyHandle].
 * Construct one instance per session; multiple concurrent sessions are a
 * later-phase concern. The native coroutine scope is supplied by the caller,
 * which also defines the session's lifetime: cancelling that scope tears the
 * session down (see contract below). No UI, no renderer, no shell simulation.
 *
 * State transitions are performed exclusively through the pure reducer
 * [PtySessionState.onEvent]; this class only orchestrates real I/O around it.
 *
 * Cancellation & cleanup contract:
 *  - [PtyConnection.destroy] is invoked exactly once per successful open,
 *    guarded by [destroyed], on every path: normal exit, stop, I/O failure
 *    after open, and outer-scope cancellation. It is never called when
 *    [PtyHandle.open] failed (nothing was opened).
 *  - `stop()` while `Starting` is deferred: when `open` completes, the state
 *    is re-checked and `requestExit()` is issued immediately.
 *  - Writes after the session is live→stopping/terminal are dropped without
 *    throwing; stdin failures during teardown are deliberately swallowed.
 *  - `output` is collector-coupled (SharedFlow): chunks are delivered only
 *    while something collects. The P3 renderer is the intended consumer.
 */
class PtySession(
    private val handle: PtyHandle,
    private val scope: kotlinx.coroutines.CoroutineScope
) {

    private val stateMutex = Mutex()
    private val _state = MutableStateFlow<PtySessionState>(PtySessionState.NotStarted)
    val state: StateFlow<PtySessionState> = _state.asStateFlow()

    private val _output = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = OUTPUT_BUFFER_CHUNKS,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    /** Combined child output, chunked, order-preserving, collector-coupled. */
    val output: SharedFlow<ByteArray> = _output.asSharedFlow()

    @Volatile
    private var connection: PtyConnection? = null
    private val destroyDone = AtomicBoolean(false)
    @Volatile
    private var sessionJob: Job? = null

    /** Starts the session. Illegal while not [PtySessionState.NotStarted]; later calls are no-ops. */
    suspend fun start(spec: PtySpec) {
        val accepted = stateMutex.withLock {
            val before = _state.value
            val next = before.onEvent(PtySessionEvent.StartRequested)
            val took = next !== before
            _state.value = next
            took && next === PtySessionState.Starting
        }
        if (!accepted) return

        val job = scope.launch {
            var conn: PtyConnection? = null
            try {
                val opened = handle.open(spec)
                conn = opened
                connection = opened
                transition(PtySessionEvent.StartSucceeded)
                if (_state.value === PtySessionState.Stopping) {
                    // A stop was requested during startup - honor it right away.
                    runCatching { opened.requestExit() }
                }
                val reader = launch {
                    for (chunk in opened.output) _output.emit(chunk)
                }
                val exitCode = try {
                    opened.awaitExit()
                } finally {
                    reader.cancel()
                    reader.join()
                }
                release(opened)
                transition(PtySessionEvent.ProcessExited(exitCode))
                conn = null
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                transition(
                    PtySessionEvent.StartFailed(e.message ?: e.javaClass.simpleName)
                )
            } finally {
                // Failed open (conn == null) must NOT destroy; teardown otherwise.
                conn?.let { release(it) }
            }
        }
        sessionJob = job
    }

    /** Asks the session to end. No-ops outside Starting/Running; safe to call repeatedly. */
    suspend fun stop() {
        val accepted = stateMutex.withLock {
            val before = _state.value
            val next = before.onEvent(PtySessionEvent.StopRequested)
            _state.value = next
            next !== before
        }
        if (!accepted) return
        val conn = connection ?: return
        runCatching { conn.requestExit() }
    }

    /** Writes stdin bytes. Dropped (never throws) when nothing can receive them. */
    suspend fun write(bytes: ByteArray) {
        val live = when (_state.value) {
            PtySessionState.Running, PtySessionState.Stopping -> true
            else -> false
        }
        if (!live) return
        val conn = connection ?: return
        runCatching { conn.write(bytes) }
    }

    /**
     * Propagates a pty window resize to the live session. Returns false
     * (no propagation) when the session is not live or the transport cannot
     * resize; errors on the propagating connection are absorbed to mirror
     * the engine's drop-late-write semantics.
     */
    suspend fun resize(rows: Int, cols: Int): Boolean {
        val live = when (_state.value) {
            PtySessionState.Running, PtySessionState.Stopping -> true
            else -> false
        }
        if (!live) return false
        val conn = connection ?: return false
        return try {
            conn.resize(rows, cols)
        } catch (e: Exception) {
            false
        }
    }

    /** Visible for tests; also used by callers shutting the owning scope down prematurely. */
    fun cancelJob() {
        sessionJob?.cancel()
    }

    private suspend fun transition(event: PtySessionEvent) {
        stateMutex.withLock { _state.value = _state.value.onEvent(event) }
    }

    private suspend fun release(conn: PtyConnection) {
        if (destroyDone.compareAndSet(false, true)) {
            try {
                // NonCancellable: cleanup runs even when the owning scope was
                // cancelled (finally blocks execute in a cancelled context).
                withContext(NonCancellable) { conn.destroy() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // destroy() owns native cleanup and must be safe; engine-side
                // guard keeps exactly-once semantics even if it throws late.
            }
        }
    }

    private companion object {
        /** Chunked output pacing before slow collectors start blocking the read loop. */
        const val OUTPUT_BUFFER_CHUNKS = 256
    }
}
