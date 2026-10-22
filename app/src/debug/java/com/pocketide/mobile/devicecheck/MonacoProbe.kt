package com.pocketide.mobile.devicecheck

import com.pocketide.mobile.editor.MonacoDocument
import com.pocketide.mobile.editor.MonacoHostEngine
import com.pocketide.mobile.editor.MonacoImeWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns a REAL [MonacoHostEngine] plus the callback flows the validation
 * probes await. The WebView itself is mounted by the harness UI (its factory
 * calls [createProbeWebView] exactly once); the engine lifecycle rules are
 * the production ones: push-before-ready is queued, readiness flips only
 * through the real JS bridge handshake.
 */
class MonacoProbeEngine {

    private val readyState = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = readyState.asStateFlow()

    /** Version counter bumped on every onContentLoaded, with the last content. */
    private val contentVersion = MutableStateFlow(0)
    private val lastContent = MutableStateFlow("")

    /** Window observation counters for packet discipline checks. */
    val windowEventCount = MutableStateFlow(0)
    val errorEvents = MutableStateFlow("")

    var webView: android.webkit.WebView? = null
        private set

    /** Callbacks the engine calls on its own main thread. */
    internal val callbacks = object : MonacoHostEngine.Callbacks {
        override fun onReady() {
            readyState.value = true
        }

        override fun onContentLoaded(content: String, language: String) {
            lastContent.value = content
            contentVersion.value += 1
        }

        override fun onImeWindow(window: MonacoImeWindow) {
            windowEventCount.value += 1
        }

        override fun onEngineError(detail: String) {
            errorEvents.value = detail
        }
    }

    val engine = MonacoHostEngine(callbacks)

    /** UI-only entry point; must be called exactly once from main thread. */
    fun createProbeWebView(context: android.content.Context): android.webkit.WebView {
        check(webView == null) { "probe engine WebView already mounted" }
        val view = engine.createWebView(context)
        webView = view
        return view
    }

    /** Awaits a new content callback since [fromVersion]; null on timeout. */
    suspend fun awaitContent(fromVersion: Int, timeoutMs: Long = 12_000): String? {
        val observed = withTimeoutOrNull(timeoutMs) {
            contentVersion.first { it > fromVersion }
        }
        return if (observed != null) lastContent.value else null
    }

    /** Current content callback version, for dedupe-safe awaits. */
    fun contentVersionNow(): Int = contentVersion.value
}

/**
 * Validation probe behind [EditorProbe]. Every call walks the REAL engine
 * scripts through the real WebView bridge and observes the REAL report
 * callbacks; timeouts surface as null so the runner can fail honestly.
 */
class MonacoEditorProbe(
    private val holder: MonacoProbeEngine
) : EditorProbe {

    override suspend fun contentRoundTrip(content: String, language: String): String? {
        val before = holder.contentVersionNow()
        holder.engine.pushDocument(MonacoDocument(content, language))
        val seen = holder.awaitContent(before) ?: return null
        return seen
    }

    override suspend fun insertAndReport(text: String): String? {
        val before = holder.contentVersionNow()
        holder.engine.insertText(text)
        holder.engine.pullContent()
        return holder.awaitContent(before)
    }

    override suspend fun cursorAwareInsert(move: String, text: String): String? {
        val before = holder.contentVersionNow()
        holder.engine.moveCursor(move)
        holder.engine.insertText(text)
        holder.engine.pullContent()
        return holder.awaitContent(before)
    }
}
