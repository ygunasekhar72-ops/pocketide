package com.pocketide.mobile.devicecheck

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Environment facts sampled from the REAL device state via official Android
 * APIs only (Build, PackageManager, permission checks, ConnectivityManager,
 * the app's own APK digest). Every fact ends up in the report verbatim.
 */
object DeviceFacts {

    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    fun collect(context: Context): Map<String, String> {
        val facts = linkedMapOf<String, String>()
        facts["device.model"] = Build.MODEL ?: "?"
        facts["device.manufacturer"] = Build.MANUFACTURER ?: "?"
        facts["device.os"] = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        facts["device.abis"] = Build.SUPPORTED_ABIS?.joinToString(",") ?: "?"
        facts["device.kernel"] = System.getProperty("os.arch") ?: "?"

        try {
            val pm = context.packageManager
            val pkg = pm.getPackageInfo(context.packageName, 0)
            val versionName = pkg.versionName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            }
            facts["app.version"] = "v$versionName ($versionCode)"
            val first = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(pkg.firstInstallTime))
            facts["app.installedAt"] = first
        } catch (t: Throwable) {
            facts["app.version"] = "unavailable: ${t.message}"
        }

        facts["app.apkSha256"] = ownApkSha256(context)

        facts["termux.installed"] = try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            "true"
        } catch (_: PackageManager.NameNotFoundException) {
            "false"
        }

        facts["permission.runCommand"] = when {
            ContextCompat.checkSelfPermission(context, RUN_COMMAND_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED -> "true"
            else -> "false"
        }
        return facts
    }

    /** SHA-256 of the installed APK, read from this app's own sourceDir. */
    fun ownApkSha256(context: Context): String = try {
        val apk = File(context.applicationInfo.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        "unavailable: ${t.message}"
    }

    /**
     * One connectivity sample for the airplane-mode criterion. Returns
     * "offline" when no network carries INTERNET, otherwise "online(n=<count>)".
     */
    fun sampleNetworkState(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var online = 0
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) online++
        }
        if (online == 0) "offline" else "online(n=$online)"
    } catch (t: Throwable) {
        "unknown: ${t.message}"
    }

    fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
}
