package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fixed-address function is a declaration: the address already belongs to
 * something else — a KERNAL routine, for instance. The compiler emits the
 * ROM call as the function's body, so a call site stays an ordinary call.
 *
 * `setLfs` (KERNAL $FFBA) is the ROM routine used here. It pins its three
 * arguments to A, Y and X in declaration order and writes the device number
 * (the X argument) to $BA. That write is directly observable and needs no
 * screen editor, so it is a clean way to prove the ROM call reaches the
 * routine with its arguments in the right registers.
 *
 * (The corpus's `chrout` would need the KERNAL screen editor's cursor set up
 * before its character reaches screen RAM; VICE's `-autostart` skips that
 * setup, so its character lands in the KERNAL's cursor buffer instead.
 * `setLfs` sidesteps that.)
 */
class RomCallTest {
    @Test fun `a fixed address function is emitted as a jsr to its address`() {
        val ir = emitIr("""
            fun setLfs^0xffba(lfn: ubyte^CPU.A, channel: ubyte^CPU.Y, dev: ubyte^CPU.X)

            fun main() {
                setLfs(5, 9, 8)
                while (true) {}
            }
        """)
        // The asm escape is a double dollar sign.
        val jsr = "jsr ${'$'}${'$'}ffba"
        assertTrue(ir.contains(jsr), "the ROM call must be emitted:\n$ir")
        // The constraint list follows the declaration order of the
        // parameters: lfn in A, channel in Y, dev in X.
        assertTrue(ir.contains("\"a,y,x\""), "constraints must follow declaration order:\n$ir")
    }

    @Test fun `a rom call reaches the routine with its argument in its register`() {
        val bytes = runProgram("""
            fun setLfs^0xffba(lfn: ubyte^CPU.A, channel: ubyte^CPU.Y, dev: ubyte^CPU.X)

            fun main() {
                setLfs(5, 9, 8)
                var dev: ubyte^0xba
                var out: ubyte^0x400
                out = dev
                while (true) {}
            }
        """)
        // The device argument reached the ROM and was written to $BA.
        assertEquals(8, bytes[0].toInt() and 0xFF)
    }

    @Test fun `a different device argument reaches the rom`() {
        val bytes = runProgram("""
            fun setLfs^0xffba(lfn: ubyte^CPU.A, channel: ubyte^CPU.Y, dev: ubyte^CPU.X)

            fun main() {
                setLfs(5, 9, 11)
                var dev: ubyte^0xba
                var out: ubyte^0x400
                out = dev
                while (true) {}
            }
        """)
        // A different argument value must reach the ROM: the result tracks
        // the argument, not a constant.
        assertEquals(11, bytes[0].toInt() and 0xFF)
    }
}
