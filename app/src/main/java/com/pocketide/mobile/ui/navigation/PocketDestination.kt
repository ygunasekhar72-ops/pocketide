package com.pocketide.mobile.ui.navigation

/**
 * The five shell destinations. `symbol` is rendered as a monospace glyph in
 * the bottom bar; Material's core icon set has no terminal/editor symbols
 * and the extended icon pack would add megabytes for five icons.
 */
sealed class PocketDestination(val id: String, val label: String, val symbol: String) {
    data object Home : PocketDestination("home", "Home", "PD")
    data object Editor : PocketDestination("editor", "Editor", "</> ")
    data object Terminal : PocketDestination("terminal", "Terminal", ">_")
    data object Assistant : PocketDestination("assistant", "AI", "AI")
    data object Settings : PocketDestination("settings", "Settings", "::")

    companion object {
        /** Canonical bottom-bar order. */
        val bottomTabs = listOf(Home, Editor, Terminal, Assistant, Settings)

        fun fromId(id: String): PocketDestination? = when (id) {
            Home.id -> Home
            Editor.id -> Editor
            Terminal.id -> Terminal
            Assistant.id -> Assistant
            Settings.id -> Settings
            else -> null
        }
    }
}
