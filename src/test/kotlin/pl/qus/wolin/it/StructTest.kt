package pl.qus.wolin.it

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Structs on the real 6502: packed fields, array strides, and fixed storage. */
class StructTest {

    @Test fun `a global struct field holds its value`() {
        val bytes = runProgram("""
            struct P {
                var x: ubyte
                var y: uword
            }
            var p: P
            fun main() {
                p.y = 4660
                var out: ubyte^0x400 = 0
                out = p.y.toUbyte()
                while (true) {}
            }
        """)
        assertEquals(0x34, bytes[0].toInt() and 0xFF)
    }

    @Test fun `an array of structs stores fields at the element stride`() {
        val bytes = runProgram("""
            struct P {
                var x: ubyte
                var y: ubyte
            }
            var ps: P[ubyte, 4]
            fun main() {
                ps[2].x = 42
                ps[2].y = 7
                var out: ubyte^0x400 = 0
                out = ps[2].x
                while (true) {}
            }
        """)
        assertEquals(42, bytes[0].toInt() and 0xFF)
    }

    @Test fun `an unaligned uword field reads back`() {
        val bytes = runProgram("""
            struct P {
                var a: ubyte
                var b: uword
            }
            var ps: P[ubyte, 4]
            fun main() {
                ps[0].b = 0x1234
                var out: ubyte^0x400 = 0
                out = ps[0].b.toUbyte()
                while (true) {}
            }
        """)
        assertEquals(0x34, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a local struct is writable`() {
        val bytes = runProgram("""
            struct P {
                var x: ubyte
            }
            fun main() {
                var p: P
                p.x = 42
                var out: ubyte^0x400 = 0
                out = p.x
                while (true) {}
            }
        """)
        assertEquals(42, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a fixed-address struct writes its field directly`() {
        val bytes = runProgram("""
            struct P {
                var x: ubyte
            }
            var p: P^0x3000
            fun main() {
                p.x = 42
                var out: ubyte^0x400 = 0
                out = p.x
                while (true) {}
            }
        """)
        assertEquals(42, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a fixed-address array of structs writes element fields`() {
        val bytes = runProgram("""
            struct P {
                var x: ubyte
                var y: ubyte
            }
            var ps: P[ubyte, 4]^0x3000
            fun main() {
                ps[2].y = 42
                var out: ubyte^0x400 = 0
                out = ps[2].y
                while (true) {}
            }
        """)
        assertEquals(42, bytes[0].toInt() and 0xFF)
    }

    @Test fun `field increment and compound assignment reach memory`() {
        val bytes = runProgram("""
            struct P {
                var x: ubyte
                var y: uword
            }
            var p: P
            fun main() {
                p.x = 1
                p.x++
                p.y += 2
                var out: ubyte^0x400 = 0
                out = p.y.toUbyte()
                while (true) {}
            }
        """)
        assertEquals(2, bytes[0].toInt() and 0xFF)
    }
}
