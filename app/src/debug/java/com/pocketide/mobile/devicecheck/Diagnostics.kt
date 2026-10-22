package com.pocketide.mobile.devicecheck

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Debug-harness black box recorder. Every harness entry point (receiver,
 * service, activity, runner) appends milestones to a MediaStore Downloads
 * file owned by this app, so the Termux side can read what happened even when
 * the report push itself never completes. App-owned MediaStore inserts need
 * no extra permission; Termux reads the resulting shared file directly.
 */
object Diagnostics {

    const val LOG_NAME = "p3e-milestones.txt"
    private const val TAG = "P3eDiag"
    private val lock = Any()

    /** Pure formatter (unit-testable): epoch-millis + tag + single-line message. */
    fun formatLine(millis: Long, tag: String, message: String): String {
        val flattened = message
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\n", " \\n ")
            .ifEmpty { "<empty>" }
        return "$millis [$tag] $flattened\n"
    }

    fun log(context: Context, tag: String, message: String) {
        val line = formatLine(System.currentTimeMillis(), tag, message)
        synchronized(lock) {
            try {
                val uri = ensureRowUri(context)
                if (uri == null) {
                    android.util.Log.e(TAG, "no MediaStore row; milestone not written: ${line.trimEnd()}")
                    return
                }
                context.contentResolver.openOutputStream(uri, "wa")?.use { out ->
                    out.write(line.toByteArray(Charsets.UTF_8))
                } ?: android.util.Log.e(TAG, "output stream unavailable: ${line.trimEnd()}")
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "milestone write failed: ${t::class.simpleName}: ${t.message}"
                    + " | ${line.trimEnd()}")
            }
        }
    }

    /** Installs a process-wide uncaught-crash recorder that preserves the chain. */
    fun installCrashRecorder(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                log(
                    context,
                    "CRASH/${thread.name}",
                    "${throwable::class.java.name}: ${throwable.message}\n" +
                        android.util.Log.getStackTraceString(throwable)
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Publishes a run artifact (report JSON) into shared Download through the
     * app's own MediaStore row, independent of the Termux RUN_COMMAND
     * transport (which requires a permission Termux denies to third-party
     * callers on this build). Returns false when publication was impossible.
     */
    fun publish(context: Context, displayName: String, content: String): Boolean {
        synchronized(lock) {
            try {
                val resolver = context.contentResolver
                val uri = ensureItemUri(resolver, displayName)
                if (uri == null) {
                    android.util.Log.e(TAG, "no MediaStore row for $displayName")
                    return false
                }
                resolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: return false
                android.util.Log.i(TAG, "published $displayName (${content.length} bytes)")
                return true
            } catch (t: Throwable) {
                android.util.Log.e(
                    TAG, "publish $displayName failed: ${t::class.simpleName}: ${t.message}"
                )
                return false
            }
        }
    }

    private fun ensureItemUri(context: Context, displayName: String): Uri? {
        val resolver = context.contentResolver
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(displayName, "Download/")
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, PROJECTION, selection, args, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                }
            }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun ensureRowUri(context: Context): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // MediaStore.Downloads does not exist pre-29; scoped Download
            // writes were never possible for this harness there anyway.
            android.util.Log.e(TAG, "MediaStore Downloads unavailable on SDK ${Build.VERSION.SDK_INT}")
            return null
        }
        return try {
            ensureItemUri(context, LOG_NAME)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "MediaStore row provisioning failed: ${t::class.simpleName}: ${t.message}")
            null
        }
    }

    private val PROJECTION = arrayOf(MediaStore.MediaColumns._ID)

    fun debugBuildInfo(): String =
        "sdk=${Build.VERSION.SDK_INT} device=${Build.DEVICE}"
}
