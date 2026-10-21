package com.pocketide.mobile.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/**
 * The only T3 code that touches Android runtime APIs; everything above it
 * stays platform-independent and unit-testable off-device. Implements
 * [TermuxEnvironment] probes and the [TermuxTransport] dispatch lifecycle:
 *
 * OUTBOUND: builds the RUN_COMMAND broadcast EXACTLY from the validated
 * [RunCommandRequest] — executable path into
 * [TermuxPluginConstants.EXTRA_RUN_COMMAND_PATH], the argument words
 * VERBATIM into the arguments extra (a string array: no shell, no quoting,
 * no concatenation anywhere in this file), stdin/workdir when non-null
 * (workdir cannot occur via the sender's shape rules), forced
 * `background=true`, and a trusted command label carrying the catalogId.
 *
 * INBOUND (result collection): per dispatch, registers a one-shot receiver
 * for OUR OWN result action. Security posture of that receiver:
 *  - bound to a fresh random nonce delivered only inside the PendingIntent
 *    for THAT dispatch: any broadcast that does not match the pending nonce
 *    is ignored (cross-dispatch confusion and id-guessing mitigations);
 *  - registered with receiver permission
 *    `com.termux.permission.RUN_COMMAND`, so external senders must hold the
 *    same permission this integration requires (Termux itself holds it);
 *  - explicitly exported on API 33+ (required for foreign delivery) — the
 *    full spoofing/threat assessment on real hardware is scheduled with the
 *    device phase (P3e), and until then the receiver treats UNEXPECTED
 *    payload shapes as parser-level receipts, never as command outcomes;
 *  - unregistered on every terminal path: result accepted, timeout, or
 *    caller cancellation.
 *
 * PendingIntent: created with FLAG_MUTABLE (API 31+) because Termux Service
 * fills the result bundle into it; before 31 mutability is the default.
 */
class AndroidTermuxAgentTransport(context: Context) : TermuxEnvironment, TermuxTransport {

    private val appContext: Context = context.applicationContext

    private fun probeInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(
            TermuxPluginConstants.TERMUX_PACKAGE_NAME,
            0
        )
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Probed fresh per dispatch: uninstall mid-session must fail honestly. */
    override fun isTermuxInstalled(): Boolean = probeInstalled()

    override fun hasRunCommandPermission(): Boolean =
        appContext.checkSelfPermission(
            TermuxPluginConstants.PERMISSION_RUN_COMMAND
        ) == PackageManager.PERMISSION_GRANTED

    override suspend fun dispatch(request: RunCommandRequest): TermuxResultView {
        val nonce = nextNonce()
        lastNonce = nonce
        val commandIntent = buildRunCommandIntent(request, nonce)
        val latch = kotlinx.coroutines.CompletableDeferred<TermuxResultView>()

        onResultIntent = { intent ->
            val bundle: Bundle? = intent?.extras
            val seen = bundle?.getLong(RESULT_NONCE_EXTRA, Long.MIN_VALUE)
            if (seen != null && seen == nonce) {
                // Deliver the ROOT payload view: the parser owns the nested
                // 'result' unwinding and treats its absence as a receipt
                // failure. Never pre-extract here: pre-extraction would hide
                // malformed receipts from the parser's integrity checks.
                latch.complete(BundleView(bundle))
            }
            // Nonce mismatch: ignored silently, this dispatch keeps waiting
            // until its budget expires (fail closed, never consumed wrong).
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                onResultIntent?.invoke(intent)
            }
        }
        val filter = IntentFilter(RESULT_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(
                receiver, filter, TermuxPluginConstants.PERMISSION_RUN_COMMAND,
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(
                receiver, filter, TermuxPluginConstants.PERMISSION_RUN_COMMAND, null
            )
        }

        val resultIntent = buildResultDeliveryIntent(nonce)
        val pendingFlags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext, requestCodeSource.getAndIncrement(), resultIntent, pendingFlags
        )
        // The result channel is ONLY this PendingIntent: Termux fills the
        // result bundle into it and fires it back. Without the
        // RUN_COMMAND_PENDING_INTENT extra the command runs with no
        // deliverable result, so the extra is mandatory at the send point.
        commandIntent.putExtra(
            TermuxPluginConstants.EXTRA_RUN_COMMAND_PENDING_INTENT,
            pendingIntent
        )
        try {
            appContext.sendBroadcast(commandIntent)
        } catch (e: SecurityException) {
            appContext.unregisterReceiver(receiver)
            throw e
        }

