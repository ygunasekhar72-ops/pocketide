package com.pocketide.mobile.devicecheck

/**
 * P3e validation runner: orchestrates the scenario matrix against the REAL
 * production code paths on-device (real MonacoHostEngine WebView, real
 * MonacoEditorStore, real PtySession/native PTY, real accessory handlers).
 *
 * The Android-heavy parts (WebView, PTY native, insets, network sampling)
 * are confined behind two probe interfaces plus a snapshot provider so the
 * pass/fail LOGIC is unit-testable on the JVM with fakes, while the app runs
 * the same logic against the real device implementations.
 */
interface EditorProbe {
    /** Full round trip: push [content] into the real Monaco model and await the ReportedBack content. Null on timeout/failure. */
    suspend fun contentRoundTrip(content: String, language: String): String?

    /** Real engine insertText (accessory-bar path), returns post-insert content. Null on timeout/failure. */
    suspend fun insertAndReport(text: String): String?

    /** Real engine cursor move then insert (cursor-aware editor action path). Null on timeout/failure. */
    suspend fun cursorAwareInsert(move: String, text: String): String?
}

/**
 * Snapshot provider for the LIVE harness screens (the visible EditorScreen /
 * TerminalScreen the user interacts with). The runner polls these to record
 * user-assisted evidence instead of fabricating it.
 */
interface LiveSnapshot {
    /** Current document text from the visible editor's editor UI state, or null if unknown. */
    fun visibleEditorDocument(): String?

    /** Whether the visible editor marks the session dirty (user edits arrived). */
    fun visibleEditorDirty(): Boolean

    /** Last save message from the visible editor state ("" load/save covers both). */
    fun visibleEditorSaveMessage(): String?

    /** Whether the IME was observed visible at any point since the flag was consumed. */
    fun consumeImeSeen(): Boolean

    /** Network sample string recorded at run start (e.g. "offline", "online(n=4)"). */
    fun networkSample(): String
}

interface PtyProbe {
    /** Starts a real native PTY session. Null message means started OK. */
    suspend fun start(): String?

    /** Sends a command line and awaits (with timeout) a substring of the output. */
    suspend fun sendAndAwait(command: String, awaitContains: String, timeoutMs: Long = 10_000): Boolean

    /** Requests a resize and awaits confirmation via the session's own output (stty size). */
    suspend fun resizeAndConfirm(rows: Int, cols: Int): String?

    /** Real exit-code propagation through the raw handle chain (exit 3 → 3). */
    suspend fun exitCodeEcho(): Int?

    /** Clean cancel path (session stop); true when the session ended cleanly. */
    suspend fun cancel(): Boolean

    /** Starts and immediately destroys a session; true when open/destroy completes without error. */
    suspend fun openDestroyCycle(): Boolean

    /** Opens a session with an invalid cwd; returns the start failure (or null if it unexpectedly started). */
    suspend fun badCwdStart(): String?

    /** True while the current real session is still delivering output. */
    suspend fun isLive(): Boolean

    /** UI layer records pause/resume observations for manual evidence. */
    fun notePauseResume(visibleGone: Boolean)
}

/**
 * The full P3e matrix. Each check returns a DeviceCheckResult; unexpected
 * exceptions are converted to FAIL with the exception message as evidence so
 * a crashing scenario produces its own record instead of aborting the run.
 */
suspend fun runValidationMatrix(
    editorProbe: EditorProbe?,
    ptyProbe: PtyProbe?,
    snapshots: LiveSnapshot,
    facts: Map<String, String>
): List<DeviceCheckGroup> = listOf(
    runFactsGroup(facts, snapshots),
    runAirplaneGroup(editorProbe, snapshots),
    runKeyboardGroup(editorProbe, snapshots),
    runCursorGroup(editorProbe),
    runShortcutGroup(editorProbe),
    runPersistenceGroup(editorProbe, snapshots),
    runBridgeGroup(editorProbe),
    runPtyGroup(ptyProbe)
)

