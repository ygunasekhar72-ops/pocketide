package com.pocketide.mobile.devicecheck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
        val action = intent.action
        if (action != ACTION_TRIGGER && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val service = Intent(context, ValidationAutorunService::class.java)
        try {
            context.startForegroundService(service)
        } catch (t: Throwable) {
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
            } catch (_: Throwable) {
                // Push transport unavailable; nothing more can be recorded here.
            }
        }
    }

    companion object {
        const val ACTION_TRIGGER = "com.pocketide.mobile.devicecheck.RUN"
    }
}
