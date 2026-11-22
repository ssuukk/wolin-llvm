package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The control-flow paths of the compiler, proven on the real 6502 under VICE.
 *
 * Every program parks in `while (true) {}` after writing its result, so the
 * emulator stays alive until the memory is read back.
 */
class ControlFlowTest {
    @Test fun `if else takes the else branch`() {
        val bytes = runProgram("""
            fun main() {
                var x: ubyte = 0
                if (1 > 2) { x = 1 } else { x = 2 }
                var out: ubyte^0x400 = 0
                out = x
                while (true) {}
            }
        """)
        assertEquals(2, bytes[0].toInt() and 0xFF)
    }

    @Test fun `while counts to five`() {
        val bytes = runProgram("""
            fun main() {
                var i: ubyte = 0
                while (i < 5) { i = i + 1 }
                var out: ubyte^0x400 = 0
                out = i
                while (true) {}
            }
        """)
        assertEquals(5, bytes[0].toInt() and 0xFF)
    }

    @Test fun `do while runs its body once`() {
        val bytes = runProgram("""
            fun main() {
                var i: ubyte = 0
                do { i = i + 1 } while (i < 5)
                var out0: ubyte^0x400 = 0
                out0 = i
                // The condition is false on entry, so the body runs once before
                // it is tested: a `while` loop would leave j at 5.
                var j: ubyte = 5
                do { j = j + 1 } while (j < 5)
                var out1: ubyte^0x401 = 0
                out1 = j
                while (true) {}
            }
        """)
        assertEquals(5, bytes[0].toInt() and 0xFF)
        assertEquals(6, bytes[1].toInt() and 0xFF)
    }

    @Test fun `break leaves the loop early`() {
        val bytes = runProgram("""
            fun main() {
                var i: ubyte = 0
                while (true) {
                    i = i + 1
                    if (i == 3) { break }
                }
                var out: ubyte^0x400 = 0
                out = i
                while (true) {}
            }
        """)
        assertEquals(3, bytes[0].toInt() and 0xFF)
    }

    @Test fun `continue skips the rest of the body`() {
        val bytes = runProgram("""
            fun main() {
                var i: ubyte = 0
                var n: ubyte = 0
                while (i < 6) {
                    i = i + 1
                    if (i == 3) { continue }
                    n = n + 1
                }
                var out: ubyte^0x400 = 0
                out = n
                while (true) {}
            }
        """)
        assertEquals(5, bytes[0].toInt() and 0xFF)
    }

    @Test fun `when with a subject matches the branch value`() {
        val bytes = runProgram("""
            fun main() {
                var x: ubyte = 2
                var r: ubyte = 0
                when (x) {
                    1 -> { r = 10 }
                    2 -> { r = 20 }
                    else -> { r = 30 }
                }
                var out: ubyte^0x400 = 0
                out = r
                while (true) {}
            }
        """)
        assertEquals(20, bytes[0].toInt() and 0xFF)
    }

    @Test fun `when with conditions matches the first true condition`() {
        val bytes = runProgram("""
            fun main() {
                var x: ubyte = 7
                var r: ubyte = 0
                when {
                    x < 5 -> { r = 1 }
                    x < 9 -> { r = 2 }
                    else -> { r = 3 }
                }
                var out: ubyte^0x400 = 0
                out = r
                while (true) {}
            }
        """)
        assertEquals(2, bytes[0].toInt() and 0xFF)
    }

    @Test fun `when as an expression takes the taken branch's value`() {
        val bytes = runProgram("""
            fun main() {
                var x: ubyte = 2
                var r: ubyte = when (x) {
                    1 -> 10
                    2 -> 20
                    else -> 30
                }
                var out: ubyte^0x400 = 0
                out = r
                while (true) {}
            }
        """)
        assertEquals(20, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a local in an if-expression initialiser runs on the 6502`() {
        val bytes = runProgram("""
            fun main() {
                var x = if (true) { var b: byte = -1; b } else { -2 }
                var out: ubyte^0x400 = 0
                out = x.toUbyte()
                while (true) {}
            }
        """)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
    }
}
