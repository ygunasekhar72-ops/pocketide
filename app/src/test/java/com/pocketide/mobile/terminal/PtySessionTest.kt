package com.pocketide.mobile.terminal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Session-engine tests. [ScriptedConnection] is a coroutine PLUMBING harness:
 * arbitrary byte payloads and a script-driven exit — NOT a shell simulation.
 * It validates lifecycle transitions, ordering, stdin routing, exactly-once
 * destroy and cancellation cleanup. Real PTY semantics are the P4 native
 * phase's acceptance work; nothing here claims the terminal works.
 */
class PtySessionTest {

    /** Plumbing harness for one scripted connection; payloads are arbitrary. */
    private class ScriptedConnection : PtyConnection {
        override val output = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        val requestExitCalls = mutableListOf<Int>()
        val resizes = mutableListOf<Pair<Int, Int>>()
        var resizeFailure: Exception? = null
        var destroyCalls = 0
        private val exitSignal = CompletableDeferred<Int>()

        override suspend fun write(bytes: ByteArray) {
            writes.add(bytes)
        }

        override suspend fun requestExit() {
            requestExitCalls.add(requestExitCalls.size)
        }

        override suspend fun resize(rows: Int, cols: Int): Boolean {
            resizes.add(rows to cols)
            resizeFailure?.let { throw it }
            return rows in 1..2048 && cols in 1..2048
        }

        override suspend fun awaitExit(): Int = exitSignal.await()

        override suspend fun destroy() {
            destroyCalls += 1
        }

        /** Script action: the child exits with [code]. */
        fun finish(code: Int) {
            exitSignal.complete(code)
            output.close()
        }
    }

    /** Plumbing harness for [PtyHandle.open]; optionally gated/failing. */
    private class ScriptedHandle : PtyHandle {
        val connection = ScriptedConnection()
        val openCalls = mutableListOf<PtySpec>()
        var openGate: CompletableDeferred<Unit>? = null
        var openError: PtyStartException? = null

        override suspend fun open(spec: PtySpec): PtyConnection {
            openCalls.add(spec)
            openGate?.await()
            openError?.let { throw it }
            return connection
        }
    }

    private fun TestScope.newSession(handle: ScriptedHandle): PtySession =
        PtySession(handle, this)

