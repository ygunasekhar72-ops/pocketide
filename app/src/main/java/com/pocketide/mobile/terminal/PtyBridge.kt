package com.pocketide.mobile.terminal

/**
 * JNI surface of the P3a native PTY (libpocketpty). Loaded lazily and only on
 * the Android runtime path; nothing in this file runs on the plain JVM unit
 * test runner. Method names map 1:1 to `Java_com_pocketide_mobile_terminal_
 * PtyBridge_nativeXxx` symbols in app/src/main/cpp/pocketpty.c.
 *
 * Native return-code conventions (documented there, mirrored here):
 *  - nativeRead: >0 bytes; 0 = EOF; -1 = destroy claimed (shutdown wakeup);
 *    -2 = stale/foreign handle; -3 = I/O error (native side throws
 *    IOException before our -3 is even seen by Kotlin).
 *  - nativeWrite: delivered byte count; remainder dropped by [PtyLinuxHandle].
 *  - nativeAwaitExit: mapped exit code (normal -> status, signal ->
 *    128+signal, unreaped/other -> -1); -1 additionally for a stale handle.
 *  - nativeDestroy: 0 released here; -1 cleanup deferred (bounded budget
 *    expired while a reader was in flight; the reader completes it); -2 stale.
 */
object PtyBridge {

    init {
        System.loadLibrary("pocketpty")
    }

    /** Packaging probe: returns the native library version string. */
    external fun nativeVersion(): String

    /**
     * Opens /system/bin/sh on a fresh PTY in [cwd] with [envp] ("K=V" array).
     * Throws IOException (mapped to PtyStartException by the caller) when the
     * session cannot be established; nothing is left allocated on failure.
     */
    external fun nativeOpen(cwd: String, envp: Array<String>): Long

    /** See class KDoc for return-code conventions. Max one buffer per call. */
    external fun nativeRead(handle: Long, buf: ByteArray): Int

    /** Returns delivered byte count; silently drops when handle is stale. */
    external fun nativeWrite(handle: Long, bytes: ByteArray): Int

    /**
     * Propagates a window resize to the pty master (TIOCSWINSZ; the kernel
     * delivers SIGWINCH to the foreground process group). Conventions:
     * 0 ok; -2 stale handle; -3 ioctl failure; -4 arguments outside
     * [1, 2048] (rows/cols).
     */
    external fun nativeResize(handle: Long, rows: Int, cols: Int): Int

    /** SIGHUP to the session leader; repeat-tolerant; -1 for stale handle. */
    external fun nativeRequestExit(handle: Long): Int

    /** Blocks the CALLING THREAD until the child is reaped; returns the exit code. */
    external fun nativeAwaitExit(handle: Long): Int

    /** Exactly-once native cleanup per live handle. See class KDoc codes. */
    external fun nativeDestroy(handle: Long): Int
}
