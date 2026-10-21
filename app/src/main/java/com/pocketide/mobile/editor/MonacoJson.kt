package com.pocketide.mobile.editor

/**
 * Minimal, dependency-free JSON support for the Monaco editor bridge.
 *
 * Scope (deliberately small, fully unit-tested):
 *  - encoding: [encodeString] — a single JSON string value (used whenever
 *    Kotlin hands document text to JavaScript via evaluateJavascript, so
 *    the emitted string can never be parsed as code);
 *  - parsing: [parse] — general JSON documents with STRING / NUMBER /
 *    BOOLEAN / NULL / OBJECT / ARRAY values, no trailing garbage.
 *
 * Numbers are kept as their raw text ([Json.Number.raw]) so bridge code can
 * apply its own range validation (per the bridge contract, values decoded
 * from the webview are treated as untrusted).
 *
 * Unicode escapes are decoded per code unit, which faithfully reconstructs
 * surrogate pairs only when both halves are escaped/present (matching the
 * JSON spec's unit-based semantics).
 */
object MonacoJson {

    sealed interface Json {
        data class Str(val value: String) : Json
        data class Number(val raw: String) : Json
        data class Bool(val value: Boolean) : Json
        data object Null : Json
        data class Obj(val entries: Map<String, Json>) : Json
        data class Arr(val items: List<Json>) : Json
    }

    /** Encodes [s] as a single JSON string (quotes + escapes). */
    fun encodeString(s: String): String {
        val out = StringBuilder(s.length + 2)
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch.code < 0x20) {
                    out.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    /** Parses [raw]; null on malformed input or trailing garbage. */
    fun parse(raw: String): Json? {
        if (raw.isBlank()) return null
        val p = Parser(raw)
        val value = p.parseValue() ?: return null
        p.skipWhitespace()
        return if (p.atEnd()) value else null
    }

    private class Parser(private val input: String) {
        var position = 0

        fun atEnd(): Boolean = position >= input.length

        fun skipWhitespace() {
            while (position < input.length) {
                val c = input[position]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') position++ else break
            }
        }

        fun parseValue(): Json? {
            skipWhitespace()
            if (atEnd()) return null
            return when (input[position]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(): Json? {
            position++ // {
            val entries = LinkedHashMap<String, Json>()
            skipWhitespace()
            if (!atEnd() && input[position] == '}') { position++; return Json.Obj(entries) }
            while (true) {
                skipWhitespace()
                if (atEnd() || input[position] != '"') return null
                val name = parseString() as? Json.Str ?: return null
                skipWhitespace()
                if (atEnd() || input[position] != ':') return null
                position++
                val value = parseValue() ?: return null
                entries[name.value] = value
                skipWhitespace()
                when {
                    atEnd() -> return null
                    input[position] == ',' -> position++
                    input[position] == '}' -> { position++; return Json.Obj(entries) }
                    else -> return null
                }
            }
        }

        private fun parseArray(): Json? {
            position++ // [
            val list = ArrayList<Json>()
            skipWhitespace()
            if (!atEnd() && input[position] == ']') { position++; return Json.Arr(list) }
            while (true) {
                val item = parseValue() ?: return null
                list.add(item)
                skipWhitespace()
                when {
                    atEnd() -> return null
                    input[position] == ',' -> position++
                    input[position] == ']' -> { position++; return Json.Arr(list) }
                    else -> return null
                }
            }
        }

        fun parseString(): Json? {
            position++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) return null
                val c = input[position]
                position++
                when {
                    c == '"' -> return Json.Str(out.toString())
                    c == '\\' -> {
                        if (atEnd()) return null
                        val e = input[position]
                        position++
                        when (e) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (position + 4 > input.length) return null
                                var cp = 0
                                for (k in 0 until 4) {
                                    val d = input[position + k].digitToIntOrNull(16) ?: return null
                                    cp = (cp shl 4) or d
                                }
                                position += 4
                                out.append(cp.toChar())
                            }
                            else -> return null
                        }
                    }
                    c.code < 0x20 -> return null
                    else -> out.append(c)
                }
            }
        }

        private fun parseBool(): Json? =
            when {
                input.startsWith("true", position) -> { position += 4; Json.Bool(true) }
                input.startsWith("false", position) -> { position += 5; Json.Bool(false) }
                else -> null
            }

        private fun parseNull(): Json? =
            if (input.startsWith("null", position)) { position += 4; Json.Null } else null

        private fun parseNumber(): Json? {
            skipWhitespace()
            var end = position
            while (end < input.length && input[end] in "0123456789+-.eE") end++
            if (end == position) return null
            val raw = input.substring(position, end)
            // A conservative number shape; anything else is rejected.
            if (!NUMBER_PATTERN.matches(raw)) return null
            position = end
            return Json.Number(raw)
        }

        private companion object {
            val NUMBER_PATTERN = Regex("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        }
    }

    /** Convenience: named string field of a flat [Json.Obj]. */
    fun stringField(json: Json, name: String): String? {
        val obj = json as? Json.Obj ?: return null
        val field = obj.entries[name] as? Json.Str ?: return null
        return field.value
    }
}
