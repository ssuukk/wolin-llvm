package pl.qus.wolin.it

import org.junit.jupiter.api.Test
import pl.qus.wolin.test.ViceRunner
import kotlin.test.assertEquals

/**
 * The first end-to-end program: Wolin source → LLVM IR → `.prg` → the real
 * 6502 under VICE → the memory read back over the binary monitor.
 */
class EndToEndTest {

    @Test fun `ubyte addition runs on the real 6502`() {
        val src = """
            fun main() {
                var a: ubyte = 40
                var b: ubyte = 2
                var r: ubyte = a + b
                var out: ubyte^0x400 = r
                out = r
                while (true) {}
            }
        """.trimIndent()
        val prg = compileToPrg(src, "add_ubyte")
        val bytes = ViceRunner().run(prg, 0x0400, 0x0401)
        assertEquals(42, bytes[0].toInt() and 0xFF)
    }
}