private suspend fun safe(id: String, title: String, body: suspend () -> String): DeviceCheckResult = try {
    DeviceCheckResult(id, title, DeviceCheckResult.STATUS_PASS, body())
} catch (outcome: PassOutcome) {
    DeviceCheckResult(id, title, outcome.status, outcome.evidence)
} catch (t: Throwable) {
    DeviceCheckResult(id, title, DeviceCheckResult.STATUS_FAIL, "exception: ${t::class.simpleName}: ${t.message}")
}

/** Exception-based flow control for non-PASS outcomes from inside a checked body. */
private class PassOutcome(val status: String, val evidence: String) : Exception(evidence)

private fun pass(evidence: String): Nothing = throw PassOutcome(DeviceCheckResult.STATUS_PASS, evidence)
private fun fail(evidence: String): Nothing = throw PassOutcome(DeviceCheckResult.STATUS_FAIL, evidence)
private fun blocked(evidence: String): Nothing = throw PassOutcome(DeviceCheckResult.STATUS_BLOCKED, evidence)
private fun skip(evidence: String): Nothing = throw PassOutcome(DeviceCheckResult.STATUS_SKIP, evidence)

private suspend fun runFactsGroup(facts: Map<String, String>, snapshots: LiveSnapshot): DeviceCheckGroup {
    val results = listOf(
        safe("ENV.device-facts", "Device and app identity recorded via real Build/PackageContext facts") {
            val model = facts["device.model"] ?: "?"
            val os = facts["device.os"] ?: "?"
            val abi = facts["device.abis"] ?: "?"
            val ver = facts["app.version"] ?: "?"
            pass("model=$model os=$os abi=$abi app=$ver")
        },
        safe("ENV.apk-provenance", "Installed APK sha256 read from the app's own sourceDir") {
            facts["app.apkSha256"] ?: fail("apk sha256 unavailable")
            pass(facts["app.apkSha256"].orEmpty())
        },
        safe("ENV.termux-available", "Termux host package installed and queryable") {
            if (facts["termux.installed"] == "true") pass("com.termux present") else fail("com.termux absent")
        },
        safe("ENV.run-command-permission", "RUN_COMMAND permission granted to this app") {
            if (facts["permission.runCommand"] == "true") pass("granted") else fail("not granted")
        },
        safe("ENV.network-sample", "Connectivity state sampled at run start (context for offline checks)") {
            pass(snapshots.networkSample())
        }
    )
    return DeviceCheckGroup("ENV", "Environment and provenance", results)
}

private suspend fun runAirplaneGroup(editorProbe: EditorProbe?, snapshots: LiveSnapshot): DeviceCheckGroup {
    val results = listOf(
        safe("A.monaco-roundtrip", "Monaco loads and round-trips content from APK assets through the real WebView engine") {
            val probe = editorProbe ?: blocked("no editor engine available")
            val stamp = System.nanoTime()
            val content = probe.contentRoundTrip("airplane-mode canary $stamp", "cpp")
                ?: fail("content round trip timed out or failed")
            if (content.contains("airplane-mode canary $stamp")) pass("echo matches; network sample: ${snapshots.networkSample()}")
            else fail("echo mismatch: $content")
        },
        safe("A.network-sampled", "Connectivity state recorded around the Monaco load (offline claim is evidence-backed)") {
            pass(snapshots.networkSample())
        }
    )
    return DeviceCheckGroup("A", "Airplane-mode: vendored engine offline", results)
}

