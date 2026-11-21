package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.CompilerPipeline
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import pl.qus.wolin.sema.AstBuilder
import pl.qus.wolin.sema.SemaException
import pl.qus.wolin.sema.TypeChecker
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A variable pinned to a CPU status flag (`var m: bool^CPU.I`).
 *
 * The flag is a single bit of P, so the compiler owns it: a write lowers to
 * `sei`/`cli` and a read reads the bit. A write to a full register
 * (`CPU.A`, `CPU.X`) still clobbers the compiler's own accumulator, so it
 * stays rejected.
 */
class CpuFlagVarTest {

    private fun ir(src: String): String = CompilerPipeline().compileToIr(src, "t.wtk")

    private fun typeCheck(src: String) =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    @Test fun `a bool CPU I global is accepted`() {
        typeCheck("var maskInterrupts: bool^CPU.I")
    }

    @Test fun `a bool CPU I local is accepted`() {
        typeCheck("fun f() { var m: bool^CPU.I }")
    }

    @Test fun `writing true lowers to sei`() {
        val ir = ir(
            """
            fun f() {
                var m: bool^CPU.I
                m = true
            }
            """.trimIndent()
        )
        assertTrue(ir.contains("\"sei\""), ir)
        assertFalse(ir.contains("\"cli\""), ir)
    }

    @Test fun `writing false lowers to cli`() {
        val ir = ir(
            """
            fun f() {
                var m: bool^CPU.I
                m = false
            }
            """.trimIndent()
        )
        assertTrue(ir.contains("\"cli\""), ir)
        assertFalse(ir.contains("\"sei\""), ir)
    }

    @Test fun `a global flag variable has no storage`() {
        val ir = ir(
            """
            var maskInterrupts: bool^CPU.I
            fun f() {
                maskInterrupts = true
            }
            """.trimIndent()
        )
        assertFalse(ir.contains("@maskInterrupts"), "a flag variable has no storage:\n$ir")
        assertTrue(ir.contains("\"sei\""), ir)
    }

    @Test fun `reading the flag yields a comparison`() {
        val ir = ir(
            """
            fun f(): bool {
                var m: bool^CPU.I
                return m
            }
            """.trimIndent()
        )
        assertTrue(ir.contains("icmp ne i8"), ir)
    }

    @Test fun `a non-bool flag variable is rejected`() {
        val e = assertFailsWith<SemaException> {
            typeCheck("var m: ubyte^CPU.I")
        }
        assertTrue(e.message!!.startsWith("CPU register variable m is not supported yet"), e.message)
    }

    @Test fun `a full register variable is still rejected`() {
        val e = assertFailsWith<SemaException> {
            typeCheck("var x: ubyte^CPU.X = 1")
        }
        assertTrue(e.message!!.startsWith("CPU register variable x is not supported yet"), e.message)
    }

    @Test fun `a sema error carries the line of the declaration`() {
        val e = assertFailsWith<SemaException> {
            typeCheck(
                """
                var ok: ubyte

                var bad: ubyte^CPU.X = 1
                """.trimIndent()
            )
        }
        assertEquals(3, e.line)
    }

    @Test fun `a bool CPU C global is accepted`() {
        typeCheck("var carry: bool^CPU.C")
    }

    @Test fun `a bool CPU C local is accepted`() {
        typeCheck("fun f() { var carry: bool^CPU.C }")
    }

    @Test fun `writing carry true lowers to sec`() {
        val ir = ir(
            """
            fun f() {
                var carry: bool^CPU.C
                carry = true
            }
            """.trimIndent()
        )
        assertTrue(ir.contains("\"sec\""), ir)
        assertFalse(ir.contains("\"clc\""), ir)
    }

    @Test fun `writing carry false lowers to clc`() {
        val ir = ir(
            """
            fun f() {
                var carry: bool^CPU.C
                carry = false
            }
            """.trimIndent()
        )
        assertTrue(ir.contains("\"clc\""), ir)
        assertFalse(ir.contains("\"sec\""), ir)
    }

    @Test fun `a global CPU C variable has no storage`() {
        val ir = ir(
            """
            var carry: bool^CPU.C
            fun f() {
                carry = true
            }
            """.trimIndent()
        )
        assertFalse(ir.contains("@carry"), "a flag variable has no storage:\n$ir")
        assertTrue(ir.contains("\"sec\""), ir)
    }

    @Test fun `reading the carry flag yields a comparison`() {
        val ir = ir(
            """
            fun f(): bool {
                var carry: bool^CPU.C
                return carry
            }
            """.trimIndent()
        )
        assertTrue(ir.contains("icmp ne i8"), ir)
    }

    @Test fun `a bool CPU N variable is rejected`() {
        assertFailsWith<SemaException> { typeCheck("var n: bool^CPU.N") }
    }

    @Test fun `a bool CPU Z variable is rejected`() {
        assertFailsWith<SemaException> { typeCheck("var z: bool^CPU.Z") }
    }
}
