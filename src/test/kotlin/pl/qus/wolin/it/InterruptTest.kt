package pl.qus.wolin.it

import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import pl.qus.wolin.sema.AstBuilder
import pl.qus.wolin.sema.SemaException
import pl.qus.wolin.sema.TypeChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An interrupt handler is emitted with `attributes #0 = { "interrupt" }`,
 * which makes the backend emit the register-save prologue and the `rti`.
 *
 * The compiler must NOT emit its own A/X/Y save/restore: the backend already
 * does it, and a second one would double the stack traffic.
 */
class InterruptTest {
    @Test fun `the interrupt handler is emitted with the interrupt attribute`() {
        val ir = emitIr("""
            interrupt fun handler() {
                var marker: ubyte^0x400 = 0
                marker = 99
            }

            fun main() {
                var vector: uword^0x314 = 0
                vector = handler
                while (true) {}
            }
        """)
        assertTrue(
            ir.contains("attributes #0 = { \"interrupt\" }"),
            "the handler must carry the interrupt attribute:\n$ir",
        )
    }

    @Test fun `the handler is the function the attribute group names`() {
        val ir = emitIr("""
            interrupt fun handler() {
                var marker: ubyte^0x400 = 0
                marker = 99
            }

            fun main() {
                while (true) {}
            }
        """)
        // The attribute group must belong to the handler, not to main.
        val handlerAt = ir.indexOf("handler")
        val attributeAt = ir.indexOf("attributes #0")
        assertTrue(handlerAt >= 0, "the handler must be emitted:\n$ir")
        assertTrue(attributeAt > handlerAt, "the attribute group must follow the handler:\n$ir")
        val mainAt = ir.indexOf("main")
        assertTrue(
            attributeAt < mainAt,
            "the attribute group must not belong to main:\n$ir",
        )
    }

    @Test fun `the handler runs and writes to RAM`() {
        val bytes = runProgram("""
            interrupt fun handler() {
                var marker: ubyte^0x400 = 0
                marker = 99
            }

            fun main() {
                var vector: uword^0x314 = 0
                vector = handler
                while (true) {}
            }
        """)
        assertEquals(99, bytes[0].toInt() and 0xFF)
    }

    /**
     * An `interrupt fun` cannot take arguments: the KERNAL's IRQ entry pushes
     * nothing for a handler to read.
     */
    @Test fun `an interrupt function with arguments is rejected`() {
        val e = assertFailsWith<SemaException> {
            compile("""
                interrupt fun handler(a: ubyte) {
                }
                fun main() { }
            """)
        }
        assertTrue(
            e.message!!.contains("Arguments not allowed for interrupt function!"),
            "message should name the arguments restriction, got: ${e.message}",
        )
    }

    /**
     * An `interrupt fun` cannot return a value: the exit is `rti`, which has
     * no register to hand a result back in.
     */
    @Test fun `an interrupt function with a return type is rejected`() {
        val e = assertFailsWith<SemaException> {
            compile("""
                interrupt fun handler(): ubyte {
                    return 1
                }
                fun main() { }
            """)
        }
        assertTrue(
            e.message!!.contains("Return not allowed for interrupt function!"),
            "message should name the return restriction, got: ${e.message}",
        )
    }

    private fun compile(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))
}