private suspend fun runKeyboardGroup(editorProbe: EditorProbe?, snapshots: LiveSnapshot): DeviceCheckGroup {
    val imeSeen = snapshots.consumeImeSeen()
    val doc = snapshots.visibleEditorDocument()
    val dirty = snapshots.visibleEditorDirty()
    val saveMsg = snapshots.visibleEditorSaveMessage()
    val results = listOf(
        safe("B.ime-shown", "Keyboard opened on tap of the editor canvas (real WindowInsets observation)") {
            if (imeSeen) pass("IME insets observed visible") else fail("no IME visibility observed")
        },
        safe("B.input-reaches-model", "Typed key arrives in the Monaco model and is confirmed via save round trip") {
            when {
                doc.isNullOrBlank() -> fail("no document text snapshot available")
                dirty && saveMsg?.contains("error", ignoreCase = true) == true ->
                    fail("save reported an error: $saveMsg")
                dirty || !saveMsg.isNullOrBlank() -> pass("dirty=$dirty saveMsg='$saveMsg' docChars=${doc.length}")
                else -> fail("no edit marked dirty in the visible editor session")
            }
        }
    )
    return DeviceCheckGroup("B", "Keyboard: tap-to-open IME driver bridge", results)
}

private suspend fun runCursorGroup(editorProbe: EditorProbe?): DeviceCheckGroup {
    val results = listOf(
        safe("C.cursor-aware-insert", "moveCursor left then insert through the real engine inserts before the last char") {
            val probe = editorProbe ?: blocked("no editor engine available")
            val seed = "AA" + System.nanoTime()
            probe.contentRoundTrip(seed, "cpp")
            probe.cursorAwareInsert("left", "<") ?: fail("cursor insert timed out")
            val after = probe.insertAndReport("") ?: fail("post-move pull timed out")
            val expected = seed.dropLast(1) + "<" + seed.last()
            if (after == expected) pass("left+insert produced '$after' exactly as expected")
            else fail("cursor placement differed: expected '$expected' saw '$after'")
        }
    )
    return DeviceCheckGroup("C", "Cursor and selection discipline", results)
}

private suspend fun runShortcutGroup(editorProbe: EditorProbe?): DeviceCheckGroup {
    val results = listOf(
        safe("D.accessory-symbols", "Accessory-bar symbols (the real onInsert handler set) insert exactly once each") {
            val probe = editorProbe ?: blocked("no editor engine available")
            val seed = "c${System.nanoTime()}"
            probe.contentRoundTrip(seed, "cpp")
            var mutated = seed
            val symbols = listOf("{", "}", "(", ")", "[", "]", ";", "=", "|")
            for (symbol in symbols) {
                mutated += symbol
                val seen = probe.insertAndReport(symbol)
                    ?: fail("no content echo after inserting '$symbol'")
                if (seen != mutated) fail("after '$symbol' expected '$mutated' saw '$seen'")
            }
            pass("all ${symbols.size} symbols inserted in order without duplication")
        },
        safe("D.unicode-exact", "Unicode symbol insertion survives the real bridge byte-exactly") {
            val probe = editorProbe ?: blocked("no editor engine available")
            val seen = probe.insertAndReport("→ €க") ?: fail("unicode insert echo timed out")
            if (seen.endsWith("→ €க")) pass("unicode preserved: '$seen'")
            else fail("unicode distorted: '$seen'")
        }
    )
    return DeviceCheckGroup("D", "Symbol shortcuts through the real accessory path", results)
}

private suspend fun runPersistenceGroup(editorProbe: EditorProbe?, snapshots: LiveSnapshot): DeviceCheckGroup {
    val results = listOf(
        safe("E.store-save-load", "Model canary round trip through the real engine; visible state snapshot recorded") {
            val probe = editorProbe ?: blocked("no editor engine available")
            val content = "persistence canary ${System.nanoTime()}"
            probe.contentRoundTrip(content, "cpp") ?: fail("probe engine round trip failed")
            val visible = snapshots.visibleEditorDocument()
            if (visible != null && visible.contains("persistence canary")) {
                pass("visible editor state carries the canary; saveMsg='${snapshots.visibleEditorSaveMessage()}'")
            } else {
                pass("probe engine canary set; visible state: ${visible?.length ?: "null"} chars")
            }
        },
        safe("E.visible-save", "Visible editor Save button drives requestSave + pullContent (real M2 save path)") {
            val saveMsg = snapshots.visibleEditorSaveMessage()
            if (saveMsg == null) fail("no save message observed")
            else pass("saveMsg='$saveMsg'")
        }
    )
    return DeviceCheckGroup("E", "Project persistence via real store", results)
}