    private val spec = PtySpec(cwd = File("/tmp/workspace"))

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.collectToString(session: PtySession): MutableList<String> {
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.output.collect { received.add(it.decodeToString()) }
        }
        return received
    }

    @Test
    fun start_runsDirectedOutput_writesStdin_andTerminatesOnChildExit() = runTest {
        val handle = ScriptedHandle()
        val session = newSession(handle)
        val received = collectToString(session)
        advanceUntilIdle()

        session.start(spec)
        advanceUntilIdle()
        assertEquals(PtySessionState.Running, session.state.value)

        session.write("dir".encodeToByteArray())
        advanceUntilIdle()
        assertEquals(listOf("dir"), handle.connection.writes.map { it.decodeToString() })

        handle.connection.output.send("chunk-one".encodeToByteArray())
        handle.connection.output.send("chunk-two".encodeToByteArray())
        advanceUntilIdle()
        assertEquals(listOf("chunk-one", "chunk-two"), received)

        handle.connection.finish(0)
        advanceUntilIdle()
        assertEquals(PtySessionState.Terminated(0), session.state.value)
        assertEquals(1, handle.connection.destroyCalls)
    }

    @Test
    fun stopSignalsExit_reapsExactlyOnce() = runTest {
        val handle = ScriptedHandle()
        val session = newSession(handle)
        collectToString(session)
        session.start(spec)
        advanceUntilIdle()

        session.stop()
        advanceUntilIdle()
        assertEquals(PtySessionState.Stopping, session.state.value)
        assertEquals(1, handle.connection.requestExitCalls.size)

        handle.connection.finish(3)
        advanceUntilIdle()
        assertEquals(PtySessionState.Terminated(3), session.state.value)
        assertEquals(1, handle.connection.destroyCalls)

        session.stop()
        advanceUntilIdle()
        assertEquals("stop on terminal state must not re-request exit",
            1, handle.connection.requestExitCalls.size)
    }

    @Test
    fun stopDuringStartup_deliversExitAsSoonAsOpenCompletes() = runTest {
        val handle = ScriptedHandle()
        val gate = CompletableDeferred<Unit>()
        handle.openGate = gate
        val session = newSession(handle)
        collectToString(session)

        session.start(spec)
        advanceUntilIdle()
        assertEquals(PtySessionState.Starting, session.state.value)

        session.stop()
        advanceUntilIdle()
        assertEquals(PtySessionState.Stopping, session.state.value)
        assertEquals("nothing to signal before open completes",
            0, handle.connection.requestExitCalls.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("deferred stop must be delivered right after open",
            1, handle.connection.requestExitCalls.size)

        handle.connection.finish(0)
        advanceUntilIdle()
        assertEquals(PtySessionState.Terminated(0), session.state.value)
        assertEquals(1, handle.connection.destroyCalls)
    }

    @Test
    fun failedOpen_marksFailed_withoutDestroyingAnything() = runTest {
        val handle = ScriptedHandle()
        handle.openError = PtyStartException("pty denied")
        val session = newSession(handle)
        collectToString(session)

        session.start(spec)
        advanceUntilIdle()
        assertEquals(PtySessionState.Failed("pty denied"), session.state.value)
        assertEquals(0, handle.connection.destroyCalls)
        assertTrue("open must still have been attempted", handle.openCalls.isNotEmpty())
    }

    @Test
    fun lateWritesOnTerminalSession_areDroppedNotThrown() = runTest {
        val handle = ScriptedHandle()
        val session = newSession(handle)
        collectToString(session)
        session.start(spec)
        advanceUntilIdle()
        handle.connection.finish(0)
        advanceUntilIdle()

        session.write("after".encodeToByteArray())
        advanceUntilIdle()
        assertTrue(handle.connection.writes.isEmpty())
        assertEquals(1, handle.connection.destroyCalls)
    }

    @Test
    fun doubleStart_isANoOp() = runTest {
        val handle = ScriptedHandle()
        val gate = CompletableDeferred<Unit>()
        handle.openGate = gate
        val session = newSession(handle)
        collectToString(session)

        session.start(spec)
        advanceUntilIdle()
        session.start(spec)
        advanceUntilIdle()
        assertEquals(1, handle.openCalls.size)
        assertEquals(PtySessionState.Starting, session.state.value)

        gate.complete(Unit)
        handle.connection.finish(0)
        advanceUntilIdle()
        assertEquals(PtySessionState.Terminated(0), session.state.value)
    }

    @Test
    fun stopBeforeStart_isANoOp_andDoesNotBlockLaterStart() = runTest {
        val handle = ScriptedHandle()
        val session = newSession(handle)
        collectToString(session)

        session.stop()
        advanceUntilIdle()
        assertEquals(PtySessionState.NotStarted, session.state.value)

        session.start(spec)
        advanceUntilIdle()
        assertEquals(PtySessionState.Running, session.state.value)
        handle.connection.finish(0)
        advanceUntilIdle()
        assertEquals(PtySessionState.Terminated(0), session.state.value)
    }

    @Test
    fun scopeCancellation_tearsDownExactlyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val outer = TestScope(dispatcher)
        val handle = ScriptedHandle()
        val session = PtySession(handle, outer)
        session.start(spec)
        advanceUntilIdle()
        assertEquals(PtySessionState.Running, session.state.value)

        outer.cancel()
        advanceUntilIdle()
        assertEquals("cancellation must not fabricate an exit code",
            PtySessionState.Running, session.state.value)
        assertEquals("exactly-once destroy on scope cancellation",
            1, handle.connection.destroyCalls)
        outer.cancel(null)
    }

    @Test
    fun resize_propagatesToLiveConnection_includingFailingBounds() = runTest {
        val handle = ScriptedHandle()
        val session = newSession(handle)
        collectToString(session)
        session.start(spec)
        advanceUntilIdle()

        assertTrue(session.resize(80, 24))
        assertEquals(listOf(80 to 24), handle.connection.resizes)

        // Out-of-bounds values still REACH the transport: the native side
        // owns authoritative validation, the scripted transport mirrors it.
        assertTrue(!session.resize(0, 24))
        assertEquals(listOf(80 to 24, 0 to 24), handle.connection.resizes)
    }

    @Test
    fun resize_onNotStartedOrTerminatedSession_returnsFalseWithoutPropagation() = runTest {
        val handle = ScriptedHandle()
        val session = newSession(handle)
        collectToString(session)

        assertTrue(!session.resize(80, 24))
        assertEquals(0, handle.connection.resizes.size)

        session.start(spec)
        advanceUntilIdle()
        handle.connection.finish(0)
        advanceUntilIdle()

        assertTrue(!session.resize(80, 24))
        assertEquals(0, handle.connection.resizes.size)
    }

    @Test
    fun resize_transportErrorsAreAbsorbedAsFalse() = runTest {
        val handle = ScriptedHandle()
        val session = newSession(handle)
        collectToString(session)
        session.start(spec)
        advanceUntilIdle()
        handle.connection.resizeFailure = IllegalStateException("winsize ioctl failed")

        assertTrue(!session.resize(80, 24))
        // The attempt still reached the transport exactly once.
        assertEquals(listOf(80 to 24), handle.connection.resizes)

        handle.connection.finish(0)
        advanceUntilIdle()
    }
}
