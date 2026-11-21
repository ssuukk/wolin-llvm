package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * String literals on the real 6502: the literal is NUL-terminated and the
 * escapes are decoded into single bytes.
 *
 * Each asserted value needs its own address: a fixed-address local is a
 * direct memory alias, so two writes to one address overwrite each other.
 */
class StringTest {
    @Test fun `a string literal is NUL terminated`() {
        val bytes = runProgram("""
            fun main() {
                var s: string = "hi"
                var out0: ubyte^0x400 = 0
                var out1: ubyte^0x401 = 0
                var out2: ubyte^0x402 = 0
                out0 = s[0]
                out1 = s[1]
                out2 = s[2]
                while (true) {}
            }
        """)
        assertEquals(0x48, bytes[0].toInt() and 0xFF)
        assertEquals(0x49, bytes[1].toInt() and 0xFF)
        assertEquals(0, bytes[2].toInt() and 0xFF)
    }

    @Test fun `a newline escape is one byte`() {
        val bytes = runProgram("""
            fun main() {
                var s: string = "a\nb"
                var out: ubyte^0x400 = 0
                out = s[1]
                while (true) {}
            }
        """)
        assertEquals(0x0A, bytes[0].toInt() and 0xFF)
    }
}
