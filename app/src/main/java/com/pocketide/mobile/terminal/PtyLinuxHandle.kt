package com.pocketide.mobile.terminal

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * P3a implementation of [PtyHandle] over the JNI bridge.
 *
 * Responsibilities (contract per [PtyHandle]/[PtyConnection] KDoc):
 *  - open never returns a half-live connection: a failed native open is
 *    mapped to [PtyStartException] with no destroy-call obligation.
 *  - output is a bounded producer channel fed by a dedicated reader coroutine
 *    child of the session job's scope (the coroutine context captured at
 *    open time), so cancelling the session scope stops the pump; destroy
 *    also wakes the native reader, and the channel closes cleanly.
 *  - write() returns Unit to keep the P2 engine contract; if the native side
 *    delivered only part of the bytes (shutdown race), the remainder is
 *    dropped with a log line - never an exception.
 *  - destroy() is invoked exactly once by the engine; the native side is
 *    hardened additionally against duplicate/stale calls.
 */
class PtyLinuxHandle : PtyHandle {

    override suspend fun open(spec: PtySpec): PtyConnection {
        val envp = PtyEnv.build(spec)
        val handle = try {
            withContext(Dispatchers.IO) {
                PtyBridge.nativeOpen(spec.cwd.absolutePath, envp)
            }
        } catch (e: IOException) {
            throw PtyStartException(e.message ?: "pty start failed", e)
        } catch (e: Exception) {
            throw PtyStartException(e.message ?: e.javaClass.simpleName, e)
        }
        if (handle < 0) {
            throw PtyStartException("pty session start failed")
        }
        return PtyLinuxConnection(handle, coroutineContext[Job]!!)
    }
}

/**
 * One concrete connection between the engine and the native session.
 * `sessionJob` is captured at open time so the pump coroutine joins the
 * engine's session job (cancellation is honoured; native destroy remains
 * the final authority for fd closure).
 */
class PtyLinuxConnection internal constructor(
    private val nativeHandle: Long,
    sessionJob: Job?
) : PtyConnection {

    override val output: Channel<ByteArray> = Channel(capacity = 256)

    private val pumpScope = if (sessionJob != null) {
        CoroutineScope(sessionJob + Dispatchers.IO)
    } else {
        CoroutineScope(Dispatchers.IO)
    }

    init {
        pumpScope.launch {
            val buf = ByteArray(READ_CHUNK)
            try {
                val h = nativeHandle
                while (true) {
                    val n = PtyBridge.nativeRead(h, buf)
                    when {
                        n > 0 -> output.send(buf.copyOf(n))
                        n == 0 -> break      // EOF: child side is gone
                        n == -1 -> break     // destroy claimed: shutdown wakeup
                        else -> throw IOException("pty reader returned $n (stale?)")
                    }
                }
            } catch (_: IOException) {
                // Absorb read-loop failure; the engine owns failure semantics.
            } finally {
                output.close()
            }
        }
    }

    override suspend fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val delivered = withContext(Dispatchers.IO) {
            PtyBridge.nativeWrite(nativeHandle, bytes)
        }
        if (delivered < bytes.size) {
            Log.i(
                TAG,
                "partial stdin write discarded at session shutdown " +
                    "(delivered $delivered of ${bytes.size} bytes)"
            )
        }
    }

    override suspend fun resize(rows: Int, cols: Int): Boolean {
        val rc = withContext(Dispatchers.IO) {
            PtyBridge.nativeResize(nativeHandle, rows, cols)
        }
        when (rc) {
            0 -> return true
            -2 -> Log.i(TAG, "resize ignored stale handle $nativeHandle")
            -3 -> Log.i(TAG, "resize ioctl failed for handle $nativeHandle")
            -4 -> return false
        }
        return false
    }

    override suspend fun requestExit() {
        withContext(Dispatchers.IO) {
            PtyBridge.nativeRequestExit(nativeHandle)
        }
    }

    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        PtyBridge.nativeAwaitExit(nativeHandle)
    }

    override suspend fun destroy() {
        val code = withContext(Dispatchers.IO) {
            PtyBridge.nativeDestroy(nativeHandle)
        }
        when (code) {
            0 -> Unit
            -1 -> Log.i(TAG, "native destroy deferred: reader still in-flight; completes on reader exit")
            else -> Log.i(TAG, "native destroy ignored stale handle $nativeHandle")
        }
    }

    companion object {
        private const val TAG = "PocketPty"
        private const val READ_CHUNK = 8 * 1024
    }
}
