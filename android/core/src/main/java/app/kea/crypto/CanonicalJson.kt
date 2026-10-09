package app.kea.crypto

/**
 * A JSON codec with a single fixed shape, used for sealed payloads.
 *
 * kotlinx.serialization would sort nothing and format numbers its own way, so
 * the Android, iOS and Python implementations would disagree on the bytes and
 * the reference vector in PROTOCOL.md §5 would be worthless. This exists purely
 * to make the wire format portable:
 *
 *  - object keys sorted by Unicode code point (matches Python `sort_keys`)
 *  - no whitespace (matches Python `separators=(",", ":")`)
 *  - doubles rendered with the shortest round-trip form
 *
 * Payload schemas are small and closed, so a full JSON parser is not worth the
 * dependency or the attack surface.
 */
object CanonicalJson {

    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Int -> out.append(value.toString())
            is Long -> out.append(value.toString())
            is Double -> out.append(number(value))
            is Float -> out.append(number(value.toDouble()))
            is Map<*, *> -> {
                out.append('{')
                // Sort by the UTF-16 code unit of each key, which for ASCII keys
                // — the only kind we emit — is identical to code-point order.
                val entries = value.entries
                    .filter { it.key is String }
                    .map { it.key as String to it.value }
                    .sortedBy { it.first }
                entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    writeString(out, k)
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            is ByteArray -> error("ByteArray has no canonical JSON form")
            else -> error("unsupported payload type ${value::class.java.name}")
        }
    }

    /**
     * Shortest representation that round-trips, matching Python's `repr(float)`.
     * Non-finite values are not representable in JSON and are rejected rather
     * than silently turning into something a peer would misread.
     */
    private fun number(d: Double): String {
        require(d.isFinite()) { "non-finite number in payload: $d" }
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) {
            // Python renders 34.0, not 34.
            val asLong = d.toLong()
            "$asLong.0"
        } else {
            d.toString()
        }
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '' -> out.append("\\f")
                else ->
                    if (ch < ' ') out.append("\\u").append("%04x".format(ch.code))
                    else out.append(ch)
            }
        }
        out.append('"')
    }

    /**
     * Decode the shapes we emit. Deliberately strict: unknown structures and
     * malformed escapes throw rather than being tolerated, because a payload
     * that survived Ed25519 verification is already trusted and any parse
     * surprise means the sender and receiver disagree about the format.
     */
    fun decode(text: String): Map<String, Any?> {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        p.requireEnd()
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?> ?: error("payload root is not an object")
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun requireEnd() {
            if (i != s.length) error("trailing data at $i")
        }

        fun readValue(): Any? {
            if (i >= s.length) error("unexpected end of input")
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else ->
                    if (c == '-' || c.isDigit()) readNumber()
                    else error("unexpected character '$c' at $i")
            }
        }

        private fun readLiteral(text: String, value: Any?): Any? {
            if (!s.startsWith(text, i)) error("bad literal at $i")
            i += text.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWs()
                val k = readString()
                skipWs()
                expect(':')
                skipWs()
                out[k] = readValue()
                skipWs()
                when (val c = next()) {
                    ',' -> Unit
                    '}' -> return out
                    else -> error("expected , or } at ${i - 1} but found '$c'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return out }
            while (true) {
                skipWs()
                out += readValue()
                skipWs()
                when (val c = next()) {
                    ',' -> Unit
                    ']' -> return out
                    else -> error("expected , or ] at ${i - 1} but found '$c'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) error("unterminated string")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> sb.append(readEscape())
                    else -> sb.append(c)
                }
            }
        }

        private fun readEscape(): Char {
            if (i >= s.length) error("unterminated escape")
            return when (val c = s[i++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'b' -> '\b'
                'f' -> ''
                'u' -> {
                    if (i + 4 > s.length) error("truncated \\u escape")
                    val hex = s.substring(i, i + 4)
                    i += 4
                    hex.toIntOrNull(16)?.toChar() ?: error("bad \\u escape '$hex'")
                }
                else -> error("bad escape '\\$c'")
            }
        }

        private fun readNumber(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isFloat = false
            if (i < s.length && s[i] == '.') {
                isFloat = true
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isFloat = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val text = s.substring(start, i)
            return if (isFloat) {
                text.toDoubleOrNull() ?: error("bad number '$text'")
            } else {
                val v = text.toLongOrNull() ?: text.toDoubleOrNull() ?: error("bad number '$text'")
                if (v is Long && v < Int.MIN_VALUE.toLong() || v is Long && v > Int.MAX_VALUE.toLong()) v
                else (v as Long).toInt()
            }
        }

        private fun peek(): Char = if (i < s.length) s[i] else ' '
        private fun next(): Char = if (i < s.length) s[i++] else error("unexpected end of input")

        private fun expect(c: Char) {
            if (next() != c) error("expected '$c' at ${i - 1}")
        }
    }
}