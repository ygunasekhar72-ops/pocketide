package com.pocketide.mobile.devicecheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure contracts of the harness diagnostics recorder (no Android framework
 * access): single-line formatting, multi-line flattening, and empty handling.
 */
class DiagnosticsTest {

    @Test
    fun `formatLine is single line with epoch tag and message`() {
        val line = Diagnostics.formatLine(1234567890L, "runner", "execute start mode=ACTIVITY_AUTO")
        assertEquals("1234567890 [runner] execute start mode=ACTIVITY_AUTO\n", line)
        assertTrue(!line.dropLast(1).contains('\n'))
    }

    @Test
    fun `formatLine flattens stack traces to one line`() {
        val line = Diagnostics.formatLine(1L, "CRASH", " IllegalStateException: boom\n at x.y.z(Foo.kt:1)\n at a.b(Bar.kt:2)\n")
        assertTrue(line.count { it == '\n' } == 1)
        assertTrue(line.endsWith("\n"))
        assertTrue(line.contains("boom \\n  at x.y.z(Foo.kt:1) \\n  at a.b(Bar.kt:2)"))
    }

    @Test
    fun `formatLine handles empty message`() {
        assertEquals("5 [tag] <empty>\n", Diagnostics.formatLine(5L, "tag", ""))
    }
}
