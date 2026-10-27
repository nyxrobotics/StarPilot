package link.firestar.companion

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * A strict JSON reader for input that comes over the Bluetooth link (a job list from comma, a push frame, a bridge health answer, a report body).
 *
 * Why not `org.json`: its rules differ between the Android library and the JVM test library (duplicate keys, single quotes, comments, unquoted keys), and a
 * parser that decides whether a car position is shown must behave the same everywhere. This reader accepts RFC 8259 JSON and nothing else: no comment, no
 * trailing comma, no `NaN`, no leading zero, no raw control character in a string, nesting at most [MAX_DEPTH] deep, input at most [maxChars] long. Numbers are kept
 * as their text so that the caller decides what is an integer (a counter), a finite double (a coordinate) or too large. Every failure is an [Invalid]; nothing
 * else escapes (a fuzz test holds that).
 */
internal object StrictJson {
    const val MAX_DEPTH = 16

    class Invalid(message: String) : IllegalArgumentException(message)

    /** The JSON value `null` (Kotlin's null means "no such key"). */
    object Null

    /** A JSON number as written. */
    class Num(val text: String) {
        val isInteger: Boolean get() = text.none { it == '.' || it == 'e' || it == 'E' }
        fun toLongOrNull(): Long? = if (isInteger) text.toLongOrNull() else null
        fun toDoubleOrNull(): Double? = text.toDoubleOrNull()
        override fun toString() = text
    }

    /** An object: [members] keeps the order of the text (a repeated key keeps its last value and sets [duplicateKey]); [raw] is the exact text of the object. */
    class Obj(val members: LinkedHashMap<String, Any?>, val duplicateKey: Boolean, val raw: String) {
        operator fun get(key: String): Any? = members[key]
        fun has(key: String) = members.containsKey(key)
    }

    fun parse(text: String, maxChars: Int = 1 shl 20): Any {
        if (text.length > maxChars) throw Invalid("too long")
        val reader = Reader(text)
        reader.skipSpace()
        val value = reader.value(0)
        reader.skipSpace()
        if (!reader.atEnd) throw Invalid("trailing characters")
        return value
    }

    fun parseObject(text: String, maxChars: Int = 1 shl 20): Obj = parse(text, maxChars) as? Obj ?: throw Invalid("not an object")

    /** UTF-8 that is not well formed is refused (a lenient decoder would turn damage into replacement characters). */
    fun parseObject(bytes: ByteArray, maxChars: Int = 1 shl 20): Obj {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { throw Invalid("not UTF-8") }
        return parseObject(text, maxChars)
    }

    private class Reader(private val s: String) {
        private var i = 0
        val atEnd get() = i >= s.length

        fun skipSpace() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }

        fun value(depth: Int): Any {
            if (depth > MAX_DEPTH) throw Invalid("too deep")
            if (atEnd) throw Invalid("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", Null)
                else -> if (c == '-' || c in '0'..'9') number() else throw Invalid("unexpected character")
            }
        }

        private fun literal(word: String, result: Any): Any {
            if (!s.startsWith(word, i)) throw Invalid("bad literal")
            i += word.length
            return result
        }

        private fun obj(depth: Int): Obj {
            val start = i
            i++ // {
            val members = LinkedHashMap<String, Any?>()
            var duplicate = false
            skipSpace()
            if (!atEnd && s[i] == '}') { i++; return Obj(members, false, s.substring(start, i)) }
            while (true) {
                skipSpace()
                if (atEnd || s[i] != '"') throw Invalid("key expected")
                val key = string()
                skipSpace()
                if (atEnd || s[i] != ':') throw Invalid("colon expected")
                i++
                skipSpace()
                val item = value(depth + 1)
                if (members.containsKey(key)) duplicate = true
                members[key] = item
                skipSpace()
                if (atEnd) throw Invalid("unexpected end")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return Obj(members, duplicate, s.substring(start, i)) }
                    else -> throw Invalid("comma or brace expected")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            i++ // [
            val items = ArrayList<Any?>()
            skipSpace()
            if (!atEnd && s[i] == ']') { i++; return items }
            while (true) {
                skipSpace()
                items.add(value(depth + 1))
                skipSpace()
                if (atEnd) throw Invalid("unexpected end")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return items }
                    else -> throw Invalid("comma or bracket expected")
                }
            }
        }

        private fun string(): String {
            i++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd) throw Invalid("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c < ' ' -> throw Invalid("control character in string")
                    c == '\\' -> {
                        if (atEnd) throw Invalid("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw Invalid("short unicode escape")
                                var code = 0
                                for (k in 0 until 4) {
                                    val d = Character.digit(s[i + k], 16)
                                    if (d < 0 || s[i + k].code > 127) throw Invalid("bad unicode escape")
                                    code = code * 16 + d
                                }
                                i += 4
                                out.append(code.toChar())
                            }
                            else -> throw Invalid("bad escape '${if (e.code in 32..126) e else '?'}'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Num {
            val start = i
            if (s[i] == '-') i++
            if (atEnd) throw Invalid("bad number")
            if (s[i] == '0') i++
            else if (s[i] in '1'..'9') { while (!atEnd && s[i] in '0'..'9') i++ }
            else throw Invalid("bad number")
            if (!atEnd && s[i] == '.') {
                i++
                val digits = i
                while (!atEnd && s[i] in '0'..'9') i++
                if (i == digits) throw Invalid("bad fraction")
            }
            if (!atEnd && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (!atEnd && (s[i] == '+' || s[i] == '-')) i++
                val digits = i
                while (!atEnd && s[i] in '0'..'9') i++
                if (i == digits) throw Invalid("bad exponent")
            }
            return Num(s.substring(start, i))
        }
    }
}
