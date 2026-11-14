package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Array access on the real 6502: a fixed-address byte array stores an
 * element under an index and reads it back.
 */
class ArrayTest {
    @Test fun `a ubyte array at a fixed address stores and reads back`() {
        val bytes = runProgram("""
            fun main() {
                var arr: ubyte[ubyte]^0x3000
                arr[3] = 42
                var out: ubyte^0x400 = 0
                out = arr[3]
                while (true) {}
            }
        """)
        assertEquals(42, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a uword index array reads back`() {
        val bytes = runProgram("""
            fun main() {
                var arr: ubyte[uword]^0x3000
                var i: uword = 5
                arr[i] = 7
                var out: ubyte^0x400 = 0
                out = arr[i]
                while (true) {}
            }
        """)
        assertEquals(7, bytes[0].toInt() and 0xFF)
    }
}
