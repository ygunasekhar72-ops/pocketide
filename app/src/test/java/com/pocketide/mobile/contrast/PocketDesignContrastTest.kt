package com.pocketide.mobile.contrast

import com.pocketide.mobile.ui.theme.PocketOnBackground
import com.pocketide.mobile.ui.theme.PocketOnPrimary
import com.pocketide.mobile.ui.theme.PocketOnSecondary
import com.pocketide.mobile.ui.theme.PocketOnSurface
import com.pocketide.mobile.ui.theme.PocketOnSurfaceVariant
import com.pocketide.mobile.ui.theme.PocketOnError
import com.pocketide.mobile.ui.theme.PocketBackground
import com.pocketide.mobile.ui.theme.PocketPrimary
import com.pocketide.mobile.ui.theme.PocketSecondary
import com.pocketide.mobile.ui.theme.PocketSurface
import com.pocketide.mobile.ui.theme.PocketSurfaceVariant
import com.pocketide.mobile.ui.theme.PocketError
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG contrast checks for the core token pairs, keeping the dark-first
 * design accessible (body text >= 4.5:1, large/icon-accent pairs >= 3.0:1).
 */
class PocketDesignContrastTest {

    private fun channel(c: Long, shift: Int): Double {
        val v = (c shr shift) and 0xFFL
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Long): Double {
        val r = channel(color, 16); val g = channel(color, 8); val b = channel(color, 0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(fore: Long, back: Long): Double {
        val l1 = luminance(fore); val l2 = luminance(back)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun argb(color: androidx.compose.ui.graphics.Color): Long =
        (color.value.toLong() and 0xFFFFFFL) or 0xFF000000L

    @Test
    fun bodyTextContrast_meetsAA() {
        assertTrue(contrast(argb(PocketOnBackground), argb(PocketBackground)) >= 4.5)
        assertTrue(contrast(argb(PocketOnSurface), argb(PocketSurface)) >= 4.5)
        assertTrue(contrast(argb(PocketOnSurface), argb(PocketSurfaceVariant)) >= 4.5)
        assertTrue(contrast(argb(PocketOnError), argb(PocketError)) >= 4.5)
    }

    @Test
    fun secondaryText_andAccents_meetLegibility() {
        // Muted text on charcoal — 4.5:1 target for UI labels.
        assertTrue(contrast(argb(PocketOnSurfaceVariant), argb(PocketSurface)) >= 4.5)
        // Accent glyphs on dark surfaces — large-scale 3:1 rule.
        assertTrue(contrast(argb(PocketPrimary), argb(PocketSurface)) >= 3.0)
        assertTrue(contrast(argb(PocketSecondary), argb(PocketSurface)) >= 3.0)
    }

    @Test
    fun onAccentPairs_meetAA() {
        assertTrue(contrast(argb(PocketOnPrimary), argb(PocketPrimary)) >= 4.5)
        assertTrue(contrast(argb(PocketOnSecondary), argb(PocketSecondary)) >= 4.5)
    }
}
