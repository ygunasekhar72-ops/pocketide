package com.pocketide.mobile.devicecheck

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Headless validation service (debug-only). Started by the auto-run receiver,
 * it executes the real device matrix without any user interaction and publishes
 * the JSON report through the Termux RUN_COMMAND transport.
 */
class ValidationAutorunService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Diagnostics.installCrashRecorder(applicationContext)
        Diagnostics.log(applicationContext, "service", "onStartCommand id=$startId")
        startAsForeground()
        serviceScope.launch {
            try {
                if (HeadlessAutoRunner.isRunning()) {
                    Diagnostics.log(applicationContext, "service", "run already in flight; ending")
                    return@launch
                }
                val report = HeadlessAutoRunner.runNow(applicationContext, RunMode.HEADLESS_SERVICE)
                Diagnostics.log(
                    applicationContext, "service",
                    "finished: ${report?.summaryLine() ?: "skipped (concurrent run)"}"
                )
            } catch (t: Throwable) {
                Diagnostics.log(
                    applicationContext, "service/fatal", "run failed: " +
                        android.util.Log.getStackTraceString(t)
                )
                try {
                    val report = DeviceValidationReport(
                        deviceTestedAtIso = DeviceFacts.nowIso(),
                        facts = mapOf("run.mode" to RunMode.HEADLESS_SERVICE.name,
                            "run.error" to "${t::class.simpleName}: ${t.message}"),
                        groups = emptyList()
                    )
                    val json = report.serialize()
                    ReportPusher.push(applicationContext, json)
                } catch (pushFailure: Throwable) {
                    Diagnostics.log(
                        applicationContext, "service/push-error", "run.error push failed: " +
                            android.util.Log.getStackTraceString(pushFailure)
                    )
                }
            } finally {
                Diagnostics.log(applicationContext, "service", "stopping foreground id=$startId")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL_ID,
                        "P3e validation",
                        android.app.NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PocketIDE Dev Self-Check")
            .setContentText("Running headless device validation…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 991
        const val CHANNEL_ID = "p3e-validation"
    }
}
