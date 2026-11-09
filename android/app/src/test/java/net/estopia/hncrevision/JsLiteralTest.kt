package net.estopia.hncrevision

import net.estopia.hncrevision.data.Json
import net.estopia.hncrevision.data.JsLiteral
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsLiteralTest {

    @Test fun singleQuotedAndEscapedStrings() {
        val v = JsLiteral.parse("""['It\'s', "say \"hi\"", 'a\\b\/c', 'tab\there\nline', '· \u{1F600}', 'LAN B’s']""")
        assertEquals(listOf("It's", "say \"hi\"", "a\\b/c", "tab\there\nline", "· 😀", "LAN B’s"), v)
    }

    @Test fun trailingCommasUnquotedKeysAndComments() {
        val v = JsLiteral.parse(
            """
            // leading comment
            { a: 1, 'b': [1, 2, 3,], /* block
              comment */ "c": { d: true, e: null, }, f: -2.5e1, g: false, h_1: 'x', }
            """.trimIndent()
        )
        assertEquals(
            mapOf("a" to 1L, "b" to listOf(1L, 2L, 3L), "c" to mapOf("d" to true, "e" to null), "f" to -25.0, "g" to false, "h_1" to "x"),
            v,
        )
    }

    @Test fun declarationStopsAfterLiteral() {
        val src = """
            const other = 5;
            const exerciseHints = [
              ['one', 'two',],
            ];
            const hintButton = document.getElementById('vlsmHint');
            hintButton.addEventListener('click', () => { if (x) return; });
        """.trimIndent()
        assertEquals(listOf(listOf("one", "two")), JsLiteral.declaration(src, "exerciseHints"))
        assertEquals(5L, JsLiteral.declaration(src, "other"))
    }

    @Test(expected = JsLiteral.ParseException::class)
    fun missingDeclarationFails() {
        JsLiteral.declaration("const A = 1;", "B")
    }

    @Test fun declarationWithoutSpaces() {
        assertEquals(listOf(mapOf("code" to "X")), JsLiteral.declaration("const UNITS=[{\"code\": \"X\"}];", "UNITS"))
    }

    @Test fun jsonRoundTrip() {
        val value = mapOf("s" to "quote \" back \\ nl \n ·", "n" to 3L, "l" to listOf(true, null, 1.5), "m" to mapOf("k" to "v"))
        assertEquals(value, JsLiteral.parse(Json.write(value)))
    }

    @Test fun nullAndUndefined() {
        assertNull(JsLiteral.parse("undefined"))
    }
}
