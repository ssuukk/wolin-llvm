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

    @Test fun `a byte converts to a word with sign extension`() {
        val bytes = runProgram("""
            fun main() {
                var b: byte = -2
                var w: word = b.toWord()
                var r: word = w + 1
                var ok: bool = r + 1 == 0
                var out: ubyte^0x400 = 0
                out = if (ok) 1 else 0
                while (true) {}
            }
        """)
        assertEquals(1, bytes[0].toInt() and 0xFF)
    }

    @Test fun `sign extension fills the high byte`() {
        val bytes = runProgram("""
            fun main() {
                var b: byte = -1
                var w: word = b.toWord()
                var hi: ubyte = ((w & -256) / 256).toUbyte()
                var out: ubyte^0x400 = 0
                out = hi
                while (true) {}
            }
        """)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a converted global initialiser runs on the real 6502`() {
        val bytes = runProgram("""
            var b: byte = -1
            var w: word = b.toWord()
            fun main() {
                var r: word = w + 1
                var ok: bool = r == 0
                var out: ubyte^0x400 = 0
                out = if (ok) 1 else 0
                while (true) {}
            }
        """)
        assertEquals(1, bytes[0].toInt() and 0xFF)
    }
}
