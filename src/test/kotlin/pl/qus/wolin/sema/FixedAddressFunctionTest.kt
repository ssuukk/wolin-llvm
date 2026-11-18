package pl.qus.wolin.sema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.qus.wolin.ast.Ast
import pl.qus.wolin.driver.Diagnostics
import pl.qus.wolin.parser.ParserFacade
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checker rules for fixed-address functions (kernal calls): the
 * parameters must be single CPU registers, a return type must name the
 * register it comes from, and only `ubyte` (one register) or `uword`
 * (a register pair) may be returned.
 */
class FixedAddressFunctionTest {

    private fun typeChecker(src: String): Ast.Program =
        TypeChecker().check(AstBuilder().build(ParserFacade(Diagnostics()).parse(src, "t.ktk")!!))

    @Test fun `a return type without a register is rejected`() {
        val e = assertThrows<SemaException> {
            typeChecker("fun readst^0xffb7(lfn: ubyte^CPU.A): ubyte")
        }
        assertEquals("Fixed address function readst needs a CPU register return type", e.message)
    }

    @Test fun `a byte return is rejected`() {
        assertThrows<SemaException> {
            typeChecker("fun f^0xff00(a: ubyte^CPU.A): byte^CPU.A")
        }
    }

    @Test fun `a uword return needs a register pair`() {
        val e = assertThrows<SemaException> {
            typeChecker("fun f^0xff00(a: ubyte^CPU.A): uword^CPU.A")
        }
        assertEquals("Fixed address function f returns a register pair, not CPU.A", e.message)
    }

    @Test fun `a ubyte return needs one register`() {
        val e = assertThrows<SemaException> {
            typeChecker("fun f^0xff00(a: ubyte^CPU.A): ubyte^CPU.AX")
        }
        assertEquals("Fixed address function f returns one register, not CPU.AX", e.message)
    }

    @Test fun `a return from a flag is rejected`() {
        assertThrows<SemaException> {
            typeChecker("fun f^0xff00(a: ubyte^CPU.A): ubyte^CPU.C")
        }
    }

    @Test fun `a parameter without a register is rejected`() {
        val e = assertThrows<SemaException> {
            typeChecker("fun f^0xff00(a: ubyte)")
        }
        assertEquals("Fixed address function f needs a CPU register parameter", e.message)
    }

    @Test fun `a pair parameter is rejected`() {
        val e = assertThrows<SemaException> {
            typeChecker("fun setnam^0xffbd(len: ubyte^CPU.A, name: word^CPU.XY)")
        }
        assertEquals("Fixed address function setnam needs a single register parameter, not CPU.XY", e.message)
    }

    @Test fun `a flag parameter is rejected`() {
        assertThrows<SemaException> {
            typeChecker("fun f^0xff00(a: ubyte^CPU.C)")
        }
    }

    @Test fun `a CPU register local variable is rejected`() {
        val e = assertThrows<SemaException> {
            typeChecker("fun f() { var x: ubyte^CPU.X = 1 }")
        }
        assertTrue(e.message!!.startsWith("CPU register variable x is not supported yet"))
    }

    @Test fun `a CPU register global variable is rejected`() {
        val e = assertThrows<SemaException> {
            typeChecker("var x: ubyte^CPU.X = 1")
        }
        assertTrue(e.message!!.startsWith("CPU register variable x is not supported yet"))
    }

    @Test fun `a valid return type is accepted`() {
        typeChecker("fun readst^0xffb7(lfn: ubyte^CPU.A, ch: ubyte^CPU.Y, dev: ubyte^CPU.X): ubyte^CPU.A")
        typeChecker("fun membot^0xff9c(): uword^CPU.XY")
        typeChecker("fun f^0xff00(a: ubyte^CPU.A): uword^CPU.AX")
    }
}
