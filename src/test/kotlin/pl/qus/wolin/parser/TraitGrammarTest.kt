package pl.qus.wolin.parser

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.Diagnostics
import kotlin.test.assertTrue

/** `trait`, `impl Type` and `impl Type : Trait` parse. */
class TraitGrammarTest {

    private fun parse(src: String): Boolean {
        val diag = Diagnostics()
        ParserFacade(diag).parse(src, "t.ktk")
        return diag.messages.isEmpty()
    }

    @Test fun `a trait with signatures parses`() {
        assertTrue(parse("trait Shape { fun area(): uword; fun name(): string }"))
    }

    @Test fun `a trait with a default body parses`() {
        assertTrue(parse("trait Shape { fun area(): uword { return 1 } }"))
    }

    @Test fun `an inherent impl parses`() {
        assertTrue(parse("struct P { var x: ubyte }\nimpl P { fun bump() { this.x++ } }"))
    }

    @Test fun `a trait impl parses`() {
        assertTrue(
            parse(
                "trait Shape { fun area(): uword }\n" +
                    "struct P { var x: ubyte }\n" +
                    "impl P : Shape { fun area(): uword { return this.x.toUword() } }"
            )
        )
    }

    @Test fun `this in a method body parses`() {
        assertTrue(parse("struct P { var x: ubyte }\nimpl P { fun bump() { this.x = 1 } }"))
    }
}
