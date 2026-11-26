package pl.qus.wolin.parser

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.Diagnostics
import kotlin.test.assertTrue

/** Simple enum declarations: anonymous and named, at file scope. */
class EnumGrammarTest {

    private fun parse(src: String): Boolean {
        val diag = Diagnostics()
        ParserFacade(diag).parse(src, "t.ktk")
        return diag.messages.isEmpty()
    }

    @Test fun `an anonymous enum with explicit values parses`() {
        assertTrue(parse("enum { PENDING = 0, RUNNING = 5, ERROR = 255 }"))
    }

    @Test fun `an anonymous enum with autonumbered entries parses`() {
        assertTrue(parse("enum { A, B, C }"))
    }

    @Test fun `a named enum parses`() {
        assertTrue(parse("enum Status { PENDING, RUNNING = 5 }"))
    }

    @Test fun `newlines and a trailing comma parse`() {
        assertTrue(parse("enum Status {\n    IDLE,\n    BUSY = 0x10,\n    DONE,\n}"))
    }
}
