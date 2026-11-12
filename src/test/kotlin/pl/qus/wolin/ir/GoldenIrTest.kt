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
    @ValueSource(strings = ["add_ubyte", "global_const", "while_counter", "when_subject"])
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
}
