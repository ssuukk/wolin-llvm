package pl.qus.wolin.it

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Simple enums on the real 6502. */
class EnumTest {

    @Test fun `a named enum drives a when`() {
        val bytes = runProgram("""
            enum Status { PENDING = 0, RUNNING = 5, ERROR = 255 }
            fun main() {
                var state: Status = Status.RUNNING
                var out: ubyte^0x400 = 0
                when (state) {
                    Status.PENDING -> out = 1
                    Status.RUNNING -> out = 5
                    Status.ERROR -> out = 255
                }
                while (true) {}
            }
        """)
        assertEquals(5, bytes[0].toInt() and 0xFF)
    }

    @Test fun `anonymous autonumbered entries run`() {
        val bytes = runProgram("""
            enum { A, B, C }
            fun main() {
                var out: ubyte^0x400 = 0
                out = C
                while (true) {}
            }
        """)
        assertEquals(2, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a local variable shadows an anonymous entry`() {
        val bytes = runProgram("""
            enum { RUNNING = 5 }
            fun main() {
                var RUNNING: ubyte = 9
                var out: ubyte^0x400 = 0
                out = RUNNING
                while (true) {}
            }
        """)
        assertEquals(9, bytes[0].toInt() and 0xFF)
    }
}
