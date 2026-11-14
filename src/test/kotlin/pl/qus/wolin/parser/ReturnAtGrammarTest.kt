package pl.qus.wolin.parser

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.sema.AstBuilder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `return@<literal>` already parsed before this test existed — it reaches
 * the AST through the lexer's `LabelDefinition : Identifier '@'` rule, not
 * through the `RETURN_AT` token. `AstBuilder.buildPrefix` sees the label
 * `return@` and records the following integer literal as the jump target.
 *
 * These tests pin that behaviour: a later change to the lexer or to the
 * builder must not silently turn `return@0xea31` into a plain return.
 */
class ReturnAtGrammarTest {
    private fun returnAtAddresses(src: String): List<Int> {
        val diag = Diagnostics()
        val tree = ParserFacade(diag).parse(src, "t.ktk")
        assertTrue(diag.messages.isEmpty(), "syntax errors: ${diag.messages}")
        val program = AstBuilder().build(tree!!)
        return program.functions[0].returnAt.map { it.address }
    }

    @Test fun `return at a hex address records the address`() {
        assertEquals(listOf(0xea31), returnAtAddresses("fun f() { return@0xea31 }"))
    }

    @Test fun `return at a decimal address records the address`() {
        assertEquals(listOf(59953), returnAtAddresses("fun f() { return@59953 }"))
    }

    @Test fun `the address does not depend on the case of the hex digits`() {
        assertEquals(listOf(0xea31), returnAtAddresses("fun f() { return@0xEA31 }"))
    }

    @Test fun `return at address zero records zero`() {
        assertEquals(listOf(0), returnAtAddresses("fun f() { return@0 }"))
    }

    @Test fun `every return at site in a function is recorded`() {
        val addresses = returnAtAddresses(
            """
            fun f() {
                if (true) { return@0xea31 }
                return@0xffd2
            }
            """.trimIndent()
        )
        assertEquals(listOf(0xea31, 0xffd2), addresses)
    }

    @Test fun `the old corpus parses with its four return at sites`() {
        val src = javaClass.getResource("/corpus/raster_irq.ktk")!!.readText()
        val diag = Diagnostics()
        val tree = ParserFacade(diag).parse(src, "raster_irq.ktk")
        assertTrue(diag.messages.isEmpty(), "syntax errors: ${diag.messages}")
        val program = AstBuilder().build(tree!!)
        val addresses = program.functions.flatMap { fn -> fn.returnAt.map { it.address } }
        assertEquals(4, addresses.size)
        assertTrue(
            addresses.all { it == 0xea31 },
            "every corpus site jumps to 0xea31, got $addresses",
        )
    }
}
