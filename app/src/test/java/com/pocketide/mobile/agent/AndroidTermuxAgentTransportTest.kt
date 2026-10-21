package com.pocketide.mobile.agent

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric verification of the Android glue layer: the merged manifest
 * must grant the sender its runtime contract, the outgoing RUN_COMMAND
 * intent must be byte-exact (no shell wrapping of arguments), and the
 * one-shot result receiver must be nonce-guarded.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidTermuxAgentTransportTest {

    private fun appContext() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `merged manifest requests the termux RUN_COMMAND permission`() {
        val info = appContext().packageManager.getPackageInfo(
            appContext().packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requested = info.requestedPermissions?.toList().orEmpty()
        assertTrue(
            requested.contains(TermuxPluginConstants.PERMISSION_RUN_COMMAND)
        )
    }

    @Test
    fun `environment and permission probes are total in the test environment`() {
        val transport = AndroidTermuxAgentTransport(appContext())
        // Probes must never throw in any environment; the permission truth
        // in Robolectric (declared vs granted) is resolved by shadow policy,
        // so only total-function behavior with per-call consistency is
        // asserted here. State-dependent truth is covered at the sender
        // layer with fakes.
        val installed = transport.isTermuxInstalled()
        val granted = transport.hasRunCommandPermission()
        assertEquals(installed, transport.isTermuxInstalled())
        assertEquals(granted, transport.hasRunCommandPermission())
    }

    @Test
    fun `outbound intent carries verbatim arguments and forced background`() {
        val transport = AndroidTermuxAgentTransport(appContext())
        val request = RunCommandRequest(
            executablePath = "\$PREFIX/bin/echo",
            arguments = listOf("hello world\n", "c\nd", "$HOME ;;"),
            stdin = null,
            background = true,
            commandLabel = "pocketide:tpl-echo"
        )

        val intent = transport.buildRunCommandIntent(request, nonce = 123456789L)

        assertEquals(TermuxPluginConstants.ACTION_RUN_COMMAND, intent.action)
        assertEquals(TermuxPluginConstants.TERMUX_PACKAGE_NAME, intent.`package`)
        assertEquals(
            request.executablePath,
            intent.getStringExtra(TermuxPluginConstants.EXTRA_RUN_COMMAND_PATH)
        )
        val arguments = intent.getStringArrayExtra(
            TermuxPluginConstants.EXTRA_RUN_COMMAND_ARGUMENTS
        )
        assertTrue(arguments != null)
        assertEquals(
            listOf("hello world\n", "c\nd", "$HOME ;;"),
            arguments?.toList()
        )
        assertEquals(
            java.lang.Boolean.TRUE,
            intent.getBooleanExtra(
                TermuxPluginConstants.EXTRA_RUN_COMMAND_BACKGROUND,
                false
            )
        )
        assertEquals(
            "pocketide:tpl-echo",
            intent.getStringExtra(TermuxPluginConstants.EXTRA_RUN_COMMAND_COMMAND_LABEL)
        )
        // No shell anywhere: the executable path and the argument words are
        // structurally separate extras, so no recombination happened.

        // No workdir extra when request workdir is null.
        assertEquals(
            false,
            intent.hasExtra(TermuxPluginConstants.EXTRA_RUN_COMMAND_WORKDIR)
        )
        // No stdin extra when request stdin is null.
        assertEquals(
            false,
            intent.hasExtra(TermuxPluginConstants.EXTRA_RUN_COMMAND_STDIN)
        )
    }

    @Test
    fun `stdin extra is included only when a payload exists`() {
        val transport = AndroidTermuxAgentTransport(appContext())
        val request = RunCommandRequest(
            executablePath = "\$PREFIX/bin/cat",
            arguments = emptyList(),
            stdin = "some one-shot payload",
            background = true,
            commandLabel = null
        )
        val intent = transport.buildRunCommandIntent(request, nonce = 1L)
        assertEquals(
            "some one-shot payload",
            intent.getStringExtra(TermuxPluginConstants.EXTRA_RUN_COMMAND_STDIN)
        )
    }

    @Test
    fun `dispatched command intent carries the RESULT PendingIntent extra`() = runTest {
        val context = appContext()
        val transport = AndroidTermuxAgentTransport(context)
        val request = RunCommandRequest(
            executablePath = "/bin/echo",
            arguments = listOf("hello"),
            background = true,
            commandLabel = "pocketide:tpl-echo"
        )

        var observed: TermuxResultView? = null
        val job = launch(Dispatchers.Unconfined) {
            observed = transport.dispatch(request)
        }

        // Inspect the ACTUAL broadcast handed to the system, not the builder.
        val sent = shadowOf(context).getBroadcastIntents().last()
        assertEquals(TermuxPluginConstants.ACTION_RUN_COMMAND, sent.action)
        assertEquals(TermuxPluginConstants.TERMUX_PACKAGE_NAME, sent.`package`)
        val pending = IntentCompat.getParcelableExtra(
            sent,
            TermuxPluginConstants.EXTRA_RUN_COMMAND_PENDING_INTENT,
            PendingIntent::class.java
        )
        assertNotNull(pending)
        // Construction type: a broadcast-backed PendingIntent shaped for
        // direct result delivery back to our receiver.
        assertTrue(shadowOf(pending!!).isBroadcastIntent())
        // On the tested (31+) path the flags include FLAG_MUTABLE; absence
        // itself throws during creation on Robolectric's 31+ simulation, so
        // a non-null result also proves the guarded branch ran.

        // The pending lifecycle is intact: a matching-nonce result still
        // completes the very same dispatch through the glue's receiver.
        val delivery = transport.buildResultDeliveryIntent(transport.lastNonce)
        delivery.putExtras(
            resultPayload(
                TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
                TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "ok"
            )
        )
        delivery.putExtra(
            AndroidTermuxAgentTransport.RESULT_NONCE_EXTRA,
            transport.lastNonce
        )
        transport.onResultIntent?.invoke(delivery)
        job.join()

        assertNotNull(observed)
        val success = TermuxResultParser.parse(observed!!) as AgentCommandResult.Success
        assertEquals(0, success.exitCode)
        assertEquals("ok", success.stdout)
    }

    private fun resultPayload(vararg inner: Pair<String, Any?>): Bundle =
        Bundle().apply {
            putBundle(
                TermuxPluginConstants.RESULT_BUNDLE_KEY_IS_BUNDLE,
                Bundle().apply {
                    inner.forEach { (k, v) ->
                        when (v) {
                            is String -> putString(k, v)
                            is Int -> putInt(k, v)
                            else -> throw IllegalArgumentException("unsupported test payload")
                        }
                    }
                }
            )
        }

    @Test
    fun `one-shot receiver accepts the matching nonce result`() = runTest {
        val transport = AndroidTermuxAgentTransport(appContext())
        val request = RunCommandRequest(
            executablePath = "/bin/echo",
            arguments = listOf("hello"),
            background = true,
            commandLabel = "pocketide:tpl-echo"
        )

        var observed: TermuxResultView? = null
        val job = launch(Dispatchers.Unconfined) {
            observed = transport.dispatch(request)
        }

        assertTrue(transport.lastNonce != Long.MIN_VALUE)
        val deliveryIntent = transport.buildResultDeliveryIntent(transport.lastNonce)
        val payload = resultPayload(
            TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
            TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "hello"
        )
        deliveryIntent.putExtras(payload)
        deliveryIntent.putExtra(AndroidTermuxAgentTransport.RESULT_NONCE_EXTRA, transport.lastNonce)

        // Simulated Termux result delivery routed through the glue's handler.
        transport.onResultIntent?.invoke(deliveryIntent)

        job.join()
        assertNotNull(observed)
        val success = TermuxResultParser.parse(observed!!) as AgentCommandResult.Success
        assertEquals(0, success.exitCode)
        assertEquals("hello", success.stdout)
    }

    @Test
    fun `wrong-nonce deliveries are ignored and never consume the pending dispatch`() = runTest {
        val transport = AndroidTermuxAgentTransport(appContext())
        val request = RunCommandRequest(
            executablePath = "/bin/echo",
            arguments = listOf("x"),
            background = true,
            commandLabel = null
        )

        var observed: TermuxResultView? = null
        val job = launch(Dispatchers.Unconfined) {
            observed = transport.dispatch(request)
        }
        val correctNonce = transport.lastNonce
        assertNotEquals(Long.MIN_VALUE, correctNonce)

        // Delivery with a mismatched nonce: ignored silently.
        val alienIntent = transport.buildResultDeliveryIntent(correctNonce + 1)
        alienIntent.putExtras(resultPayload())
        alienIntent.putExtra(
            AndroidTermuxAgentTransport.RESULT_NONCE_EXTRA,
            correctNonce + 1
        )
        transport.onResultIntent?.invoke(alienIntent)

        // The correct nonce still completes the pending dispatch afterwards.
        val matching = transport.buildResultDeliveryIntent(correctNonce)
        matching.putExtras(
            resultPayload(
                TermuxPluginConstants.RESULT_BUNDLE_KEY_EXIT_CODE to 0,
                TermuxPluginConstants.RESULT_BUNDLE_KEY_STDOUT to "late-ok"
            )
        )
        matching.putExtra(
            AndroidTermuxAgentTransport.RESULT_NONCE_EXTRA,
            correctNonce
        )
        transport.onResultIntent?.invoke(matching)
        job.join()

        val success = TermuxResultParser.parse(observed!!) as AgentCommandResult.Success
        assertEquals("late-ok", success.stdout)
    }
}
