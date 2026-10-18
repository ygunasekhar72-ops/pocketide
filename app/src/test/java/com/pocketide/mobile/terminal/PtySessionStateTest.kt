package com.pocketide.mobile.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.pocketide.mobile.terminal.PtySessionEvent.StartFailed
import com.pocketide.mobile.terminal.PtySessionEvent.StartRequested
import com.pocketide.mobile.terminal.PtySessionEvent.StartSucceeded
import com.pocketide.mobile.terminal.PtySessionEvent.ProcessExited
import com.pocketide.mobile.terminal.PtySessionEvent.StopRequested
import com.pocketide.mobile.terminal.PtySessionState.Failed
import com.pocketide.mobile.terminal.PtySessionState.NotStarted
import com.pocketide.mobile.terminal.PtySessionState.Running
import com.pocketide.mobile.terminal.PtySessionState.Starting
import com.pocketide.mobile.terminal.PtySessionState.Stopping
import com.pocketide.mobile.terminal.PtySessionState.Terminated

class PtySessionStateTest {

    @Test
    fun happyPath_transitionsForward() {
        var s: PtySessionState = NotStarted
        s = s.onEvent(StartRequested); assertEquals(Starting, s)
        s = s.onEvent(StartSucceeded); assertEquals(Running, s)
        s = s.onEvent(ProcessExited(0)); assertEquals(Terminated(0), s)
        assertTrue(s.isTerminal)
    }

    @Test
    fun stopPath_goesThroughStopping() {
        var s: PtySessionState = Running
        s = s.onEvent(StopRequested); assertEquals(Stopping, s)
        s = s.onEvent(ProcessExited(5)); assertEquals(Terminated(5), s)
    }

    @Test
    fun pendingStopDuringStartup_isHonoredAfterEngineStart() {
        var s: PtySessionState = Starting
        s = s.onEvent(StopRequested); assertEquals(Stopping, s)
        s = s.onEvent(StartSucceeded); assertEquals("pending stop must not regress to Running", Stopping, s)
        s = s.onEvent(ProcessExited(0)); assertEquals(Terminated(0), s)
    }

    @Test
    fun processCanExitDuringStartup() {
        assertEquals(Terminated(3), Starting.onEvent(ProcessExited(3)))
    }

    @Test
    fun startFailure_fromAnyLiveState_fails() {
        assertEquals(Failed("denied"), NotStarted.onEvent(StartFailed("denied")))
        assertEquals(Failed("boom"), Running.onEvent(StartFailed("boom")))
        assertEquals(Failed("boom"), Stopping.onEvent(StartFailed("boom")))
        val f = Failed("first"); assertEquals(f, f.onEvent(StartFailed("second")))
    }

    @Test
    fun repeatedStart_isAbsorbed() {
        assertEquals(Starting, NotStarted.onEvent(StartRequested))
        assertEquals(Starting, Starting.onEvent(StartRequested))
        assertEquals(Running, Running.onEvent(StartRequested))
        assertEquals(Terminated(1), Terminated(1).onEvent(StartRequested))
    }

    @Test
    fun stopOutsideLiveStates_isAbsorbed() {
        assertEquals(NotStarted, NotStarted.onEvent(StopRequested))
        assertEquals(Failed("x"), Failed("x").onEvent(StopRequested))
        assertEquals(Terminated(0), Terminated(0).onEvent(StopRequested))
    }

    @Test
    fun terminalStates_absorbEverything() {
        val events = listOf(
            StartRequested, StartSucceeded, StartFailed("late"), StopRequested, ProcessExited(9)
        )
        for (e in events) {
            assertEquals(Terminated(0), Terminated(0).onEvent(e))
            assertEquals(Failed("x"), Failed("x").onEvent(e))
        }
    }

    @Test
    fun prematureStartSuccess_outsideStartup_isAbsorbed() {
        assertEquals(NotStarted, NotStarted.onEvent(StartSucceeded))
        assertEquals("late success after terminal state must be absorbed",
            Terminated(2), Terminated(2).onEvent(StartSucceeded))
    }

    @Test
    fun exitCodeIsPreservedAcrossTerminalTransition() {
        val s = Stopping.onEvent(ProcessExited(127))
        assertEquals(127, (s as Terminated).exitCode)
        assertTrue(Failed("x").isTerminal)
    }
}
