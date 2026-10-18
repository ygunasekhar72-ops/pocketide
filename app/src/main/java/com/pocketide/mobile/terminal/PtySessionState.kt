package com.pocketide.mobile.terminal

/**
 * Terminal session lifecycle, modeled as a pure reducer so every transition
 * and every illegal-event absorption is unit-testable without coroutines,
 * Robolectric or a PTY. The session engine ([PtySession]) is the only
 * production writer of this state; it never invents states outside this file.
 *
 * Allowed progression (terminal states absorb all events):
 *
 *   NotStarted -> Starting -> Running -> Stopping -> Terminated(code)
 *        \------ Starting/Running/Stopping -> Failed(reason) ------/
 *
 * A process may die while `open` is still completing, so
 * Starting + ProcessExited -> Terminated(code) is legal too.
 */
sealed interface PtySessionState {

    val isTerminal: Boolean
        get() = this is Terminated || this is Failed

    data object NotStarted : PtySessionState
    data object Starting : PtySessionState
    data object Running : PtySessionState
    data object Stopping : PtySessionState
    data class Terminated(val exitCode: Int) : PtySessionState
    data class Failed(val reason: String) : PtySessionState

    /** Returns the next state after [event]; same state if the event is illegal here. */
    fun onEvent(event: PtySessionEvent): PtySessionState = when (event) {
        PtySessionEvent.StartRequested -> when (this) {
            NotStarted -> Starting
            else -> this
        }
        PtySessionEvent.StartSucceeded -> when (this) {
            // A pending stop is honored after the engine is up.
            Starting -> Running
            else -> this
        }
        is PtySessionEvent.StartFailed -> when {
            isTerminal -> this
            else -> Failed(event.reason)
        }
        PtySessionEvent.StopRequested -> when (this) {
            Starting, Running -> Stopping
            else -> this
        }
        is PtySessionEvent.ProcessExited -> when (this) {
            Starting, Running, Stopping -> Terminated(event.exitCode)
            else -> this
        }
    }
}

sealed interface PtySessionEvent {
    data object StartRequested : PtySessionEvent
    data object StartSucceeded : PtySessionEvent
    data class StartFailed(val reason: String) : PtySessionEvent
    data object StopRequested : PtySessionEvent
    data class ProcessExited(val exitCode: Int) : PtySessionEvent
}
