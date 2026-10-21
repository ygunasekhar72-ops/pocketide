package com.pocketide.mobile.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chunk-boundary correctness of the incremental UTF-8 decoder used for
 * PTY output rendering: arbitrary read splittings must preserve text;
 * invalid input degrades to U+FFFD like a real terminal renderer would.
 */
class Utf8StreamDecoderTest {

    private fun decodeAll(bytes: ByteArray, cuts: List<Int>): String {
        val decoder = Utf8StreamDecoder()
        var offset = 0
        val out = StringBuilder()
        for (cut in cuts + listOf(bytes.size)) {
            val end = minOf(cut, bytes.size)
            if (end > offset) {
                out.append(decoder.decode(bytes.copyOfRange(offset, end)))
                offset = end
            }
        }
        out.append(decoder.flush())
        return out.toString()
    }

    @Test
    fun `ascii stream survives byte-level cuts`() {
        val bytes = "hello world".encodeToByteArray()
        for (i in 0..bytes.size) assertEquals("hello world", decodeAll(bytes, listOf(i)))
    }

    @Test
    fun `two byte sequences survive cuts`() {
        val text = "aéz"
        val bytes = text.encodeToByteArray()
        for (i in 0..bytes.size) assertEquals(text, decodeAll(bytes, listOf(i)))
    }

    @Test
    fun `three byte sequences survive cuts`() {
        val text = "x中y€z"
        val bytes = text.encodeToByteArray()
        for (i in 0..bytes.size) assertEquals(text, decodeAll(bytes, listOf(i)))
    }

    @Test
    fun `four byte surrogate sequences survive cuts`() {
        val text = "a😀b"
        val bytes = text.encodeToByteArray()
        for (i in 0..bytes.size) assertEquals(text, decodeAll(bytes, listOf(i)))
    }

    @Test
    fun `invalid lead byte becomes one replacement and continues`() {
        val decoder = Utf8StreamDecoder()
        assertEquals(
            "\uFFFDa",
            decoder.decode(byteArrayOf(0x80.toByte()) + "a".encodeToByteArray())
        )
        assertEquals(
            "\uFFFDbc",
            decoder.decode(byteArrayOf(0xff.toByte()) + "bc".encodeToByteArray())
        )
    }

    @Test
    fun `interrupted sequence emits replacement then resumes at the interrupt`() {
        val decoder = Utf8StreamDecoder()
        val result = decoder.decode(
            byteArrayOf("中".encodeToByteArray()[0], 'A'.code.toByte())
        )
        assertEquals("\uFFFDA", result)
        // The decoder is ready for fresh content afterwards.
        assertEquals("b", decoder.decode("b".encodeToByteArray()))
    }

    @Test
    fun `incomplete tail flushes as a replacement`() {
        val decoder = Utf8StreamDecoder()
        val tail = "中A".encodeToByteArray().copyOf(2) // E4 B8, sequence not complete
        assertEquals("", decoder.decode(tail))
        assertEquals(1, decoder.flush().codePoints().toArray().size)
    }

    @Test
    fun `flush of empty tail is empty`() {
        val decoder = Utf8StreamDecoder()
        decoder.decode("ok".encodeToByteArray())
        assertEquals("", decoder.flush())
    }

    @Test
    fun `long mixed stream survives arbitrary cuts`() {
        val text = "return € 12 😀\n"
        val bytes = text.encodeToByteArray()
        for (i in 0..bytes.size) assertEquals(text, decodeAll(bytes, listOf(i)))
        assertEquals(text, decodeAll(bytes, listOf(1, 2, 3, 5, 8, 13, 21, 34)))
    }
}
