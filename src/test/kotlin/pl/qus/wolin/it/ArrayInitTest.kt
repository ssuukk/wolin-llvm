package pl.qus.wolin.it

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Array initialisers on the real 6502. The program initialises a fixed-address
 * array from a list and from a string, then copies the first bytes to
 * $0400..$0403 which `runProgram` reads back.
 */
class ArrayInitTest {

    @Test fun `a fixed-address array from a list`() {
        val bytes = runProgram(
            """
            var pattern: ubyte[ubyte]^0x3000 = [1, 2, 3, 4]

            fun main() {
                var out: ubyte[ubyte]^0x0400
                out[0] = pattern[0]
                out[1] = pattern[1]
                out[2] = pattern[2]
                out[3] = pattern[3]
                while (true) {}
            }
            """
        )
        assertEquals(1, bytes[0].toInt() and 0xFF)
        assertEquals(2, bytes[1].toInt() and 0xFF)
        assertEquals(3, bytes[2].toInt() and 0xFF)
        assertEquals(4, bytes[3].toInt() and 0xFF)
    }

    @Test fun `a fixed-address array from a string`() {
        val bytes = runProgram(
            """
            var greeting: ubyte[ubyte]^0x3000 = "HI"

            fun main() {
                var out: ubyte[ubyte]^0x0400
                out[0] = greeting[0]
                out[1] = greeting[1]
                out[2] = greeting[2]
                while (true) {}
            }
            """
        )
        assertEquals(0xC8, bytes[0].toInt() and 0xFF)
        assertEquals(0xC9, bytes[1].toInt() and 0xFF)
        assertEquals(0, bytes[2].toInt() and 0xFF)
    }
}
