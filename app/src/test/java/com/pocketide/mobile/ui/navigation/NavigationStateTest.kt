package com.pocketide.mobile.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the shell navigation state machine. */
class NavigationStateTest {

    @Test
    fun startsOnHomeTabRoot() {
        val state = NavigationState()
        assertEquals(PocketDestination.Home, state.current)
        assertFalse(state.canGoBack)
    }

    @Test
    fun switchTo_resetsStackToTargetTabRoot() {
        val state = NavigationState()
        state.switchTo(PocketDestination.Terminal)
        state.push(PocketDestination.Editor)
        assertEquals(listOf(PocketDestination.Terminal, PocketDestination.Editor), state.stack.value)

        state.switchTo(PocketDestination.Settings)
        assertEquals(PocketDestination.Settings, state.current)
        assertFalse("detail screens must not survive a tab switch", state.canGoBack)
    }

    @Test
    fun push_thenPop_returnsToPreviousTabRoot() {
        val state = NavigationState()
        state.switchTo(PocketDestination.Home)
        state.push(PocketDestination.Editor)

        assertTrue(state.canGoBack)
        assertEquals(PocketDestination.Editor, state.current)

        assertTrue(state.pop())
        assertEquals(PocketDestination.Home, state.current)
        assertFalse(state.pop())
    }

    @Test
    fun pop_atTabRoot_isNoop() {
        val state = NavigationState()
        assertFalse(state.pop())
    }

    @Test
    fun fromId_roundTripsEveryDestination() {
        PocketDestination.bottomTabs.forEach { tab ->
            assertEquals(tab, PocketDestination.fromId(tab.id))
        }
        assertEquals(null, PocketDestination.fromId("nope"))
    }

    @Test
    fun bottomTabs_containsExactlyFiveUniqueIds() {
        assertEquals(5, PocketDestination.bottomTabs.size)
        assertEquals(5, PocketDestination.bottomTabs.map { it.id }.toSet().size)
    }
}
