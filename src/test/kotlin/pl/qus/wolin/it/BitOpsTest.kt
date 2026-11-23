package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

class BitOpsTest {
    @Test fun `set bits ORs the mask in`() {
        val bytes = runProgram("""
            fun main() {
                var x: ubyte = 0b00000000
                x := 0b00001111
                var out: ubyte^0x400 = 0
                out = x
                while (true) {}
            }
        """)
        assertEquals(0x0F, bytes[0].toInt() and 0xFF)
    }

    @Test fun `clear bits ANDs the complement in`() {
        val bytes = runProgram("""
            fun main() {
                var x: ubyte = 0b11111111
                x .= 0b00001111
                var out: ubyte^0x400 = 0
                out = x
                while (true) {}
            }
        """)
        assertEquals(0xF0, bytes[0].toInt() and 0xFF)
    }

    @Test fun `and-assign and or-assign are bitwise`() {
        val bytes = runProgram("""
            fun main() {
                var x: ubyte = 0b11110000
                x &= 0b00111100
                var y: ubyte = 0b11110000
                y |= 0b00001111
                var out0: ubyte^0x400 = 0
                var out1: ubyte^0x401 = 0
                out0 = x
                out1 = y
                while (true) {}
            }
        """)
        assertEquals(0x30, bytes[0].toInt() and 0xFF)   // x &= 0b00111100
        assertEquals(0xFF, bytes[1].toInt() and 0xFF)   // y |= 0b00001111
    }

    @Test fun `xor of two bytes runs on the real 6502`() {
        val bytes = runProgram("""
            fun main() {
                var a: ubyte = 0x0F
                var b: ubyte = 0xF0
                var out: ubyte^0x400 = 0
                out = a xor b
                while (true) {}
            }
        """)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a literal in xor takes the operand type`() {
        val bytes = runProgram("""
            fun main() {
                var a: uword = 0x0F0F
                var out: uword^0x400 = 0
                out = a xor 0xFFFF
                while (true) {}
            }
        """)
        assertEquals(0xF0, bytes[0].toInt() and 0xFF)
        assertEquals(0xF0, bytes[1].toInt() and 0xFF)
    }

    @Test fun `a bitmask literal clears the high byte of a word`() {
        val bytes = runProgram("""
            fun main() {
                var w: word = 0x1234
                var out: word^0x400 = 0
                out = w & 0xFF00
                while (true) {}
            }
        """)
        assertEquals(0x00, bytes[0].toInt() and 0xFF)
        assertEquals(0x12, bytes[1].toInt() and 0xFF)
    }

    @Test fun `a narrower bit-assignment mask is extended`() {
        val bytes = runProgram("""
            fun main() {
                var x: uword = 0x1200
                var m: ubyte = 0x0F
                x |= m
                var y: word = 0
                var s: byte = -1
                y := s
                var outX: uword^0x400 = 0
                var outY: word^0x402 = 0
                outX = x
                outY = y
                while (true) {}
            }
        """)
        assertEquals(0x0F, bytes[0].toInt() and 0xFF)
        assertEquals(0x12, bytes[1].toInt() and 0xFF)
        assertEquals(0xFF, bytes[2].toInt() and 0xFF)
        assertEquals(0xFF, bytes[3].toInt() and 0xFF)
    }

    @Test fun `a wider bit-assignment mask is truncated`() {
        val bytes = runProgram("""
            fun main() {
                var x: ubyte = 0
                var m: uword = 0x12FF
                x := m
                var y: ubyte = 0xF0
                var w: word = -1
                y &= w
                var out0: ubyte^0x400 = 0
                var out1: ubyte^0x401 = 0
                out0 = x
                out1 = y
                while (true) {}
            }
        """)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xF0, bytes[1].toInt() and 0xFF)
    }

    @Test fun `xor precedence sits below the additive level`() {
        // a | b xor c & d must group as (a | b) xor (c & d) = 0x00.
        // The plausible wrong grouping a | (b xor (c & d)) yields 0x11.
        val bytes = runProgram("""
            fun main() {
                var a: ubyte = 0x11
                var b: ubyte = 0x11
                var c: ubyte = 0x11
                var d: ubyte = 0x11
                var out: ubyte^0x400 = 0
                out = a | b xor c & d
                while (true) {}
            }
        """)
        assertEquals(0x00, bytes[0].toInt() and 0xFF)
    }

    @Test fun `bitwise operators work inside parentheses`() {
        val bytes = runProgram("""
            fun main() {
                var a: ubyte = 0xFF
                var b: ubyte = 0x0F
                var r: ubyte = (a & b)
                var out: ubyte^0x400 = 0
                out = r
                while (true) {}
            }
        """)
        assertEquals(0x0F, bytes[0].toInt() and 0xFF)
    }
}
