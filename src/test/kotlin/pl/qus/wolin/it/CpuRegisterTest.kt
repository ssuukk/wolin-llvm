package pl.qus.wolin.it

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fixed-address function that returns a value: the routine puts the
 * result in a register, and the declaration says which one.
 *
 * The result is read inside the wrapper, right after the ROM call, so it
 * cannot be lost: the compiler owns the registers, and it may use them for
 * its own bookkeeping between the call and the next statement.
 *
 * A pair name lists the registers in low-to-high byte order: `CPU.AX`
 * reads A as the low byte and X as the high byte; `CPU.XY` reads X as the
 * low byte and Y as the high byte.
 */
class CpuRegisterTest {

    @Test fun `a fixed address function with a return type loads the register`() {
        val ir = emitIr("""
            fun readst^0xffb7(lfn: ubyte^CPU.A, channel: ubyte^CPU.Y, dev: ubyte^CPU.X): ubyte^CPU.A

            fun main() {
                val status = readst(0, 0, 8)
                while (true) {}
            }
        """)
        assertTrue(ir.contains("define i8 @\"t.readst\""), "the function must return a byte:\n$ir")
        assertTrue(ir.contains("\"=a\"()"), "the result must be read from A:\n$ir")
        assertTrue(ir.contains("ret i8"), "the function must return the value:\n$ir")
    }

    @Test fun `a fixed address function returns the value from its register`() {
        val bytes = runProgram("""
            fun setLfs^0xffba(lfn: ubyte^CPU.A, channel: ubyte^CPU.Y, dev: ubyte^CPU.X)
            fun readst^0xffb7(lfn: ubyte^CPU.A, channel: ubyte^CPU.Y, dev: ubyte^CPU.X): ubyte^CPU.A

            fun main() {
                setLfs(5, 9, 8)
                val status = readst(0, 0, 8)
                var out: ubyte^0x400
                out = status
                while (true) {}
            }
        """)
        // The kernal reports 64 ($40) for the LFS status of device 8,
        // which has no disk attached under VICE's -default.
        assertEquals(64, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a routine returns its value in one register`() {
        val bytes = runProgram("""
            fun plot^0xfff0(x: ubyte^CPU.X, y: ubyte^CPU.Y): ubyte^CPU.X

            fun main() {
                val column = plot(10, 20)
                var out: ubyte^0x400
                out = column
                while (true) {}
            }
        """)
        // PLOT leaves the column in X.
        assertEquals(10, bytes[0].toInt() and 0xFF)
    }

    @Test fun `an AX pair return reads A as the low byte and X as the high byte`() {
        val ir = emitIr("""
            fun rdtim^0xffde(): uword^CPU.AX

            fun main() {
                val time = rdtim()
                while (true) {}
            }
        """)
        assertTrue(ir.contains("define i16 @\"t.rdtim\""), "the function must return a word:\n$ir")
        assertTrue(ir.contains("\"=a\"()"), "A must be read (low byte):\n$ir")
        assertTrue(ir.contains("\"=x\"()"), "X must be read (high byte):\n$ir")
        assertTrue(ir.contains("zext i8"), "the bytes must be widened:\n$ir")
        assertTrue(ir.contains("shl i16"), "the high byte must be shifted:\n$ir")
        assertTrue(ir.contains("ret i16"), "the function must return the value:\n$ir")
    }

    @Test fun `a routine returns a value in the XY pair`() {
        val bytes = runProgram("""
            fun screen^0xffed(): uword^CPU.XY

            fun main() {
                val size = screen()
                var out: uword^0x400
                out = size
                while (true) {}
            }
        """)
        // SCREEN leaves the columns in X and the rows in Y: 40 and 25 on a
        // PAL C64. X is the low byte of the pair, Y the high byte.
        assertEquals(40, bytes[0].toInt() and 0xFF)
        assertEquals(25, bytes[1].toInt() and 0xFF)
    }

    @Test fun `a routine returns a 16-bit value in the XY pair`() {
        val bytes = runProgram("""
            fun membot^0xff9c(): uword^CPU.XY

            fun main() {
                val bottom = membot()
                var out: uword^0x400
                out = bottom
                while (true) {}
            }
        """)
        // MEMBOT leaves the bottom of memory in X (low) and Y (high):
        // $003E on a C64 with no cartridge.
        val low = bytes[0].toInt() and 0xFF
        val high = bytes[1].toInt() and 0xFF
        assertEquals(0x3E, low + (high shl 8))
    }
}
