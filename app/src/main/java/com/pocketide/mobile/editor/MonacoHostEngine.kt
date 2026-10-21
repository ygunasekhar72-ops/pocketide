package com.pocketide.mobile.editor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

/**
 * The single trusted URL the editor WebView may load. Served from the APK
 * assets through [WebViewAssetLoader]; the index.html itself is project
 * code (not model- or web-controlled content).
 */
const val MONACO_EDITOR_URL: String =
    "https://appassets.androidplatform.net/assets/monaco/index.html"

/**
 * Name the Kotlin side is injected as: same value in the harness
 * (app/src/main/assets/monaco/index.html). Change both together.
 */
const val MONACO_BRIDGE_NAME: String = "pocketIDEHost"

/** Parsed and validated content of a `reportContent` payload. */
data class MonacoContentPayload(val content: String, val language: String)

/**
 * Pure protocol layer between the editor harness and Kotlin. Kept free of
 * Android types so message contracts are unit-testable on the plain JVM:
 *  - inbound payloads are parsed + shape/size validated (treated as
 *    untrusted input even though the harness is project code, in case any
 *    script content is ever inj/composed wrongly);
 *  - outbound calls are BUILDERS returning ready-to-run JavaScript, with
 *    every string argument JSON-escaped by [MonacoJson.encodeString] so a
 *    crafted document can never inject code into the scripts it travels
 *    through. (Document text rides as JSON string data, never as code.)
 */
object MonacoProtocol {

    /** Document size bound shared by store + bridge (chars). */
    const val MAX_DOCUMENT_CHARS: Int = MonacoEditorStore.MAX_DOCUMENT_CHARS

    /** Parses `reportContent`; null when malformed or oversized. */
    fun parseContentPayload(raw: String): MonacoContentPayload? {
        val obj = MonacoJson.parse(raw) as? MonacoJson.Json.Obj ?: return null
        val content = (obj.entries["content"] as? MonacoJson.Json.Str)?.value ?: return null
        val language = (obj.entries["language"] as? MonacoJson.Json.Str)?.value
            ?: return null
        if (content.length > MAX_DOCUMENT_CHARS) return null
        if (content.isEmpty()) return null
        if (language.length > 64 || language.isBlank()) return null
        return MonacoContentPayload(content, language)
    }

    /** Parses `reportWindow`; bounded via [MonacoImeWindows.MAX_WINDOW_CHARS]. */
    fun parseWindowPayload(raw: String): MonacoImeWindow? =
        MonacoImeWindows.parseWindow(raw)

    // ---- outbound script builders ----

    fun scriptSetContent(content: String, language: String): String =
        "window.pocketIDE.setContent(${MonacoJson.encodeString(content)}, " +
            "${MonacoJson.encodeString(language)});"

    fun scriptPullContent(): String =
        "window.pocketIDE.reportContent();"

    fun scriptInsertText(text: String): String =
        "window.pocketIDE.insertAtCursor(${MonacoJson.encodeString(text)});"

    fun scriptMoveCursor(direction: String): String {
        require(direction in setOf("left", "right", "up", "down")) {
            "unknown cursor direction: $direction"
        }
        return "window.pocketIDE.moveCursor(${MonacoJson.encodeString(direction)});"
    }

    fun scriptInsertTab(): String = "window.pocketIDE.insertTab();"

    fun scriptEscape(): String = "window.pocketIDE.editorEscape();"

    /**
     * IME window edit: [edit] bounds are validated here (never trusting the
     * caller blindly) and the offsets arrive as plain JSON numbers to the
     * harness's offset-anchored edit command.
     */
    fun scriptApplyWindowEdit(edit: MonacoImeWindowEdit): String {
        require(edit.offset >= 0) { "edit offset must be >= 0" }
        require(edit.deleteCount >= 0) { "edit deleteCount must be >= 0" }
        require(
            edit.offset.toLong() + edit.deleteCount.toLong() <= MAX_MODEL_EDIT_OFFSET
        ) { "edit range exceeds the model bound" }
        return "window.pocketIDE.applyWindowEdit(${edit.offset}, ${edit.deleteCount}, " +
            "${MonacoJson.encodeString(edit.replacement)});"
    }

    /** Sanity ceiling offset range (well beyond any legally stored doc). */
    const val MAX_MODEL_EDIT_OFFSET: Long = MonacoEditorStore.MAX_DOCUMENT_CHARS.toLong() + 1024
}

/**
 * The Android-owned medium for the editor harness: builds the WebView
 * (asset-loader origin, hardened settings, one JS bridge object), hands
 * validated callbacks to the owner, and runs the trusted Kotlin->JS
 * commands through [MonacoProtocol] builders on the main thread.
 *
 * Threading: [Callbacks] fire on the MAIN thread (bridge calls arrive on
 * the WebView's JavaBridge thread and are re-dispatched). All public
 * commands are main-thread-only (Compose call sites are on main too).
 */
