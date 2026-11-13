package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

class ComparisonTest {
    @Test fun `ubyte comparison is unsigned`() {
        val bytes = runProgram("""
            fun main() {
                var a: ubyte = 255
                var b: ubyte = 0
                var r: bool = a > b
                var out: ubyte^0x400 = 0
                out = if (r) 1 else 0
                while (true) {}
            }
        """)
        assertEquals(1, bytes[0].toInt() and 0xFF)
    }

    @Test fun `byte comparison is signed`() {
        val bytes = runProgram("""
            fun main() {
                var a: byte = -1
                var b: byte = 0
                var r: bool = a > b
                var out: ubyte^0x400 = 0
                out = if (r) 1 else 0
                while (true) {}
            }
        """)
        assertEquals(0, bytes[0].toInt() and 0xFF)
    }
}
