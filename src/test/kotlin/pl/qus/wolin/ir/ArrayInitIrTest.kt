package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.CompilerPipeline
import kotlin.test.assertTrue

class ArrayInitIrTest {

    private fun ir(src: String): String = CompilerPipeline().compileToIr(src, "t.wtk")

    @Test fun `a data-section array from a list spells a constant array`() {
        val out = ir("var a: ubyte[ubyte] = [12, 13]\nfun main() { while (true) {} }")
        assertTrue(out.contains("@\"t.a\" = global [256 x i8] [i8 12, i8 13, i8 0"), out)
    }

    @Test fun `a uword-element array spells an i16 constant array`() {
        val out = ir("var w: uword[uword] = [0, 1024]\nfun main() { while (true) {} }")
        assertTrue(out.contains("@\"t.w\" = global [65536 x i16] [i16 0, i16 1024, i16 0"), out)
    }

    @Test fun `an empty list spells zeroinit`() {
        val out = ir("var a: ubyte[ubyte] = []\nfun main() { while (true) {} }")
        assertTrue(out.contains("@\"t.a\" = global [256 x i8] zeroinit"), out)
    }

    @Test fun `a global fixed-address array emits volatile stores in the init function`() {
        val out = ir("var a: ubyte[ubyte]^0x3000 = [1, 2]\nfun main() { while (true) {} }")
        assertTrue(out.contains("store volatile i8"), out)
        assertTrue(out.contains("inttoptr i64 12288 to i8*"), out)
        assertTrue(out.contains("inttoptr i64 12289 to i8*"), out)
    }

    @Test fun `a local fixed-address array emits volatile stores at function entry`() {
        val src = """
            fun main() {
                var a: ubyte[ubyte]^0x3000 = [1, 2]
                while (true) {}
            }
        """.trimIndent()
        val out = ir(src)
        assertTrue(out.contains("store volatile i8"), out)
        assertTrue(out.contains("inttoptr i64 12288 to i8*"), out)
        assertTrue(out.contains("inttoptr i64 12289 to i8*"), out)
    }

    @Test fun `a string initialiser emits stores for the bytes plus NUL`() {
        val src = "var s: ubyte[ubyte]^0x3000 = \"hi\"\nfun main() { while (true) {} }"
        val out = ir(src)
        assertTrue(out.contains("store volatile i8"), out)
        assertTrue(out.contains("store volatile i8 104"), out)
        assertTrue(out.contains("store volatile i8 105"), out)
        assertTrue(out.contains("store volatile i8 0"), out)
    }
}