class MonacoHostEngine(
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onReady()
        fun onContentLoaded(content: String, language: String)
        fun onImeWindow(window: MonacoImeWindow)
        fun onEngineError(detail: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    private var documentToPush: MonacoDocument? = null
    private var lastPushedContent: String? = null
    private var lastPushedLanguage: String? = null

    @Volatile
    private var bridgeReady = false

    /** Factory entry point; called exactly once per screen instance. */
    fun createWebView(context: Context): WebView {
        val settings = WebView(context)
        with(settings.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        // Remote and file URLs are rejected in [WebViewClientCompat]; only
        // the asset-loader origin may load.
        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        settings.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Any URL outside https://appassets.androidplatform.net is
                // untrusted-editor navigation and must not load. (The
                // asset-loader origin is also the only one whose subresource
                // requests are ever answered, so file/remote URLs would both
                // fail to load AND be blocked here.)
                val url = request.url
                return !(url.scheme == "https" &&
                    url.host == "appassets.androidplatform.net" &&
                    url.path != null && url.path!!.startsWith("/assets/"))
            }
        }
        // The bridge is injected BEFORE loadUrl so the harness handshake
        // always observes it.
        settings.addJavascriptInterface(Bridge(), MONACO_BRIDGE_NAME)
        webView = settings
        settings.loadUrl(MONACO_EDITOR_URL)
        return settings
    }

    /** Marks the document the editor should hold once (and only if) ready. */
    fun pushDocument(document: MonacoDocument) {
        documentToPush = document
        maybePushDocument()
    }

    fun pullContent() {
        if (!bridgeReady) return
        eval(MonacoProtocol.scriptPullContent())
    }

    fun insertText(text: String) {
        if (!bridgeReady) return
        eval(MonacoProtocol.scriptInsertText(text))
    }

    fun moveCursor(direction: String) {
        if (!bridgeReady) return
        eval(MonacoProtocol.scriptMoveCursor(direction))
    }

    fun insertTab() {
        if (!bridgeReady) return
        eval(MonacoProtocol.scriptInsertTab())
    }

    fun escape() {
        if (!bridgeReady) return
        eval(MonacoProtocol.scriptEscape())
    }

    fun applyImeWindowEdit(edit: MonacoImeWindowEdit) {
        if (!bridgeReady) return
        eval(MonacoProtocol.scriptApplyWindowEdit(edit))
    }

    /** Composable disposal: cancels any pending work and frees the view. */
    fun detach() {
        webView?.let { wv ->
            wv.loadUrl("about:blank")
            wv.destroy()
        }
        webView = null
        bridgeReady = false
        documentToPush = null
        lastPushedContent = null
        lastPushedLanguage = null
    }

    /** Re-entrancy guard: pushing an already-pushed document is a no-op. */
    private fun maybePushDocument() {
        if (!bridgeReady) return
        val document = documentToPush ?: return
        if (document.content == lastPushedContent &&
            document.language == lastPushedLanguage
        ) return
        if (document.content.length > MonacoProtocol.MAX_DOCUMENT_CHARS) {
            callbacks.onEngineError(
                "document exceeds the ${MonacoProtocol.MAX_DOCUMENT_CHARS} char edit buffer"
            )
            return
        }
        lastPushedContent = document.content
        lastPushedLanguage = document.language
        eval(MonacoProtocol.scriptSetContent(document.content, document.language))
    }

    private fun eval(script: String) {
        // WebView commands must run on the main thread (Compose callers are
        // already there; a re-dispatch keeps the contract explicit).
        mainHandler.post {
            webView?.evaluateJavascript(script, null)
        }
    }

    /**
     * The JS bridge object. It exposes ONLY the four callback entry points;
     * every payload is (re)validated, everything is forwarded to the main
     * thread, and any malformed input becomes an [Callbacks.onEngineError]
     * rather than an unvalidated state mutation.
     */
    inner class Bridge {
        @JavascriptInterface
        fun notifyReady(detail: String) {
            mainHandler.post {
                bridgeReady = true
                maybePushDocument()
                callbacks.onReady()
            }
        }

        @JavascriptInterface
        fun reportContent(raw: String) {
            val content = MonacoProtocol.parseContentPayload(raw)
            mainHandler.post {
                if (content == null) {
                    callbacks.onEngineError("editor content payload was malformed or oversized")
                } else {
                    callbacks.onContentLoaded(content.content, content.language)
                }
            }
        }

        @JavascriptInterface
        fun reportWindow(raw: String) {
            val window = MonacoProtocol.parseWindowPayload(raw)
            mainHandler.post {
                if (window == null) {
                    // Malformed windows are dropped to stop a stuck IME
                    // surface from lagging so far behind the model it
                    // becomes a parallel editor; keeping the window is the
                    // least-surprise fallback.
                    callbacks.onEngineError("editor IME window payload was malformed")
                } else {
                    callbacks.onImeWindow(window)
                }
            }
        }
    }
}
