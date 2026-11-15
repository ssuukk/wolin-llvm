package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

class SignedByteTest {
    @Test fun `byte -128 is accepted and arithmetic wraps`() {
        val bytes = runProgram("""
            fun main() {
                var a: byte = -128
                var b: byte = a + 1
                var r: bool = b == -127
                var out: ubyte^0x400 = 0
                out = if (r) 1 else 0
                while (true) {}
            }
        """)
        assertEquals(1, bytes[0].toInt() and 0xFF)
    }

    @Test fun `byte division and remainder are signed`() {
        val bytes = runProgram("""
            fun main() {
                var a: byte = -7
                var d: byte = a / 2
                var m: byte = a % 2
                var r: bool = d == -3 && m == -1
                var out: ubyte^0x400 = 0
                out = if (r) 1 else 0
                while (true) {}
            }
        """)
        assertEquals(1, bytes[0].toInt() and 0xFF)
    }

    @Test fun `byte comparison at the boundary is signed`() {
        val bytes = runProgram("""
            fun main() {
                var a: byte = -128
                var b: byte = 127
                var r: bool = a < b
                var out: ubyte^0x400 = 0
                out = if (r) 1 else 0
                while (true) {}
            }
        """)
        assertEquals(1, bytes[0].toInt() and 0xFF)
    }
}
