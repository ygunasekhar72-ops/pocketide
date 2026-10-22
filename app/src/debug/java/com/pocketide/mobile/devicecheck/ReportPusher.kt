package com.pocketide.mobile.devicecheck

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Delivers the validation report to Termux through the documented Termux
 * RUN_COMMAND service path (startForegroundService to com.termux.app.
 * RunCommandService) writing the JSON via stdin to a file in Termux home.
 *
 * This mirrors the transport contract the M1 agent transport should be using
 * (see AndroidTermuxAgentTransport review notes): an explicit service start
 * with the plugin extras, not a broadcast.
 */
object ReportPusher {

    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val RUN_COMMAND_PKG = "com.termux"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

    const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"

    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
    const val REPORT_PATH = "$TERMUX_HOME/pocketide-validation.json"

    /** Pure builder (testable): the exact service intent the push sends. */
    fun buildRunCommandIntent(reportJson: String): Intent = Intent().apply {
        setAction(ACTION_RUN_COMMAND)
        setClassName(RUN_COMMAND_PKG, RUN_COMMAND_SERVICE)
        setPackage(RUN_COMMAND_PKG)
        putExtra(EXTRA_PATH, BASH_PATH)
        putExtra(EXTRA_ARGS, arrayOf("-c", "cat > $REPORT_PATH"))
        putExtra(EXTRA_WORKDIR, TERMUX_HOME)
        putExtra(EXTRA_BACKGROUND, true)
        putExtra(EXTRA_STDIN, reportJson)
    }

    /**
     * Sends the report. Throws IllegalStateException when Termux is missing
     * or the service is unresolvable so the UI can surface the failure
     * instead of silently losing evidence.
     */
    fun push(context: Context, reportJson: String) {
        val pm = context.packageManager
        val serviceIntent = Intent(ACTION_RUN_COMMAND).setPackage(RUN_COMMAND_PKG)
        val serviceExists = try {
            pm.resolveService(serviceIntent, 0) != null
        } catch (_: Exception) {
            false
        }
        if (!serviceExists) {
            throw IllegalStateException("Termux RunCommandService not resolvable (Termux missing or service renamed)")
        }
        val intent = buildRunCommandIntent(reportJson)
        ContextCompat.startForegroundService(context, intent)
    }
}
