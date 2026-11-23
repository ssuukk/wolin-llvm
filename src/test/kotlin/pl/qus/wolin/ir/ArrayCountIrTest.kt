package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.CompilerPipeline
import kotlin.test.assertTrue

/** Explicit array counts control the emitted array type. */
class ArrayCountIrTest {

    private fun ir(src: String): String = CompilerPipeline().compileToIr(src, "t.wtk")

    @Test fun `an explicit count spells a shorter constant array`() {
        val out = ir("var a: ubyte[4] = [1, 2]\nfun main() { while (true) {} }")
        assertTrue(out.contains("@\"t.a\" = global [4 x i8] [i8 1, i8 2, i8 0, i8 0]"), out)
    }

    @Test fun `an explicit count with an index type spells that type`() {
        val out = ir("var a: uword[uword, 2] = [0, 1024]\nfun main() { while (true) {} }")
        assertTrue(out.contains("@\"t.a\" = global [2 x i16] [i16 0, i16 1024]"), out)
    }

    @Test fun `an old domain-sized array keeps its full domain`() {
        val out = ir("var a: ubyte[ubyte] = [1]\nfun main() { while (true) {} }")
        assertTrue(out.contains("@\"t.a\" = global [256 x i8] [i8 1, i8 0"), out)
    }
}
