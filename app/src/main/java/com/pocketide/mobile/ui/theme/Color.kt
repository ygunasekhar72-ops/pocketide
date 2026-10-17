package com.pocketide.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * PocketIDE design tokens — dark-first.
 *
 * Surfaces are near-black charcoal so code content dominates; the purple
 * primary is reserved for identity/navigation accents, the mint secondary for
 * positive/secondary states. Semantic success/warning colors live in
 * [PocketExtendedColors] (Material 3 has no first-class slots for them).
 */

// Brand accents
val PocketPrimary = Color(0xFFA78BFA)
val PocketOnPrimary = Color(0xFF1A1333)
val PocketPrimaryContainer = Color(0xFF2E2052)
val PocketOnPrimaryContainer = Color(0xFFE6DEFF)

val PocketSecondary = Color(0xFF7BE0C0)
val PocketOnSecondary = Color(0xFF0B2A20)
val PocketSecondaryContainer = Color(0xFF12372A)
val PocketOnSecondaryContainer = Color(0xFFD2F6E7)

// Charcoal surfaces (dark-first)
val PocketBackground = Color(0xFF0E0E12)
val PocketOnBackground = Color(0xFFE7E7EE)
val PocketSurface = Color(0xFF15151C)
val PocketOnSurface = Color(0xFFE7E7EE)
val PocketSurfaceVariant = Color(0xFF1D1D26)
val PocketOnSurfaceVariant = Color(0xFFA2A2B0)
val PocketSurfaceContainer = Color(0xFF191921)
val PocketOutline = Color(0xFF2C2C38)
val PocketOutlineVariant = Color(0xFF232330)

// Status
val PocketError = Color(0xFFF58E8E)
val PocketOnError = Color(0xFF3A1212)
val PocketErrorContainer = Color(0xFF521A1A)
val PocketOnErrorContainer = Color(0xFFFAD9D9)
