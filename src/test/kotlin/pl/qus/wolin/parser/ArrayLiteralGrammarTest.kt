package pl.qus.wolin.parser

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The grammar rule `collectionLiteral` is the Wolin array literal. It must
 * parse single-line and multi-line forms, and reject trailing commas in
 * every form (single-line and multi-line).
 */
class ArrayLiteralGrammarTest {

    private fun parse(src: String): Boolean {
        val diag = Diagnostics()
        ParserFacade(diag).parse(src, "t.ktk")
        return diag.messages.isEmpty()
    }

    @Test fun `single-line array literal parses`() {
        assertTrue(parse("var x: ubyte[ubyte] = [1, 2, 3]"))
    }

    @Test fun `multi-line array literal parses`() {
        val src = """
            var x: ubyte[ubyte] = [
                1,
                2,
                3
            ]
        """.trimIndent()
        assertTrue(parse(src))
    }

    @Test fun `empty array literal parses`() {
        assertTrue(parse("var x: ubyte[ubyte] = []"))
    }

    @Test fun `multi-line empty array literal parses`() {
        assertTrue(parse("var x: ubyte[ubyte] = [\n]"))
    }

    @Test fun `trailing comma single-line is a parse error`() {
        assertFalse(parse("var x: ubyte[ubyte] = [1, 2, 3,]"))
    }

    @Test fun `trailing comma multi-line is a parse error`() {
        val src = """
            var x: ubyte[ubyte] = [
                1,
                2,
            ]
        """.trimIndent()
        assertFalse(parse(src))
    }
}
