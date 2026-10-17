package com.pocketide.mobile.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Library-free navigation state for the app shell.
 *
 * Two motions:
 *  - [switchTo]: bottom-bar navigation — resets the stack to the target tab
 *    (tabs are siblings; back never walks between tabs, matching platform
 *    conventions).
 *  - [push]/[pop]: detail screens layered on a tab (e.g. opening a project
 *    from Home into the Editor). System back pops these first.
 *
 * Kept as plain StateFlow so it is unit-testable on the JVM without Compose.
 */
class NavigationState(initial: PocketDestination = PocketDestination.Home) {

    private val mutableStack = MutableStateFlow(listOf(initial))
    val stack: StateFlow<List<PocketDestination>> = mutableStack.asStateFlow()

    val current: PocketDestination
        get() = mutableStack.value.last()

    val canGoBack: Boolean
        get() = mutableStack.value.size > 1

    /** Bottom-bar navigation: clears any detail screens above the tab root. */
    fun switchTo(target: PocketDestination) {
        mutableStack.update { current ->
            current.dropLast(current.size - 1) + target
        }
    }

    /** Opens a detail screen layered on the current tab. */
    fun push(target: PocketDestination) {
        mutableStack.update { it + target }
    }

    /** @return true if a detail screen was popped, false when already at a tab root. */
    fun pop(): Boolean {
        if (!canGoBack) return false
        mutableStack.update { it.dropLast(1) }
        return true
    }
}
