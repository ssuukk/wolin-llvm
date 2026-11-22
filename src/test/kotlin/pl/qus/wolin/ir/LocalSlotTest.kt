package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import pl.qus.wolin.driver.CompilerPipeline
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalSlotTest {
    private fun ir(src: String): String =
        CompilerPipeline().compileToIr(src.trimIndent(), "t.wtk")

    private fun allocas(ir: String): Int = Regex("alloca i8").findAll(ir).count()

    @Test fun `a local in an if-expression initialiser gets a slot`() {
        val ir = ir("""
            fun f(): byte {
                var x = if (true) { var b: byte = -1; b } else { -2 }
                return x
            }
        """)
        assertEquals(2, allocas(ir))
        assertTrue(ir.contains("sub i8 0, 1"), ir)
    }

    @Test fun `a local in a statement condition gets a slot`() {
        val ir = ir("""
            fun f(): byte {
                if (if (true) { var a: byte = 1; a == 1 } else { false }) { }
                while (if (false) { var b: byte = 1; b == 1 } else { false }) { }
                return 1
            }
        """)
        assertEquals(2, allocas(ir))
        assertTrue(ir.contains("ret i8 1"), ir)
    }

    @Test fun `a local in a when subject or entry condition gets a slot`() {
        val ir = ir("""
            fun f(): byte {
                when (if (true) { var a: byte = 1; a } else { -1 }) { 1 -> { } }
                when { if (true) { var b: byte = 1; b == 1 } else { false } -> { } }
                return 1
            }
        """)
        assertEquals(2, allocas(ir))
        assertTrue(ir.contains("ret i8 1"), ir)
    }

    @Test fun `a local in an if-expression condition gets a slot`() {
        val ir = ir("""
            fun f(): byte {
                var x = if (if (true) { var c: byte = 1; c == 1 } else { false }) { -1 } else { -2 }
                return x
            }
        """)
        assertEquals(2, allocas(ir))
        assertTrue(ir.contains("sub i8 0, 1"), ir)
    }

    @Test fun `a local in a return branch keeps one slot`() {
        val ir = ir("""
            fun f(): byte {
                return if (true) { var b: byte = 1; b } else { 2 }
            }
        """)
        assertEquals(1, allocas(ir))
    }
}
