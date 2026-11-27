package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An `external fun` declares a function whose body lives elsewhere — in a
 * Rust archive linked into the same program, for instance.
 *
 * The declaration has no body: the body is not the compiler's to emit. The
 * analyser accepts the declaration, checks its signature like any other
 * function's, and rejects a body on it.
 */
class ExternalFunctionTest {
    private fun check(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    @Test fun `an external function declaration is accepted`() {
        check("external fun add(a: uword, b: uword): uword")
    }

    @Test fun `an external function without a return type is accepted`() {
        check("external fun poke(p: ubyte^0xd020, v: ubyte)")
    }

    @Test fun `an external function is marked external`() {
        val program = check("external fun add(a: uword, b: uword): uword")
        val fn = program.functions.single()
        assertTrue(fn.isExternal, "the declaration must be marked external")
    }

    @Test fun `an ordinary function is not external`() {
        val program = check("fun add(a: uword, b: uword): uword { return a + b }")
        val fn = program.functions.single()
        assertTrue(!fn.isExternal, "an ordinary function must not be marked external")
    }

    @Test fun `an external function with a body is rejected`() {
        val e = assertFailsWith<SemaException> {
            check("external fun add(a: uword, b: uword): uword { return a + b }")
        }
        assertTrue(
            e.message!!.contains("body", ignoreCase = true),
            "message should name the body, got: ${e.message}",
        )
    }

    @Test fun `an external function call is type checked`() {
        val e = assertFailsWith<SemaException> {
            check("external fun add(a: uword, b: uword): uword\nfun main() { add(1, 2, 3) }")
        }
        assertTrue(e.message!!.contains("argument"), "message should name the arguments, got: ${e.message}")
    }

    @Test fun `an external function may not be an interrupt handler`() {
        assertFailsWith<SemaException> { check("interrupt external fun handler()") }
    }
}
