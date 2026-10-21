package com.pocketide.mobile.editor

/**
 * Document + cursor snapshot of the Monaco model used to drive the IME
 * bridge. The model is modeled by a WINDOW slice around the editor cursor
 * (see the `reportWindow` harness contract): [text] is exactly what the
 * IME surface displays and edits, anchored at [windowStartOffset] in the
 * full model, with [cursorInWindow] the caret inside the slice.
 */
data class MonacoImeWindow(
    val windowStartOffset: Int,
    val cursorInWindow: Int,
    val text: String
) {
    /** A window entry derived from an untrusted JSON payload — validated at creation. */
    init {
        require(windowStartOffset >= 0) { "windowStartOffset must be >= 0" }
        require(cursorInWindow >= 0) { "cursorInWindow must be >= 0" }
    }
}

/**
 * Result of diffing an IME edit: an optional old/new window text delta
 * anchored at a model offset. [deleteCount] characters at [offset] in the
 * model are replaced by [replacement].
 */
data class MonacoImeWindowEdit(
    val offset: Int,
    val deleteCount: Int,
    val replacement: String
)

/**
 * Pure IME-window edit computation ([MonacoImeWindows], old -> new).
 *
 * The IME surfaces always edit the WINDOW text (what the user sees and
 * keys into); this object converts such edits to model-anchored edits:
 *
 *  - longest common prefix P and common suffix S identify the change region;
 *  - the change region spans [P, old.length - S) replaced by
 *    new[P, new.length - S);
 *  - the model anchor is windowStartOffset + P.
 *
 * `extractWindowEdit(old, new, windowStartOffset)` returns null for a
 * no-op pair, and is deterministic regardless of IME granularity.
 */
object MonacoImeWindows {

    fun extractWindowEdit(
        windowBefore: String,
        windowAfter: String,
        windowStartOffset: Int
    ): MonacoImeWindowEdit? {
        if (windowBefore == windowAfter || windowStartOffset < 0) return null
        var prefix = 0
        val maxPrefix = minOf(windowBefore.length, windowAfter.length)
        while (prefix < maxPrefix &&
            windowBefore[prefix] == windowAfter[prefix]
        ) prefix++
        var suffix = 0
        val maxSuffix = minOf(windowBefore.length - prefix, windowAfter.length - prefix)
        while (suffix < maxSuffix &&
            windowBefore[windowBefore.length - 1 - suffix] == windowAfter[windowAfter.length - 1 - suffix]
        ) suffix++
        val deleteCount = windowBefore.length - prefix - suffix
        val replacement = windowAfter.substring(prefix, windowAfter.length - suffix)
        if (deleteCount == 0 && replacement.isEmpty()) return null
        return MonacoImeWindowEdit(
            offset = windowStartOffset + prefix,
            deleteCount = deleteCount,
            replacement = replacement
        )
    }

    /**
     * Extracts a validated [MonacoImeWindow] from an untrusted
     * `reportWindow` JSON payload (string/number fields per the harness
     * contract); null when malformed or out of documented bounds.
     */
    fun parseWindow(jsonRaw: String, maxWindowChars: Int = MAX_WINDOW_CHARS): MonacoImeWindow? {
        val json = MonacoJson.parse(jsonRaw) as? MonacoJson.Json.Obj ?: return null
        val startRaw = (json.entries["windowStartOffset"] as? MonacoJson.Json.Number) ?: return null
        val cursorRaw = (json.entries["cursorInWindow"] as? MonacoJson.Json.Number) ?: return null
        val text = (json.entries["text"] as? MonacoJson.Json.Str)?.value ?: return null
        val start = startRaw.raw.toIntOrNull() ?: return null
        val cursor = cursorRaw.raw.toIntOrNull() ?: return null
        if (start < 0) return null
        if (cursor < 0) return null
        if (text.length > maxWindowChars) return null
        return MonacoImeWindow(
            windowStartOffset = start,
            cursorInWindow = cursor,
            text = text
        )
    }

    /** Binds "cursor window" size to keep the IME surface tractable. */
    const val MAX_WINDOW_CHARS: Int = 1024
}
