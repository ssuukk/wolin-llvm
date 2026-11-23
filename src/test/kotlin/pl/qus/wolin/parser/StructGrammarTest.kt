package pl.qus.wolin.parser

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.Diagnostics
import kotlin.test.assertTrue

/** A struct declares a named packed layout. */
class StructGrammarTest {

    private fun parse(src: String): Boolean {
        val diag = Diagnostics()
        ParserFacade(diag).parse(src, "t.ktk")
        return diag.messages.isEmpty()
    }

    @Test fun `a struct with fields parses`() {
        assertTrue(parse("struct Point { var x: ubyte; var y: uword }"))
    }

    @Test fun `a struct array type parses`() {
        assertTrue(parse("struct Point { var x: ubyte; var y: ubyte }\nvar points: Point[ubyte, 4]"))
    }

    @Test fun `a field access on an array element parses`() {
        assertTrue(parse("struct Point { var x: ubyte }\nfun main() { points[0].x = 1 }"))
    }
}
