package com.pocketide.mobile.terminal

import kotlinx.coroutines.channels.ReceiveChannel
import java.io.File

/**
 * Seam between the session engine and the platform PTY implementation.
 *
 * In M1-P2 this interface has NO implementation and no Kotlin callers exercise
 * it in production code paths; the JNI/native binding in a later phase
 * implements it. Every implementation must honor the obligations documented on
 * each member. No shell behaviour lives here.
 */
interface PtyHandle {

    /**
     * Opens a PTY-backed process session with [spec]'s working directory and
     * environment overlay. Throws [PtyStartException] (and only that) when the
     * session cannot be established; on failure nothing was or needs to be
     * released - [destroy] must not be called for a failed open.
     */
    suspend fun open(spec: PtySpec): PtyConnection
}

/** Native-side process/PTY obligations, implemented by the platform layer. */
interface PtyConnection {

    /**
     * Child combined-output bytes until the child exits. Deliveries stop after
     * the final chunk; the channel is closed when [destroy] is called.
     */
    val output: ReceiveChannel<ByteArray>

    /** Writes stdin bytes. Calling after the child exited must not crash: the engine drops late writes. */
    suspend fun write(bytes: ByteArray)

    /**
     * Politely asks the child to terminate (signals/EOF; tactic is native-side).
     * Safe on an exited child, and MUST tolerate repeated calls: a
     * stop-during-startup interleaving can legitimately deliver a duplicate
     * request (direct stop plus the deferred-stop check) even though [destroy]
     * remains exactly-once per session.
     */
    suspend fun requestExit()

    /** Blocks until the child exits and returns its exit code. */
    suspend fun awaitExit(): Int

    /**
     * Releases every native resource of this connection (closes master fd,
     * reaps the child). MUST be idempotent and MUST be invoked exactly once per
     * successful [PtyHandle.open] by the engine; never after a failed open.
     */
    suspend fun destroy()
}

/** Immutable start parameters resolved by the engine, consumed by the native layer. */
data class PtySpec(
    val cwd: File,
    val env: Map<String, String> = emptyMap()
)

/** Raised when [PtyHandle.open] cannot establish the session; [message] is user-presentable. */
class PtyStartException(message: String, cause: Throwable? = null) : Exception(message, cause)
