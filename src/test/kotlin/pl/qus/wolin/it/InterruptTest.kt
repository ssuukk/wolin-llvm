package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