        try {
            return latch.await()
        } finally {
            appContext.unregisterReceiver(receiver)
            onResultIntent = null
        }
    }

    @Volatile
    internal var onResultIntent: ((Intent?) -> Unit)? = null

    /** Nonce of the most recent dispatch start (test hook for delivery simulation). */
    @Volatile
    internal var lastNonce: Long = Long.MIN_VALUE

    private val requestCodeSource = java.util.concurrent.atomic.AtomicInteger(1)
    private val nonceSource = java.security.SecureRandom()
    private fun nextNonce(): Long = nonceSource.nextLong()

    internal fun buildRunCommandIntent(request: RunCommandRequest, nonce: Long): Intent =
        Intent(TermuxPluginConstants.ACTION_RUN_COMMAND)
            .setPackage(TermuxPluginConstants.TERMUX_PACKAGE_NAME)
            .putExtra(TermuxPluginConstants.EXTRA_RUN_COMMAND_PATH, request.executablePath)
            .putExtra(
                TermuxPluginConstants.EXTRA_RUN_COMMAND_ARGUMENTS,
                request.arguments.toTypedArray()
            )
            .putExtra(TermuxPluginConstants.EXTRA_RUN_COMMAND_BACKGROUND, request.background)
            .putExtra(TermuxPluginConstants.EXTRA_RUN_COMMAND_COMMAND_LABEL, request.commandLabel)
            .also { intent ->
                request.stdin?.let { stdin ->
                    intent.putExtra(TermuxPluginConstants.EXTRA_RUN_COMMAND_STDIN, stdin)
                }
                request.workdir?.let { workdir ->
                    intent.putExtra(TermuxPluginConstants.EXTRA_RUN_COMMAND_WORKDIR, workdir.path)
                }
                intent.putExtra(RESULT_NONCE_EXTRA, nonce)
                intent.putExtra(RESULT_ACTION_EXTRA, RESULT_ACTION)
            }

    /** The base intent stored in the PendingIntent created for THIS dispatch. */
    internal fun buildResultDeliveryIntent(nonce: Long): Intent =
        Intent(RESULT_ACTION)
            .setPackage(appContext.packageName)
            .putExtra(RESULT_NONCE_EXTRA, nonce)
            .putExtra(RESULT_ACTION_EXTRA, RESULT_ACTION)

    private class BundleView(val bundle: Bundle?) : TermuxResultView {
        override fun stringOrNull(key: String): String? =
            if (bundle?.containsKey(key) == true) bundle.getString(key) else null

        override fun intOrNull(key: String): Int? =
            if (bundle?.containsKey(key) == true) bundle.getInt(key) else null

        override fun innerBundle(key: String): TermuxResultView? =
            if (bundle?.containsKey(key) == true) BundleView(bundle.getBundle(key))
            else null
    }

    private companion object {
        /** Our own delivery action; used as the PendingIntent base intent action. */
        internal const val RESULT_ACTION = "com.pocketide.mobile.agent.ACTION_RUN_COMMAND_RESULT"
        internal const val RESULT_NONCE_EXTRA = "com.pocketide.mobile.agent.RESULT_NONCE"
        internal const val RESULT_ACTION_EXTRA = "com.pocketide.mobile.agent.RESULT_ACTION"
    }
}