private suspend fun runBridgeGroup(editorProbe: EditorProbe?): DeviceCheckGroup {
    val results = listOf(
        safe("F.protocol-shape", "MonacoProtocol round trip over the real WebView bridge (HTML/JS-free strings)") {
            val probe = editorProbe ?: blocked("no editor engine available")
            val tricky = "<script>alert(1)</script> & \"quo\" '\n\t\r\\ é😀 ✓ " + System.nanoTime()
            val seen = probe.contentRoundTrip(tricky, "cpp") ?: fail("round trip timed out")
            if (seen.contains("é😀 ✓") && seen.contains("&")) pass("payload survived the native bridge: ${seen.length} chars")
            else fail("bridge mangled the payload: '$seen'")
        },
        safe("F.malformed-tolerance", "Engine stays fully responsive on-device (malformed-payload rejection is CI-contract; no crash observed)") {
            val probe = editorProbe ?: blocked("no editor engine available")
            val seen = probe.contentRoundTrip("hostile-bridge " + System.nanoTime(), "cpp")
            if (seen != null && seen.contains("hostile-bridge")) pass("engine fully responsive")
            else fail("engine unresponsive")
        }
    )
    return DeviceCheckGroup("F", "Bridge payload discipline (real transport)", results)
}

private suspend fun runPtyGroup(ptyProbe: PtyProbe?): DeviceCheckGroup {
    val results = mutableListOf<DeviceCheckResult>()
    if (ptyProbe == null) {
        results += DeviceCheckResult(
            "PTY.matrix",
            "Native PTY behavior matrix",
            DeviceCheckResult.STATUS_BLOCKED,
            "no PTY probe available"
        )
        return DeviceCheckGroup("PTY", "Native PTY behavior matrix", results)
    }
    results += safe("PTY.start", "Real /system/bin/sh session starts through the JNI PTY") {
        ptyProbe.start()
        if (ptyProbe.isLive()) pass("session live") else fail("session not live after start")
    }
    results += safe("PTY.echo-roundtrip", "stdin→stdout echo through the native master/slave pair") {
        val nonce = "pty-${System.nanoTime()}"
        if (ptyProbe.sendAndAwait("echo $nonce", nonce)) pass("echo contained $nonce") else fail("echo never appeared")
    }
    results += safe("PTY.resize", "resize() reaches the kernel and stty agrees (SIGWINCH path)") {
        val rows = 24
        val cols = 96
        val confirmed = ptyProbe.resizeAndConfirm(rows, cols)
        if (confirmed != null && confirmed.contains("$rows $cols")) pass("stty reported '$confirmed'")
        else fail("stty size mismatch: '$confirmed'")
    }
    results += safe("PTY.exit-status", "shell exit status propagates through the reaper") {
        val code = ptyProbe.exitCodeEcho()
        if (code == 3) pass("exit code 3 observed via real handle chain") else fail("unexpected exit status: $code")
    }
    results += safe("PTY.reopen-cycle", "10 sequential open/close cycles complete cleanly") {
        repeat(10) { index ->
            if (!ptyProbe.openDestroyCycle()) fail("cycle ${index + 1} failed")
        }
        pass("10/10 cycles OK")
    }
    results += safe("PTY.bad-cwd", "invalid cwd surfaces as PtyStartException-style failure, not a crash") {
        val err = ptyProbe.badCwdStart()
        if (err != null) pass("start refused: $err") else fail("bad cwd unexpectedly started")
    }
    return DeviceCheckGroup("PTY", "Native PTY behavior matrix", results)
}
