package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Labeled jumps, proven on the real 6502 under VICE: `break@`, `continue@`,
 * and `return@` to a label of the same function.
 *
 * Every program parks in `while (true) {}` after writing its result, so the
 * emulator stays alive until the memory is read back.
 */
class LabelJumpTest {
    @Test fun `a labelled continue retries a loop`() {
        val bytes = runProgram("""
            fun main() {
                var i: ubyte = 0
                retry@
                if (i < 3) { i = i + 1; continue@retry }
                var out: ubyte^0x400 = 0
                out = i
                while (true) {}
            }
        """)
        assertEquals(3, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a labelled break leaves nested loops`() {
        val bytes = runProgram("""
            fun main() {
                var i: ubyte = 0
                var j: ubyte = 0
                while (i < 10) {
                    i = i + 1
                    j = 0
                    while (j < 10) {
                        j = j + 1
                        if (i == 2) break@out
                    }
                }
                out@
                var out0: ubyte^0x400 = 0
                var out1: ubyte^0x401 = 0
                out0 = i
                out1 = j
                while (true) {}
            }
        """)
        assertEquals(2, bytes[0].toInt() and 0xFF)
        assertEquals(1, bytes[1].toInt() and 0xFF)
    }

    @Test fun `a labelled return jumps to a label in a helper function`() {
        val bytes = runProgram("""
            fun pick(flag: bool): ubyte {
                if (flag) return@skip
                return 7
                skip@
                return 9
            }

            fun main() {
                var out0: ubyte^0x400 = 0
                var out1: ubyte^0x401 = 0
                out0 = pick(true)
                out1 = pick(false)
                while (true) {}
            }
        """)
        assertEquals(9, bytes[0].toInt() and 0xFF)
        assertEquals(7, bytes[1].toInt() and 0xFF)
    }
}
