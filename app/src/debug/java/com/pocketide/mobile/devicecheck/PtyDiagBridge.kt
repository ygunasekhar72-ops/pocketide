package com.pocketide.mobile.devicecheck

/**
 * Debug-only JNI surface for native PTY root-cause diagnostics. The native
 * symbol exists only in POCKETPTY_DEBUG_DIAG builds (debug variant), so this
 * object is never referenced by production code and never loaded by the JVM
 * unit test runner.
 *
 * [nativeOpenDiag] mirrors [com.pocketide.mobile.terminal.PtyBridge.nativeOpen]
 * exactly (same shared C implementation) but returns a phase trace instead of
 * throwing on failure, localizing which native phase wedges (openpty, pipe,
 * fork, errpipe probe, registration, reaper creation).
 */
object PtyDiagBridge {

    /**
     * Returns "H=<handle>|<phase-trace>" on success and "H=-1|<trace incl.
     * failure phase>" otherwise. Handle semantics are identical to nativeOpen.
     */
    external fun nativeOpenDiag(cwd: String, envp: Array<String>): String
}
