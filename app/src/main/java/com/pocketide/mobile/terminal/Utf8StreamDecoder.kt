package com.pocketide.mobile.terminal

/**
 * Incremental UTF-8 decoder for terminal output streams.
 *
 * Why not `String(bytes, UTF_8)` per chunk: a multibyte sequence may be
 * split across chunk boundaries (pty chunks are arbitrary read sizes).
 * This decoder keeps only a bounded incomplete-tail state (at most the 3
 * continuation bytes of a started sequence) and emits exactly the completed
 * sequences, preserving text across arbitrary chunk boundaries.
 *
 * Deterministic and pure; never throws for arbitrary input. Invalid bytes
 * produce U+FFFD replacement (platform decoder behavior for the maximal
 * valid subpart), matching what a terminal emulator is expected to render.
 */
class Utf8StreamDecoder {

    /** Trailing bytes of a started-but-unfinished sequence (never > 3). */
    private val pending = ArrayList<Byte>(8)

    /** Decodes [chunk], returning the text of all completed sequences. */
    fun decode(chunk: ByteArray): String {
        if (chunk.isEmpty()) return ""
        val out = StringBuilder(chunk.size + 1)
        var i = 0
        var needed = if (pending.isEmpty()) 0 else continuationNeeded(pending[0], pending.size)

        while (i < chunk.size) {
            val b = chunk[i]
            if (needed > 0) {
                if (isContinuation(b)) {
                    pending.add(b)
                    needed--
                    i++
                    if (needed == 0) {
                        appendCompleted(out)
                    }
                } else {
                    // Sequence interrupted by a non-continuation byte: the
                    // buffered subpart is invalid; emit one replacement and
                    // restart decoding at the interrupting byte.
                    out.append(INVALID)
                    pending.clear()
                    needed = 0
                }
            } else {
                val lead = leadByteLength(b)
                when {
                    lead == 0 -> { out.append(INVALID); i++ } // invalid lead byte: replacement
                    lead == 1 -> { out.appendCodePoint(b.toInt() and 0x7F); i++ }
                    else -> {
                        pending.add(b)
                        needed = lead - 1
                        i++
                    }
                }
            }
        }
        return out.toString()
    }

    /**
     * Renders the incomplete tail as far as the platform decoder allows
     * (bounded: at most 3 bytes short of a full sequence). The decoder is
     * then ready for a fresh stream; call once per stream end.
     */
    fun flush(): String {
        if (pending.isEmpty()) return ""
        val bytes = pending.toByteArray()
        pending.clear()
        return String(bytes, Charsets.UTF_8)
    }

    private fun continuationNeeded(first: Byte, alreadyBuffered: Int): Int {
        val lead = leadByteLength(first)
        return (if (lead > 0) lead else 1) - alreadyBuffered
    }

    /** Expected total length for a lead byte; 0 for invalid lead bytes. */
    private fun leadByteLength(b: Byte): Int = when {
        b.toInt() and 0x80 == 0 -> 1
        b.toInt() and 0xE0 == 0xC0 -> 2
        b.toInt() and 0xF0 == 0xE0 -> 3
        b.toInt() and 0xF8 == 0xF0 -> 4
        else -> 0
    }

    private fun isContinuation(b: Byte): Boolean = b.toInt() and 0xC0 == 0x80

    /**
     * pending holds exactly one complete-sequence byte pattern; the platform
     * decoder owns validity semantics (overlong/illegal forms -> U+FFFD once).
     */
    private fun appendCompleted(out: StringBuilder) {
        val bytes = pending.toByteArray()
        pending.clear()
        val text = String(bytes, Charsets.UTF_8)
        out.append(text)
    }

    private companion object {
        const val INVALID: Char = '\uFFFD'
    }
}
