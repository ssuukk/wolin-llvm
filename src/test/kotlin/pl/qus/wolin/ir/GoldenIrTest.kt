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
}
