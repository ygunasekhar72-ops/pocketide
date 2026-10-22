package com.pocketide.mobile.devicecheck

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric contract for the RUN_COMMAND push transport: the service intent
 * must carry the exact Termux plugin extras so the report travels as stdin
 * into a JSON file in the Termux home directory.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReportPusherTest {

    @Test
    fun `run command intent targets the documented Termux service path`() {
        val intent = ReportPusher.buildRunCommandIntent("""{"schema":"p3e-validation-1"}""")
        assertEquals(ReportPusher.ACTION_RUN_COMMAND, intent.action)
        assertEquals("com.termux", intent.`package`)
        val component = intent.component
        assertTrue(component != null)
        assertEquals("com.termux", component!!.packageName)
        assertEquals("com.termux.app.RunCommandService", component.className)
    }

    @Test
    fun `extras carry path args workdir background and stdin`() {
        val report = """{"facts":{"a":"b"}}"""
        val intent: Intent = ReportPusher.buildRunCommandIntent(report)
        assertEquals("/data/data/com.termux/files/usr/bin/bash", intent.getStringExtra(ReportPusher.EXTRA_PATH))
        assertArrayEquals(
            arrayOf("-c", "cat > ${ReportPusher.REPORT_PATH}"),
            intent.getStringArrayExtra(ReportPusher.EXTRA_ARGS)
        )
        assertEquals("/data/data/com.termux/files/home", intent.getStringExtra(ReportPusher.EXTRA_WORKDIR))
        assertEquals(true, intent.getBooleanExtra(ReportPusher.EXTRA_BACKGROUND, false))
        assertEquals(report, intent.getStringExtra(ReportPusher.EXTRA_STDIN))
    }

    @Test
    fun `report path points into termux home`() {
        assertTrue(
            ReportPusher.REPORT_PATH.startsWith("/data/data/com.termux/files/home/")
        )
    }
}
