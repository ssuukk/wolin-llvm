package pl.qus.wolin.it

import kotlin.test.Test
import kotlin.test.assertEquals

class ReturnAtTest {
    /**
     * The `return@` site is a tail jump: the code after it never runs.
     *
     * The observation window is `$0800`, not `$0400`: the jump target
     * `$EA31` is the KERNAL's screen-init routine, and its screen clear
     * overwrites `$0400` with spaces (32) — the jump leaves the program
     * into the ROM, exactly as the brief says it may. `$0800` is outside
     * the screen and keeps the value the program wrote.
     */
    @Test fun `return at a fixed address jumps instead of returning`() {
        val bytes = runProgramAt0800("""
            fun main() {
                var out: ubyte^0x800 = 0
                out = 1
                return@0xea31
                out = 2
                while (true) {}
            }
        """)
        assertEquals(1, bytes[0].toInt() and 0xFF)
    }
}
