package net.estopia.hncrevision.data

/**
 * A small, lenient parser for JavaScript literals, used to read the site's data scripts.
 *
 * It accepts objects, arrays, strings in double or single quotes (with the JS escapes
 * `\n \t \r \b \f \v \0 \" \' \\ \/ \uXXXX \u{X…}`, line continuations and any other escaped char
 * as itself), numbers (including hex, leading `.`/`+`, exponents), `true`/`false`/`null`/`undefined`,
 * unquoted identifier keys, trailing commas, and `//` and `/* */` comments.
 *
 * Results are plain Kotlin values: [Map] (insertion ordered), [List], [String], [Long] for
 * integral numbers, [Double] otherwise, [Boolean] and `null`.
 */
object JsLiteral {

    class ParseException(message: String, val position: Int) : Exception("$message at $position")

    /**
     * Finds `const NAME =` (or `let`/`var`) in [source] and parses the single literal that follows.
     * Anything after the literal is ignored. Throws [ParseException] when the declaration is missing.
     */
    fun declaration(source: String, name: String): Any? {
        val match = Regex("""\b(?:const|let|var)\s+${Regex.escape(name)}\s*=""").find(source)
            ?: throw ParseException("Missing declaration $name", 0)
        return Reader(source, match.range.last + 1).readValue()
    }

    /** Parses [text] as one literal (JSON is a subset). Trailing content after it is ignored. */
    fun parse(text: String): Any? = Reader(text, 0).readValue()

    private class Reader(val s: String, var i: Int) {

        fun readValue(): Any? {
            skipSpace()
            if (i >= s.length) fail("Unexpected end of input")
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"', '\'' -> readString(c)
                '`' -> readTemplate()
                else -> when {
                    c == '-' || c == '+' || c == '.' || c.isDigit() -> readNumber()
                    isIdentStart(c) -> readKeyword()
                    else -> fail("Unexpected character '$c'")
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            i++ // {
            val result = LinkedHashMap<String, Any?>()
            while (true) {
                skipSpace()
                if (i >= s.length) fail("Unterminated object")
                if (s[i] == '}') { i++; return result }
                val key = when (val c = s[i]) {
                    '"', '\'' -> readString(c)
                    else -> when {
                        isIdentStart(c) -> readIdentifier()
                        c.isDigit() -> readNumber().let { n -> if (n is Double && n % 1.0 == 0.0) n.toLong().toString() else n.toString() }
                        else -> fail("Expected a key")
                    }
                }
                skipSpace()
                expect(':')
                result[key] = readValue()
                skipSpace()
                if (i >= s.length) fail("Unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return result }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            i++ // [
            val result = ArrayList<Any?>()
            while (true) {
                skipSpace()
                if (i >= s.length) fail("Unterminated array")
                if (s[i] == ']') { i++; return result }
                result.add(readValue())
                skipSpace()
                if (i >= s.length) fail("Unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return result }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun readString(quote: Char): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when {
                    c == quote -> return sb.toString()
                    c == '\\' -> readEscape(sb)
                    c == '\n' -> fail("Newline in string")
                    else -> sb.append(c)
                }
            }
        }

        /** Template literals without substitutions are treated as plain strings. */
        private fun readTemplate(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated template")
                val c = s[i++]
                when {
                    c == '`' -> return sb.toString()
                    c == '\\' -> readEscape(sb)
                    c == '$' && i < s.length && s[i] == '{' -> fail("Template substitutions are not supported")
                    else -> sb.append(c)
                }
            }
        }

        private fun readEscape(sb: StringBuilder) {
            if (i >= s.length) fail("Unterminated escape")
            when (val e = s[i++]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'v' -> sb.append('\u000B')
                '0' -> sb.append('\u0000')
                'u' -> {
                    if (i < s.length && s[i] == '{') {
                        val end = s.indexOf('}', i)
                        if (end < 0) fail("Bad unicode escape")
                        val cp = s.substring(i + 1, end).toIntOrNull(16) ?: fail("Bad unicode escape")
                        sb.appendCodePoint(cp)
                        i = end + 1
                    } else {
                        if (i + 4 > s.length) fail("Bad unicode escape")
                        val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("Bad unicode escape")
                        sb.append(code.toChar())
                        i += 4
                    }
                }
                'x' -> {
                    if (i + 2 > s.length) fail("Bad hex escape")
                    val code = s.substring(i, i + 2).toIntOrNull(16) ?: fail("Bad hex escape")
                    sb.append(code.toChar())
                    i += 2
                }
                '\r' -> if (i < s.length && s[i] == '\n') i++ // line continuation
                '\n', ' ', ' ' -> Unit // line continuation
                else -> sb.append(e) // \" \' \\ \/ and any other char as itself
            }
        }

        private fun readNumber(): Any {
            val start = i
            if (s[i] == '+' || s[i] == '-') i++
            if (i + 1 < s.length && s[i] == '0' && (s[i + 1] == 'x' || s[i + 1] == 'X')) {
                i += 2
                val hexStart = i
                while (i < s.length && (s[i].isDigit() || s[i].lowercaseChar() in 'a'..'f')) i++
                val value = s.substring(hexStart, i).toLongOrNull(16) ?: fail("Bad number")
                return if (s[start] == '-') -value else value
            }
            if (s.startsWith("Infinity", i)) {
                i += 8
                return if (s[start] == '-') Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            }
            var integral = true
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isDigit() || c == '_' -> i++
                    c == '.' || c == 'e' || c == 'E' -> { integral = false; i++ }
                    (c == '+' || c == '-') && (s[i - 1] == 'e' || s[i - 1] == 'E') -> i++
                    else -> break
                }
            }
            val text = s.substring(start, i).replace("_", "").removePrefix("+")
            if (text.isEmpty() || text == "-") fail("Bad number")
            if (integral) text.toLongOrNull()?.let { return it }
            return text.toDoubleOrNull() ?: fail("Bad number '$text'")
        }

        private fun readKeyword(): Any? = when (val word = readIdentifier()) {
            "true" -> true
            "false" -> false
            "null", "undefined" -> null
            "NaN" -> Double.NaN
            "Infinity" -> Double.POSITIVE_INFINITY
            else -> fail("Unexpected identifier '$word'")
        }

        private fun readIdentifier(): String {
            val start = i
            while (i < s.length && isIdentPart(s[i])) i++
            return s.substring(start, i)
        }

        private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'
        private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) fail("Expected '$c'")
            i++
        }

        private fun skipSpace() {
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() || c == '\uFEFF' -> i++
                    c == '/' && i + 1 < s.length && s[i + 1] == '/' -> {
                        while (i < s.length && s[i] != '\n') i++
                    }
                    c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                        val end = s.indexOf("*/", i + 2)
                        if (end < 0) fail("Unterminated comment")
                        i = end + 2
                    }
                    else -> return
                }
            }
        }

        private fun fail(message: String): Nothing = throw ParseException(message, i)
    }
}

/** Minimal JSON writer for persisting progress. Reads go back through [JsLiteral.parse]. */
object Json {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(sb, value)
            is Boolean -> sb.append(value)
            is Double -> if (value.isFinite()) sb.append(value) else sb.append("null")
            is Float -> append(sb, value.toDouble())
            is Number -> sb.append(value.toLong())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    append(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    append(sb, v)
                }
                sb.append(']')
            }
            else -> quote(sb, value.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }
}
