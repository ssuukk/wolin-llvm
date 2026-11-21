package pl.qus.wolin.it

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A variable pinned to the CPU interrupt flag (`var m: bool^CPU.I`).
 *
 * A write lowers to `sei`/`cli`, a read to a bit test of P. The test runs
 * on real 6502 under VICE: the program sets the flag, reads it back and
 * writes the result to RAM, so the round trip proves the lowering.
 */
class CpuFlagVarTest {

    @Test fun `the flag variable reads back what it wrote`() {
        val bytes = runProgram(
            """
            var maskInterrupts: bool^CPU.I

            fun main() {
                var out: ubyte^0x400

                maskInterrupts = true
                out = 1
                if (maskInterrupts) {
                    out = 2
                }

                maskInterrupts = false
                if (!maskInterrupts) {
                    out = 3
                }

                while (true) {}
            }
            """.trimIndent()
        )
        // `out` was 2 after the flag was set and 3 after it was cleared.
        assertEquals(3, bytes[0].toInt() and 0xFF)
    }

    @Test fun `the flag lowering emits sei and cli`() {
        val ir = emitIr(
            """
            fun main() {
                var m: bool^CPU.I
                m = true
                m = false
                while (true) {}
            }
            """.trimIndent()
        )
        assertTrue(ir.contains("\"sei\""), "setting the flag must emit sei:\n$ir")
        assertTrue(ir.contains("\"cli\""), "clearing the flag must emit cli:\n$ir")
    }

    @Test fun `the carry flag variable reads back what it wrote`() {
        val bytes = runProgram(
            """
            var carry: bool^CPU.C

            fun main() {
                var out: ubyte^0x400

                carry = true
                out = 1
                if (carry) {
                    out = 2
                }

                carry = false
                if (!carry) {
                    out = 3
                }

                while (true) {}
            }
            """.trimIndent()
        )
        // `out` was 2 after the flag was set and 3 after it was cleared.
        assertEquals(3, bytes[0].toInt() and 0xFF)
    }

    @Test fun `the carry flag lowering emits sec and clc`() {
        val ir = emitIr(
            """
            fun main() {
                var carry: bool^CPU.C
                carry = true
                carry = false
                while (true) {}
            }
            """.trimIndent()
        )
        assertTrue(ir.contains("\"sec\""), "setting the carry must emit sec:\n$ir")
        assertTrue(ir.contains("\"clc\""), "clearing the carry must emit clc:\n$ir")
    }
}
