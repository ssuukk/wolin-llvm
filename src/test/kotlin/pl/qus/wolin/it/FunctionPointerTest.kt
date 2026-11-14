package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A function's address is a plain 16-bit number. Storing it in a `uword`
 * and calling through that variable is an indirect call: `ptrtoint` →
 * `inttoptr` → `call`.
 */
class FunctionPointerTest {
    @Test fun `a uword holds a function address and calls through it`() {
        val bytes = runProgram("""
            fun add(a: ubyte, b: ubyte): ubyte {
                return a + b
            }

            fun main() {
                var p: uword = add
                var r: ubyte = p(2, 3)
                var out: ubyte^0x400 = 0
                out = r
                while (true) {}
            }
        """)
        assertEquals(5, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a uword global holds a function address and calls through it`() {
        val bytes = runProgram("""
            var p: uword

            fun double(a: ubyte): ubyte {
                return a + a
            }

            fun main() {
                p = double
                var r: ubyte = p(4)
                var out: ubyte^0x400 = 0
                out = r
                while (true) {}
            }
        """)
        assertEquals(8, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a uword function address can be called more than once`() {
        val bytes = runProgram("""
            fun add(a: ubyte, b: ubyte): ubyte {
                return a + b
            }

            fun main() {
                var p: uword = add
                var r: ubyte = p(1, 1)
                r = p(r, 2)
                var out: ubyte^0x400 = 0
                out = r
                while (true) {}
            }
        """)
        assertEquals(4, bytes[0].toInt() and 0xFF)
    }
}
