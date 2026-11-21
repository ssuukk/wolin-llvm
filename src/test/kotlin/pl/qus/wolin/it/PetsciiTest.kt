package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PETSCII: a source string is mapped to PETSCII at compile time, and
 * PETSCII is mapped to a screen code before it goes into screen RAM.
 *
 * The screen code is not the PETSCII byte: the VIC-II indexes the character
 * ROM by the screen code. The KERNAL's CHROUT does the conversion, so a
 * program that writes screen RAM directly has to do it too.
 */
class PetsciiTest {

    /**
     * A box-drawing character survives the compiler: the source character
     * becomes the PETSCII byte, not the low byte of its code point.
     */
    @Test fun `a box-drawing character becomes its PETSCII byte`() {
        val bytes = runProgram("""
            fun main() {
                var s: string = "┌"
                var out: ubyte^0x400 = 0
                out = s[0]
                while (true) {}
            }
        """)
        assertEquals(0xB0, bytes[0].toInt() and 0xFF)
    }

    /**
     * A character outside the Basic Multilingual Plane survives as well: the
     * literal is walked by code point, so the surrogate pair is not split.
     */
    @Test fun `a supplementary character becomes its PETSCII byte`() {
        val bytes = runProgram("""
            fun main() {
                var s: string = "🭰"
                var out: ubyte^0x400 = 0
                out = s[0]
                while (true) {}
            }
        """)
        assertEquals(0xD4, bytes[0].toInt() and 0xFF)
    }

    /**
     * The case is inverted on the way to PETSCII: an uppercase letter goes to
     * the high range, a lowercase letter to the low range.
     */
    @Test fun `the letters are case-inverted`() {
        val bytes = runProgram("""
            fun main() {
                var up: string = "A"
                var lo: string = "a"
                var out0: ubyte^0x400 = 0
                var out1: ubyte^0x401 = 0
                out0 = up[0]
                out1 = lo[0]
                while (true) {}
            }
        """)
        assertEquals(0xC1, bytes[0].toInt() and 0xFF)
        assertEquals(0x41, bytes[1].toInt() and 0xFF)
    }

    /**
     * A string written straight into screen RAM holds screen codes, not
     * PETSCII: the VIC-II indexes the character ROM by the screen code.
     *
     * `wolin.printAt` does the conversion, so a box-drawing character lands
     * on the screen as the corner glyph and not as the raw PETSCII byte.
     */
    @Test fun `printAt writes screen codes, not PETSCII`() {
        val bytes = runStdlibProgram("""
            printAt(0, 0, "┌", 7)
        """)
        // PETSCII $B0 is the top-left corner; its screen code is $70.
        assertEquals(0x70, bytes[0].toInt() and 0xFF)
    }
}
