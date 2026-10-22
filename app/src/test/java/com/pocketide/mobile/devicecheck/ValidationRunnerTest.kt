package com.pocketide.mobile.devicecheck

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic contract of the P3e validation matrix against fake probes:
 * the runner derives PASS/FAIL/BLOCKED statuses purely from probe outcomes
 * and live snapshots — no Android framework involvement here, while the app
 * runs the same code against real engines.
 */
class ValidationRunnerTest {

    private class FakeEditorProbe : EditorProbe {
        var roundTrips = 0
        var inserts = 0
        var cursorRuns = 0

        /** Phase-accurate emulation: tracks the model text like the real engine. */
        private var model: String = ""

        override suspend fun contentRoundTrip(content: String, language: String): String? {
            roundTrips++
            model = content
            return content
        }

        override suspend fun insertAndReport(text: String): String? {
            inserts++
            model += text
            return model
        }

        override suspend fun cursorAwareInsert(move: String, text: String): String? {
            cursorRuns++
            // One real-engine "left" move: insertion happens before the last char.
            model = model.dropLast(1) + text + model.last()
            return model
        }
    }

    private class FakeLiveSnapshot(
        private val doc: String? = "typed text",
        private val dirty: Boolean = true,
        private val saveMsg: String? = "saved",
        private val imeSeen: Boolean = true,
        private val network: String = "offline"
    ) : LiveSnapshot {
        override fun visibleEditorDocument(): String? = doc
        override fun visibleEditorDirty(): Boolean = dirty
        override fun visibleEditorSaveMessage(): String? = saveMsg
        override fun consumeImeSeen(): Boolean = imeSeen
        override fun networkSample(): String = network
    }

    private class FakePtyProbe(
        var startError: String? = null,
        var live: Boolean = true,
        var echoOk: Boolean = true,
        var sizes: MutableList<String> = mutableListOf("24 96", "30 120"),
        var exitCode: Int? = 3,
        var cancelOk: Boolean = true,
        var cyclesOk: Boolean = true,
        var badCwd: String? = "refused: pty session start failed"
    ) : PtyProbe {
        var cancels = 0
        var cycles = 0

        override suspend fun start(): String? = startError

        override suspend fun isLive(): Boolean = live

        override suspend fun sendAndAwait(
            command: String,
            awaitContains: String,
            timeoutMs: Long
        ): Boolean = echoOk

        override suspend fun resizeAndConfirm(rows: Int, cols: Int): String? =
            sizes.removeFirstOrNull()

        override suspend fun exitCodeEcho(): Int? = exitCode

        override suspend fun cancel(): Boolean {
            cancels++
            return cancelOk
        }

        override suspend fun openDestroyCycle(): Boolean {
            cycles++
            return cyclesOk
        }

        override suspend fun badCwdStart(): String? = badCwd

        override fun notePauseResume(visibleGone: Boolean) = Unit
    }

    private class FakeStoreProbe : StoreProbe {
        override suspend fun storeRoundTrip(content: String): String? = "loaded: $content"
    }

    private class FakeStoreProbeExact : StoreProbe {
        override suspend fun storeRoundTrip(content: String): String? = content
    }

    @Test
    fun `all-green probes yield PASS groups`() = runTest {
        val editor = FakeEditorProbe()
        val pty = FakePtyProbe()
        val groups = runValidationMatrix(
            editor, pty, FakeLiveSnapshot(), emptyMap(),
            manualEvidence = true, storeProbe = FakeStoreProbeExact()
        )

        val envGroup = groups.first { it.id == "ENV" }
        assertEquals(5, envGroup.results.size)

        val airplane = groups.first { it.id == "A" }
        assertEquals(DeviceCheckResult.STATUS_PASS, airplane.results[0].status)
        assertEquals(DeviceCheckResult.STATUS_PASS, airplane.results[1].status)

        val keyboard = groups.first { it.id == "B" }
        assertEquals(DeviceCheckResult.STATUS_PASS, keyboard.results[0].status)

        val cursor = groups.first { it.id == "C" }
        assertEquals(DeviceCheckResult.STATUS_PASS, cursor.results[0].status)

        val shortcuts = groups.first { it.id == "D" }
        assertEquals(2, shortcuts.results.size)
        assertEquals(DeviceCheckResult.STATUS_PASS, shortcuts.results[0].status)
        assertEquals(DeviceCheckResult.STATUS_PASS, shortcuts.results[1].status)

        val persistence = groups.first { it.id == "E" }
        assertEquals(DeviceCheckResult.STATUS_PASS, persistence.results[0].status)

        val bridge = groups.first { it.id == "F" }
        assertEquals(DeviceCheckResult.STATUS_PASS, bridge.results[0].status)

        val ptyGroup = groups.first { it.id == "PTY" }
        assertEquals(6, ptyGroup.results.size)
        assertTrue(ptyGroup.results.all { it.status == DeviceCheckResult.STATUS_PASS })
        assertEquals("all statuses PASS", ptyGroup.results.count { it.status == DeviceCheckResult.STATUS_PASS }, ptyGroup.results.size)
        assertEquals(6, editor.roundTrips)
    }

