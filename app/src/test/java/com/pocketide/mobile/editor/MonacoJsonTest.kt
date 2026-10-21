package com.pocketide.mobile.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests of the dependency-free JSON codec used by the Monaco
 * editor bridge (both directions of the editor protocol).
 */
class MonacoJsonTest {

    // ---- encodeString ----

    @Test
    fun `plain text encodes verbatim with quotes`() {
        assertEquals("\"hello\"", MonacoJson.encodeString("hello"))
    }

    @Test
    fun `control characters and quotes are escaped`() {
        val encoded = MonacoJson.encodeString("a\"b\\c\nd\r\te\u000Cf让你")
        assertEquals("\"a\\\"b\\\\c\\nd\\r\\te\\ff让你\"", encoded)
    }

    @Test
    fun `empty string encodes as empty JSON string`() {
        assertEquals("\"\"", MonacoJson.encodeString(""))
    }

    @Test
    fun `characters below 20 encode as unicode escapes`() {
        assertEquals("\"\\u0001\"", MonacoJson.encodeString("\u0001"))
    }

    // ---- parse: valid shapes ----

    @Test
    fun `object parses with string and number members`() {
        val parsed = MonacoJson.parse("""{"content":"hi","line":42}""")
        val obj = parsed as? MonacoJson.Json.Obj
        assertTrue(obj != null)
        assertEquals("hi", (obj!!.entries["content"] as MonacoJson.Json.Str).value)
        assertEquals("42", (obj.entries["line"] as MonacoJson.Json.Number).raw)
    }

    @Test
    fun `nested objects and arrays parse`() {
        val parsed = MonacoJson.parse("""{"a":{"b":[1,"x",true,null]},"tail":"t"}""")
        val obj = parsed as? MonacoJson.Json.Obj ?: return org.junit.Assert.fail("obj")
        val a = obj.entries["a"] as? MonacoJson.Json.Obj ?: return org.junit.Assert.fail("a")
        val b = a.entries["b"] as? MonacoJson.Json.Arr ?: return org.junit.Assert.fail("arr")
        assertEquals(4, b.items.size)
        assertTrue(b.items[3] is MonacoJson.Json.Null)
        assertEquals("t", (obj.entries["tail"] as MonacoJson.Json.Str).value)
    }

    @Test
    fun `unicode escapes and surrogate pair decode`() {
        val parsed = MonacoJson.parse("""{"m":"aé😀z"}""")
        val m = ((parsed as MonacoJson.Json.Obj).entries["m"] as MonacoJson.Json.Str).value
        assertEquals("aé😀z", m)
    }

    @Test
    fun `escapes decode`() {
        val parsed = MonacoJson.parse("""{"m":"line\n\"quoted\"\\slash"}""")
        val m = ((parsed as MonacoJson.Json.Obj).entries["m"] as MonacoJson.Json.Str).value
        assertEquals("line\n\"quoted\"\\slash", m)
    }

    @Test
    fun `whitespace tolerant`() {
        val parsed = MonacoJson.parse("  { \"k\" :\t 12 \n }  ")
        val obj = parsed as? MonacoJson.Json.Obj
        assertTrue(obj != null && obj.entries.size == 1)
    }

    @Test
    fun `negative and exponent numbers parse`() {
        val parsed = MonacoJson.parse("""[ -12, 3.5, 1e-9, 2E+3 ]""")
        val arr = parsed as? MonacoJson.Json.Arr
        assertTrue(arr != null)
        assertEquals(4, arr!!.items.size)
        assertEquals("-12", (arr.items[0] as MonacoJson.Json.Number).raw)
        assertEquals("3.5", (arr.items[1] as MonacoJson.Json.Number).raw)
        assertEquals("1e-9", (arr.items[2] as MonacoJson.Json.Number).raw)
        assertEquals("2E+3", (arr.items[3] as MonacoJson.Json.Number).raw)
    }

    // ---- parse: adversarial / malformed ----

    @Test
    fun `trailing garbage is rejected`() {
        assertNull(MonacoJson.parse("""{"ok":true}}"""))
        assertNull(MonacoJson.parse("42 junk"))
    }

    @Test
    fun `control characters inside strings are rejected`() {
        assertNull(MonacoJson.parse("{\"a\":\"b\u0001c\"}"))
    }

    @Test
    fun `broken unicode escape is rejected`() {
        assertNull(MonacoJson.parse("""{"a":"é"}""".replace("é", "\\u12")))
    }

    @Test
    fun `invalid number shapes are rejected`() {
        assertNull(MonacoJson.parse("""{"a":12-}"""))
        assertNull(MonacoJson.parse("""{"a":+-}"""))
        assertNull(MonacoJson.parse("""{"a":.5}"""))
    }

    @Test
    fun `unterminated constructs are rejected`() {
        assertNull(MonacoJson.parse("{\"a\":"))
        assertNull(MonacoJson.parse("[1,"))
        assertNull(MonacoJson.parse("{"))
        assertNull(MonacoJson.parse("\"unterminated"))
    }

    @Test
    fun `empty and blank input rejected`() {
        assertNull(MonacoJson.parse(""))
        assertNull(MonacoJson.parse("   "))
    }
}
