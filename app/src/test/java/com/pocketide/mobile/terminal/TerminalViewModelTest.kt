package com.pocketide.mobile.terminal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Screen-VM controller tests over the REAL [PtySession] engine with a
 * scripted connection harness (arbitrary byte payloads + script-driven
 * exit — plumbing, not a shell simulation). Validates stdin appending,
 * UTF-8 chunk-boundary text assembly, bounded renderer buffer, resize
 * propagation routing, and honest lifecycle labels.
 */
class TerminalViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Scripted connection: records all stdin; exits on demand. */
    private class ScriptedConnection : PtyConnection {
        override val output = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        val resizes = mutableListOf<Pair<Int, Int>>()
        private val exitSignal = CompletableDeferred<Int>()

        override suspend fun write(bytes: ByteArray) {
            writes.add(bytes)
        }

        override suspend fun requestExit() {
            exitSignal.complete(0)
        }

        override suspend fun resize(rows: Int, cols: Int): Boolean {
            resizes.add(rows to cols)
            return rows in 1..2048 && cols in 1..2048
        }

        override suspend fun awaitExit(): Int = exitSignal.await()

        override suspend fun destroy() = Unit

        fun finish(code: Int) {
            exitSignal.complete(code)
            output.close()
        }

        fun emit(text: String) {
            output.trySend(text.encodeToByteArray())
        }
    }

    /** A [PtyHandle] with an always-open connection; plain plumbing. */
    private class ScriptedHandle : PtyHandle {
        val connection = ScriptedConnection()
        override suspend fun open(spec: PtySpec): PtyConnection = connection
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun start_reflectsLifecycle_streamsOutput_andSendsStdin() = runTest {
        val handle = ScriptedHandle()
        val viewModel = TerminalViewModel(tmp.root, { handle }, backgroundScope)
        viewModel.start(null)
        advanceUntilIdle()
        assertEquals("running", viewModel.uiState.value.statusLabel)
        assertEquals(true, viewModel.uiState.value.live)
        assertEquals(tmp.root.absolutePath, viewModel.uiState.value.cwdLabel)

        handle.connection.emit("hel")
        handle.connection.emit("lo\n")
        advanceUntilIdle()
        assertEquals("hello\n", viewModel.uiState.value.text)

        viewModel.sendCommand("ls -l")
        advanceUntilIdle()
        assertEquals(listOf("ls -l\n"), handle.connection.writes.map { it.decodeToString() })

        handle.connection.finish(0)
        advanceUntilIdle()
        assertEquals("exited (code 0)", viewModel.uiState.value.statusLabel)
        assertEquals(false, viewModel.uiState.value.live)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun start_inProjectDirectory_resolvesExistingProject() = runTest {
        val projectDir = tmp.newFolder("demo")
        val handle = ScriptedHandle()
        val viewModel = TerminalViewModel(tmp.root, { handle }, backgroundScope)
        viewModel.start("demo")
        advanceUntilIdle()
        assertEquals(projectDir.absolutePath, viewModel.uiState.value.cwdLabel)
        handle.connection.finish(0)
        advanceUntilIdle()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendCommand_beforeStart_isDropped() = runTest {
        val handle = ScriptedHandle()
        val viewModel = TerminalViewModel(tmp.root, { handle }, backgroundScope)
        viewModel.sendCommand("ls")
        advanceUntilIdle()
        assertEquals(0, handle.connection.writes.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun resize_reachesOnlyLiveSession() = runTest {
        val handle = ScriptedHandle()
        val viewModel = TerminalViewModel(tmp.root, { handle }, backgroundScope)
        viewModel.resize(40, 100)
        advanceUntilIdle()
        assertEquals("no session: nothing to resize", 0, handle.connection.resizes.size)

        viewModel.start(null)
        advanceUntilIdle()
        viewModel.resize(40, 100)
        advanceUntilIdle()
        assertEquals(listOf(40 to 100), handle.connection.resizes)

        handle.connection.finish(0)
        advanceUntilIdle()
        viewModel.resize(24, 80)
        advanceUntilIdle()
        assertEquals("terminal session: no resize", 1, handle.connection.resizes.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rendererBuffer_headTrimsBeyondTheBound() = runTest {
        val handle = ScriptedHandle()
        val viewModel = TerminalViewModel(tmp.root, { handle }, backgroundScope)
        viewModel.start(null)
        advanceUntilIdle()

        val big = "b".repeat(TerminalViewModel.MAX_BUFFER_CHARS + 64)
        handle.connection.emit(big)
        handle.connection.emit("|tail")
        advanceUntilIdle()

        val text = viewModel.uiState.value.text
        assertEquals(TerminalViewModel.MAX_BUFFER_CHARS, text.length)
        assertTrue("head must be trimmed", !text.startsWith(big))
        assertTrue(text.endsWith("|tail"))

        handle.connection.finish(0)
        advanceUntilIdle()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun clear_emptiesOnlyTheRenderBuffer() = runTest {
        val handle = ScriptedHandle()
        val viewModel = TerminalViewModel(tmp.root, { handle }, backgroundScope)
        viewModel.start(null)
        advanceUntilIdle()
        handle.connection.emit("before")
        advanceUntilIdle()
        assertEquals("before", viewModel.uiState.value.text)

        viewModel.clear()
        assertEquals("", viewModel.uiState.value.text)
        assertEquals(true, viewModel.uiState.value.live)
        viewModel.sendCommand("echo hi")
        advanceUntilIdle()
        assertEquals(listOf("echo hi\n"), handle.connection.writes.map { it.decodeToString() })

        handle.connection.finish(0)
        advanceUntilIdle()
    }
}
