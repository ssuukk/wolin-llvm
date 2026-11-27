package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.CompilerPipeline
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An `external fun` is a declaration, not a definition: the module emits
 * `declare` for it, so the symbol is resolved by whatever is linked into
 * the program — a Rust archive, for instance.
 */
class ExternalFunctionIrTest {
    private fun ir(src: String): String =
        CompilerPipeline().compileToIr(src.trimIndent(), "t.wtk")

    @Test fun `an external function is declared, not defined`() {
        val ir = ir("external fun add(a: uword, b: uword): uword")
        assertTrue(ir.contains("declare i16 @\"t.add\""), ir)
        assertFalse(ir.contains("define i16 @\"t.add\""), ir)
    }

    @Test fun `an external function with no return type is declared void`() {
        val ir = ir("external fun poke(p: ubyte^0xd020, v: ubyte)")
        assertTrue(ir.contains("declare void @\"t.poke\""), ir)
    }

    @Test fun `an external function's parameters are declared`() {
        val ir = ir("external fun add(a: uword, b: uword): uword")
        assertTrue(ir.contains("i16 %a, i16 %b"), ir)
    }

    @Test fun `an ordinary function is still defined`() {
        val ir = ir("fun add(a: uword, b: uword): uword { return a + b }")
        assertTrue(ir.contains("define i16 @\"t.add\""), ir)
    }

    @Test fun `a call to an external function is an ordinary call`() {
        val ir = ir("""
            external fun add(a: uword, b: uword): uword
            fun main() {
                var x: uword = add(1, 2)
                var out: ubyte^0x800 = x.toUbyte()
                while (true) {}
            }
        """)
        assertTrue(ir.contains("call i16 @\"t.add\""), ir)
    }
}