    @Test
    fun `missing probes produce BLOCKED editor results and PTY marker`() = runTest {
        val groups = runValidationMatrix(null, null, FakeLiveSnapshot(), emptyMap())
        val airplane = groups.first { it.id == "A" }
        assertEquals(
            DeviceCheckResult.STATUS_BLOCKED,
            airplane.results.first { it.id == "A.monaco-roundtrip" }.status
        )
        val ptyGroup = groups.first { it.id == "PTY" }
        assertEquals(
            DeviceCheckResult.STATUS_BLOCKED,
            ptyGroup.results.single().status
        )
        val cursor = groups.first { it.id == "C" }
        assertEquals(DeviceCheckResult.STATUS_BLOCKED, cursor.results[0].status)
    }

    @Test
    fun `probe timeouts surface as FAIL with evidence`() = runTest {
        // A probe behind a blocked engine simulates timeouts by echoing null
        // like the real "callback never arrived" path.
        val editor = object : EditorProbe {
            override suspend fun contentRoundTrip(content: String, language: String): String? = null
            override suspend fun insertAndReport(text: String): String? = null
            override suspend fun cursorAwareInsert(move: String, text: String): String? = null
        }
        val groups = runValidationMatrix(editor, FakePtyProbe(), FakeLiveSnapshot(), emptyMap())
        val airplane = groups.first { it.id == "A" }
        assertEquals(DeviceCheckResult.STATUS_FAIL, airplane.results[0].status)
    }

    @Test
    fun `wrong cursor semantics fail with evidence text`() = runTest {
        val editor = object : EditorProbe {
            override suspend fun contentRoundTrip(content: String, language: String): String? = content
            override suspend fun insertAndReport(text: String): String? = text
            override suspend fun cursorAwareInsert(move: String, text: String): String? = "wrong-placement"
        }
        val groups = runValidationMatrix(editor, FakePtyProbe(), FakeLiveSnapshot(), emptyMap())
        val cursor = groups.first { it.id == "C" }
        assertEquals(DeviceCheckResult.STATUS_FAIL, cursor.results[0].status)
    }

    @Test
    fun `pty exit code mismatch fails`() = runTest {
        val groups = runValidationMatrix(
            FakeEditorProbe(),
            FakePtyProbe(exitCode = 0),
            FakeLiveSnapshot(),
            emptyMap()
        )
        val ptyGroup = groups.first { it.id == "PTY" }
        val exitCheck = ptyGroup.results.first { it.id == "PTY.exit-status" }
        assertEquals(DeviceCheckResult.STATUS_FAIL, exitCheck.status)
    }

    @Test
    fun `pty resize mismatch fails`() = runTest {
        val groups = runValidationMatrix(
            FakeEditorProbe(),
            FakePtyProbe(sizes = mutableListOf("24 80")),
            FakeLiveSnapshot(),
            emptyMap()
        )
        val ptyGroup = groups.first { it.id == "PTY" }
        val resizeCheck = ptyGroup.results.first { it.id == "PTY.resize" }
        assertEquals(DeviceCheckResult.STATUS_FAIL, resizeCheck.status)
    }

    @Test
    fun `keyboard checks fail honestly without user evidence`() = runTest {
        val snapshot = FakeLiveSnapshot(imeSeen = false, dirty = false, saveMsg = null)
        val groups = runValidationMatrix(FakeEditorProbe(), FakePtyProbe(), snapshot, emptyMap())
        val keyboard = groups.first { it.id == "B" }
        assertEquals(DeviceCheckResult.STATUS_FAIL, keyboard.results[0].status)
    }

    @Test
    fun `headless auto mode records B group as SKIP with a reason`() = runTest {
        val groups = runValidationMatrix(
            FakeEditorProbe(),
            FakePtyProbe(),
            FakeLiveSnapshot(),
            emptyMap(),
            manualEvidence = false,
            storeProbe = FakeStoreProbeExact()
        )
        val keyboard = groups.first { it.id == "B" }
        assertEquals(2, keyboard.results.size)
        keyboard.results.forEach { result ->
            assertEquals(DeviceCheckResult.STATUS_SKIP, result.status)
            assertTrue(result.evidence.contains("headless"))
        }
    }

    @Test
    fun `missing store probe is recorded as SKIP`() = runTest {
        val groups = runValidationMatrix(FakeEditorProbe(), FakePtyProbe(), FakeLiveSnapshot(), emptyMap())
        val storeCheck = groups.first { it.id == "E" }.results.first { it.id == "E.store-file-roundtrip" }
        assertEquals(DeviceCheckResult.STATUS_SKIP, storeCheck.status)
    }

    @Test
    fun `failing store probe surfaces as FAIL with evidence`() = runTest {
        val failing = object : StoreProbe {
            override suspend fun storeRoundTrip(content: String): String? = null
        }
        val groups = runValidationMatrix(
            FakeEditorProbe(), FakePtyProbe(), FakeLiveSnapshot(), emptyMap(),
            manualEvidence = true, storeProbe = failing
        )
        val storeCheck = groups.first { it.id == "E" }.results.first { it.id == "E.store-file-roundtrip" }
        assertEquals(DeviceCheckResult.STATUS_FAIL, storeCheck.status)
    }

    @Test
    fun `bad cwd failure is recorded as PASS-for-refusal with the message`() = runTest {
        val groups = runValidationMatrix(
            FakeEditorProbe(),
            FakePtyProbe(badCwd = "refused: chdir failed"),
            FakeLiveSnapshot(),
            emptyMap()
        )
        val badCwd = groups.first { it.id == "PTY" }.results.first { it.id == "PTY.bad-cwd" }
        assertEquals(DeviceCheckResult.STATUS_PASS, badCwd.status)
        assertTrue(badCwd.evidence.contains("chdir"))
    }
}
