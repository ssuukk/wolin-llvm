package pl.qus.wolin.ir

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pl.qus.wolin.driver.CompilerPipeline
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoldenIrTest {
    @ParameterizedTest
    @ValueSource(strings = ["add_ubyte", "global_const", "while_counter", "when_subject", "prefix_postfix"])
    fun emitsExpectedIr(name: String) {
        val src = javaClass.getResource("/golden/$name.wtk")!!.readText()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        val expected = javaClass.getResource("/golden/$name.ll")!!.readText()
        assertEquals(expected.trim(), ir.trim())
    }

    @Test fun `a fixed address variable emits no storage`() {
        val src = javaClass.getResource("/golden/fixed_address_var.wtk")!!.readText()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertFalse(ir.contains("@border"), "fixed-address vars must not allocate storage:\n$ir")
        assertTrue(ir.contains("inttoptr (i64 53280 to i8*)"), ir)
    }

    @Test fun `a local fixed address aliases the memory`() {
        val src = """
            fun f(): ubyte {
                var out: ubyte^0x400 = 5
                out = 6
                var r: ubyte = out
                return r
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("inttoptr i64 1024 to i8*"), ir)
        assertTrue(ir.contains("store volatile i8"), ir)
        assertEquals(1, Regex("alloca").findAll(ir).count(), "only r takes a stack slot:\n$ir")
    }

    @Test fun `an else-only when followed by a statement compiles to valid IR`() {
        val src = """
            fun f(): ubyte {
                var r: ubyte = 0
                when {
                    else -> { r = 1 }
                }
                r += 1
                return r
            }

            fun g() {
                var r: ubyte = 0
                when {
                    else -> { r = 1 }
                }
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        // the statement after the first when must have been emitted into a live block
        assertTrue(ir.contains("add i8"), "statement after the else-only when is missing:\n$ir")
        // every branch target must name a block that actually exists
        val defined = Regex("^(\\s*)([\\w.]+):\\s*$", RegexOption.MULTILINE)
            .findAll(ir).map { it.groupValues[2] }.toSet()
        val targets = Regex("label %([\\w.]+)").findAll(ir).map { it.groupValues[1] }.toSet()
        val missing = targets - defined
        assertTrue(missing.isEmpty(), "branches to undefined blocks: $missing\n$ir")
    }

    @Test fun `xor emits a bitwise xor instruction`() {
        val src = """
            fun f(): ubyte {
                var a: ubyte = 0x0F
                var b: ubyte = 0xF0
                var c = a xor b
                return c
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("xor i8"), ir)
    }

    @Test fun `conversions emit the right extension instruction`() {
        val src = """
            fun f(): uword {
                var b: byte = -1
                var u: ubyte = 255
                var w: word = 300
                var x = b.toWord()
                var y = u.toUword()
                var z = w.toByte()
                return x.toUword() + y - z.toUbyte().toUword()
            }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("sext i8"), "sign extension missing:\n$ir")
        assertTrue(ir.contains("zext i8"), "zero extension missing:\n$ir")
        assertTrue(ir.contains("trunc i16"), "truncation missing:\n$ir")
    }

    @Test fun `a converted global initialiser folds to a constant`() {
        val src = """
            var b: byte = -1
            var w: word = b.toWord()
            fun main() { while (true) {} }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("i16 -1"), "the folded global must be the sign extension:\n$ir")
    }

    @Test fun `a chained conversion folds to a constant`() {
        val src = """
            var b: byte = -1
            var u: uword = b.toUbyte().toUword()
            fun main() { while (true) {} }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("i16 255"), "the folded chain must be the zero extension:\n$ir")
    }

    @Test fun `a narrowing conversion folds to a constant`() {
        val src = """
            var w: word = 300
            var b: byte = w.toByte()
            fun main() { while (true) {} }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("i8 44"), "the folded narrowing must truncate:\n$ir")
    }

    @Test fun `a conversion around an if-expression keeps the branches' locals`() {
        val src = """
            fun f(c: bool): word {
                return (if (c) { var b: byte = -1; b } else { var d: byte = 0; d }).toWord()
            }
            fun main() { while (true) {} }
        """.trimIndent()
        val ir = CompilerPipeline().compileToIr(src, "t.wtk")
        assertTrue(ir.contains("sext i8"), "the conversion must sign-extend:\n$ir")
    }
}
