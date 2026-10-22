package com.pocketide.mobile.devicecheck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only trigger receiver. Two supported paths:
 *  1. Termux sends an explicit broadcast with [ACTION_TRIGGER] (the automated
 *     loop: Termux -> receiver -> foreground service -> headless matrix ->
 *     report pushed back to Termux).
 *  2. The system delivers ACTION_MY_PACKAGE_REPLACED right after an update
 *     install, so each new harness build validates itself immediately.
 */
class ValidationAutorunReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Diagnostics.installCrashRecorder(context)
        val action = intent.action
        Diagnostics.log(context, "receiver", "onReceive action=$action ${Diagnostics.debugBuildInfo()}")
        if (action != ACTION_TRIGGER && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val service = Intent(context, ValidationAutorunService::class.java)
        try {
            context.startForegroundService(service)
            Diagnostics.log(context, "receiver", "service start dispatched")
        } catch (t: Throwable) {
            Diagnostics.log(
                context, "receiver/fatal", "service start refused: " +
                    Log.getStackTraceString(t)
            )
            // Blocked from background: an honest marker through the push transport.
            try {
                ReportPusher.push(
                    context,
                    DeviceValidationReport(
                        deviceTestedAtIso = DeviceFacts.nowIso(),
                        facts = mapOf("run.error" to "receiver could not start service: ${t::class.simpleName}"),
                        groups = emptyList()
                    ).serialize()
                )
                Diagnostics.log(context, "receiver", "run.error push dispatched")
            } catch (pushFailure: Throwable) {
                Diagnostics.log(
                    context, "receiver/push-error", "run.error push failed: " +
                        Log.getStackTraceString(pushFailure)
                )
            }
        }
    }

    companion object {
        const val ACTION_TRIGGER = "com.pocketide.mobile.devicecheck.RUN"
    }
}
